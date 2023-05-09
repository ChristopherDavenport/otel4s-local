package io.chrisdavenport.otel4slocal.trace

import cats._
import cats.effect._
import cats.syntax.all._
import org.typelevel.vault.Vault
import cats.mtl.Local

import org.typelevel.otel4s.{Attribute, TextMapGetter, TextMapPropagator}
import org.typelevel.otel4s.meta.InstrumentMeta
import org.typelevel.otel4s.trace.{TracerBuilder, TracerProvider, Tracer, SpanContext, SpanBuilder, Span, SpanOps, SpanFinalizer, Status}
import cats.effect.std.{MapRef, Random}
import org.typelevel.otel4s.trace.SpanKind
import scala.concurrent.duration.FiniteDuration
import org.typelevel.otel4s.trace.Span.Backend
import io.chrisdavenport.otel4slocal.LocalOtel4s

class LocalTracer[F[_]: Temporal: Random](
  tracerName: String,
  tracerVersion: Option[String],
  tracerSchemaUrl: Option[String],
  enabled: Boolean,
  local: Local[F, Vault],
  textMapPropagator: TextMapPropagator[F],

  state: MapRef[F, SpanContext, Option[LocalSpan]], // Where we store spans in motion
  processor: fs2.concurrent.Channel[F, LocalSpan] // This is how we handle our completed spans
) extends Tracer[F]{ tracer =>

  def currentSpanContext: F[Option[SpanContext]] = local.ask.map(LocalScoped.extractFromVault).map{
    case LocalScoped.Spanned(context) => context.some
    case _ => None
  }

  def joinOrRoot[A, C: TextMapGetter](carrier: C)(fa: F[A]): F[A] = {
    local.local(fa)(vault =>
      textMapPropagator.extract(vault, carrier)
    )
  }
  def meta: Tracer.Meta[F] = new Tracer.Meta[F]{
    // Members declared in org.typelevel.otel4s.meta.InstrumentMeta
    def isEnabled: Boolean = enabled
    def unit: F[Unit] = Applicative[F].unit
    
    // Members declared in org.typelevel.otel4s.trace.Tracer$.Meta
    def noopResSpan[A](resource: Resource[F, A]):SpanBuilder.Aux[F,Span.Res[F, A]] =
      Tracer.noop.meta.noopResSpan(resource)
    def noopSpanBuilder:SpanBuilder.Aux[F,Span[F]] = SpanBuilder.noop(Span.Backend.noop)
  }

  def childScope[A](parent: SpanContext)(fa: F[A]): F[A] = local.local(fa)(LocalScoped.insertIntoVault(_, LocalScoped.Spanned(parent)))
  def noopScope[A](fa: F[A]): F[A] = local.local(fa)(LocalScoped.insertIntoVault(_, LocalScoped.Noop))
  def rootScope[A](fa: F[A]): F[A] = local.local(fa)(LocalScoped.insertIntoVault(_, LocalScoped.Root))

  sealed trait Parent
  object Parent {
    case object Root extends Parent
    case class Explicit(spanContext: SpanContext) extends Parent
  }

  class InternalSpanBuilder(
    name: String,
    attributes: Seq[Attribute[_]],
    links: Seq[(SpanContext, Seq[Attribute[_]])],
    strategy: SpanFinalizer.Strategy,
    parent: Option[Parent],
    kind: SpanKind,
    startTimeStamp: Option[FiniteDuration],
  ) extends SpanBuilder[F]{ self =>
    type Result = Span[F]

    def copy(
      name: String= self.name,
      attributes: Seq[Attribute[_]] = self.attributes,
      links: Seq[(SpanContext, Seq[Attribute[_]])] = self.links,
      strategy: SpanFinalizer.Strategy = self.strategy,
      parent: Option[Parent] = self.parent,
      kind: SpanKind = self.kind,
      startTimeStamp: Option[FiniteDuration] = self.startTimeStamp,
    ) = new InternalSpanBuilder(
      name,
      attributes,
      links,
      strategy,
      parent,
      kind,
      startTimeStamp
    )
    /** As seen from class InternalSpanBuilder, the missing signatures are as follows.
 *  For convenience, these are usable as stub implementations.
 */
  def addAttribute[A](attribute: Attribute[A]) =
    copy(attributes = self.attributes :+ attribute)
  def addAttributes(attributes: Seq[Attribute[?]]) = copy(attributes = self.attributes.appendedAll(attributes))
  def addLink(spanContext: SpanContext, attributes: Seq[Attribute[_]])=
    copy(links = self.links :+ (spanContext, attributes))
  def root = copy(parent = Parent.Root.some)
  def withFinalizationStrategy(strategy: SpanFinalizer.Strategy) =
    copy(strategy = strategy)

  def withParent(parent: SpanContext) =
    copy(parent = Parent.Explicit(parent).some)
  def withSpanKind(spanKind: SpanKind) =
    copy(kind = spanKind)
  def withStartTimestamp(timestamp: FiniteDuration)=
    copy(startTimeStamp = timestamp.some)


  def build: SpanOps.Aux[F, Span[F]] = new SpanOps[F] {
    type Result = Span[F]

    class InternalSpan(spanContext: SpanContext, ref: Ref[F, Option[LocalSpan]]) extends Span[F]{
      val endF = {(fd: FiniteDuration) => ref.modify{
          case Some(ls) =>
            None -> ls.copy(mutable = ls.mutable.copy(endTime = fd.some)).some
          case None =>
            None -> None
        }.flatMap{
          case Some(ls) =>
            processor.send(ls).void
          case None => Applicative[F].unit
        }
      }

      def backend: Backend[F] = new org.typelevel.otel4s.backdoor.BackdoorBackend[F](
        Temporal[F].realTime.flatMap(endF),
        endF,
      ) {
        def addAttributes(attributes: Seq[Attribute[?]]): F[Unit] = ref.update{
          case None => None
          case Some(value) => value.copy(mutable = value.mutable.copy(attributes = value.mutable.attributes.appendedAll(attributes))).some
        }
        def addEvent(name: String, attributes: Seq[Attribute[_]]): F[Unit] =
          Clock[F].realTime.flatMap(addEvent(name, _, attributes))
        def addEvent(name: String, timestamp: FiniteDuration, attributes:Seq[Attribute[?]]): F[Unit] = ref.update{
          case None => None
          case Some(value) => value.copy(mutable = value.mutable.copy(events = value.mutable.events.appended(LocalEvent(timestamp, name, attributes.toList, 0)))).some
        }
        def context: SpanContext = spanContext
        def meta: InstrumentMeta[F] = tracer.meta

        def recordException(exception: Throwable, attributes: Seq[Attribute[_]]):F[Unit] =
          addAttributes(attributes.appended(Attribute("error.throwable", exception.toString()))) // TODO whatever this does for java.

        def setStatus(status: Status): F[Unit] = ref.update {
          case None => None
          case Some(value) => value.copy(mutable = value.mutable.copy(status = status)).some
        }
        def setStatus(status: Status, description: String): F[Unit] = ref.update {
          case None => None
          case Some(value) => value.copy(mutable = value.mutable.copy(status = status, statusDescription = description.some)).some
        }
      }
    }

  /** As seen from class $anon, the missing signatures are as follows.
   *  For convenience, these are usable as stub implementations.
   */
    def startUnmanaged(implicit ev: Result =:= Span[F]): F[Span[F]] = {
      for {
        start <- startTimeStamp.fold(Temporal[F].realTime)(_.pure[F])
        scopeParent <- local.ask.map(LocalScoped.extractFromVault).map{
          case LocalScoped.Noop => LocalScoped.Noop : LocalScoped
          case LocalScoped.Root => parent match {
            case None => LocalScoped.Root
            case Some(Parent.Root) => LocalScoped.Root
            case Some(Parent.Explicit(context)) => LocalScoped.Spanned(context)
          }
          case LocalScoped.Spanned(context) => parent match {
            case None => LocalScoped.Spanned(context)
            case Some(Parent.Root) => LocalScoped.Root
            case Some(Parent.Explicit(context)) => LocalScoped.Spanned(context)
          }
        }
        parentSpanContext = scopeParent match {
          case LocalScoped.Noop => None
          case LocalScoped.Root => None
          case LocalScoped.Spanned(parent) => parent.some
        }

        // Only None at this point if NoOp
        buildLocalSpan = {(context: SpanContext) =>
          LocalSpan(
            context,
            parentSpanContext,
            kind,
            LocalSpan.MutableState(
            name,
            startTime = start,
            endTime = None,
            attributes = attributes.toList,
            droppedAttributes = 0,
            events = List.empty,
            droppedEvents = 0,
            links = links.toList.map{ case (context, attributes) => LocalLink(context, "", attributes.toList, 0)},
            droppedLinks = 0,
            status = Status.Unset,
            statusDescription = None,

            ),
            LocalSpan.TracerState(tracerName, tracerVersion, tracerSchemaUrl)
          )
        }
        spanContextOpt <- scopeParent match {
          case LocalScoped.Noop => None.pure[F]
          case LocalScoped.Root => 
            LocalSpan.createSpanContext(None, true).map(sc => (sc, buildLocalSpan(sc)).some)
          case LocalScoped.Spanned(parent) => LocalSpan.createSpanContext(parent.traceId.some, true).map(sc => (sc, buildLocalSpan(sc)).some)
        }

        span: Span[F] <- spanContextOpt match {
          case None => meta.noopSpanBuilder.build.startUnmanaged
          case Some((sc, localSpan)) =>
            val ref = state(sc)
            val span: Span[F] = new InternalSpan(sc, ref)
            ref.update(_ => localSpan.some).as(span)
        }

      } yield span
    }
    def surround[A](fa: F[A]): F[A] = use(_ => fa)
    def use_ : F[Unit] = use(_ => Applicative[F].unit)
    def use[A](f: Span[F] => F[A]): F[A] = {
      Resource.makeCase(startUnmanaged){
        case (span, resourceCase) => {
          strategy.unapply(resourceCase) match {
            case Some(finalizer) => org.typelevel.otel4s.backdoor.StrategyRunBackend.run(span.backend, finalizer)
            case _ => Applicative[F].unit
          }
        } >> span.end

      }.use{ span =>
        local.local(f(span))(LocalScoped.insertIntoVault(_, LocalScoped.Spanned(span.context)))
      }
    }
  }

  // TODO or avoid forever. lol
  def wrapResource[A](resource: Resource[F, A])(implicit ev: InternalSpanBuilder.this.Result =:=Span[F]):
    SpanBuilder.Aux[F,Span.Res[F, A]] = ??? // Type contorting method
  }

  def spanBuilder(name: String): SpanBuilder.Aux[F,Span[F]] = {
    new InternalSpanBuilder(
      name,
      Seq.empty,
      Seq.empty,
      SpanFinalizer.Strategy.reportAbnormal,
      None,
      SpanKind.Internal,
      None
    )
  }
}