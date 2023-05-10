package io.chrisdavenport.otel4slocal

import cats._
import cats.syntax.all._
import org.typelevel.vault.Vault
import org.typelevel.otel4s.{ContextPropagators, TextMapPropagator, TextMapGetter, TextMapSetter}
import org.typelevel.otel4s.trace.{SpanContext, SamplingDecision}
import scodec.bits._
import io.chrisdavenport.otel4slocal.trace.LocalScoped

object LocalContextPropagators {

  private val invalidTraceId = hex"00000000000000000000000000000000"
  private val invalidSpanId = hex"0000000000000000"

  private def createSpanContext(
    traceIdBV: ByteVector,
    traceIdS: String,
    spanIdBV: ByteVector,
    spanIdS: String,
    sampled: Boolean,
  ): SpanContext = new SpanContext {
    override def toString(): String = s"SpanContext(traceIdHex=${traceIdHex},spanIdHex=${spanIdHex}, isRemote=${isRemote}, sampling=${samplingDecision}, isValid=${isValid})"
    def isRemote: Boolean = true
    def isValid: Boolean = true
    def samplingDecision: org.typelevel.otel4s.trace.SamplingDecision = SamplingDecision.fromBoolean(sampled)
    def spanId: scodec.bits.ByteVector = spanIdBV
    def spanIdHex: String = spanIdS
    def traceId: scodec.bits.ByteVector = traceIdBV
    def traceIdHex: String = traceIdS
  }

  // A composite propagator can be built from a list of propagators, or a list of injectors and extractors. The resulting composite Propagator will invoke the Propagators, Injectors, or Extractors, in the order they were specified.
  def composite[F[_]: Applicative](l: List[ContextPropagators[F]]) = new ContextPropagators[F] {
    def textMapPropagator: TextMapPropagator[F] = new TextMapPropagator[F] {
      def extract[A: TextMapGetter](ctx: Vault, carrier: A): Vault = l.foldLeft(ctx){
        case (ctx, cp) => cp.textMapPropagator.extract(ctx, carrier)
      }
      def inject[A: TextMapSetter](ctx: Vault, carrier: A): F[Unit] = l.traverse_(cp =>
        cp.textMapPropagator.inject(ctx, carrier)
      )
    }

  }

  def noopPropagator[F[_]: Applicative] = new ContextPropagators[F] {
    def textMapPropagator: TextMapPropagator[F] = new TextMapPropagator[F] {
      def extract[A: TextMapGetter](ctx: Vault, carrier: A): Vault = ctx
      def inject[A: TextMapSetter](ctx: Vault, carrier: A): F[Unit] = Applicative[F].unit
    }
  }
  def w3cPropagators[F[_]: Applicative] = new ContextPropagators[F] {
    val Extract = "([0-9]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})".r

    def textMapPropagator: TextMapPropagator[F] = new TextMapPropagator[F] {
      def extract[A](ctx: Vault, carrier: A)(implicit T: TextMapGetter[A]): Vault = {

        val parent = T.get(carrier, "traceparent").orElse(T.get(carrier, "TRACEPARENT"))
        parent.fold(ctx){
          case Extract(_, traceIdS, parentIdS, traceFlagS) =>
            (
              ByteVector.fromHex(traceIdS)
                .flatTap(bv => Alternative[Option].guard(bv != invalidTraceId)),
              ByteVector.fromHex(parentIdS)
                .flatTap(bv => Alternative[Option].guard(bv != invalidSpanId)),
              ByteVector.fromHex(traceFlagS)
            ).mapN{ case (traceIdBV, parentIdBV, traceFlag) =>
              val sampled = traceFlag.bits.get(7)
              val sc = createSpanContext(traceIdBV, traceIdS, parentIdBV, parentIdS, sampled)
              LocalScoped.insertIntoVault(ctx, LocalScoped.Spanned(sc))

            }.getOrElse(ctx)
          case _ => ctx
        }

      }
      def inject[A](ctx: Vault, carrier: A)(implicit T: TextMapSetter[A]): F[Unit] = {
        LocalScoped.extractFromVault(ctx) match {
          case LocalScoped.Root => Applicative[F].unit
          case LocalScoped.Noop => Applicative[F].unit
          case LocalScoped.Spanned(spanContext) => Applicative[F].unit.map{_ => // Cheat the evil
            val sampled = if (spanContext.samplingDecision.isSampled) "01" else "00"
            val s = s"00-${spanContext.traceIdHex}-${spanContext.spanIdHex}-${sampled}"
            T.unsafeSet(carrier, "traceparent", s)
          }
        }
      }
    }
  }

  def b3Propagation[F[_]: Applicative] = new ContextPropagators[F] {
    val Extract = "([0-9a-f]{32})-([0-9a-f]{16})-([01d]{1}).*".r
    val Extract2 = "([0-9a-f]{32})-([0-9a-f]{16}).*".r
    def textMapPropagator: TextMapPropagator[F] = new TextMapPropagator[F] {
      def inject[A](ctx: Vault, carrier: A)(implicit T: TextMapSetter[A]): F[Unit] = {
        LocalScoped.extractFromVault(ctx) match {
          case LocalScoped.Root => Applicative[F].unit
          case LocalScoped.Noop => Applicative[F].unit
          case LocalScoped.Spanned(spanContext) => Applicative[F].unit.map{_ => // Cheat the evil
            val sampled = if (spanContext.samplingDecision.isSampled) "1" else "0"
            val s = s"${spanContext.traceIdHex}-${spanContext.spanIdHex}-${sampled}"
            T.unsafeSet(carrier, "b3", s)
          }
        }
      }
      def extract[A](ctx: Vault, carrier: A)(implicit T: TextMapGetter[A]): Vault = {
        def fromStrings(traceIdS: String, spanIdS: String, sampleDecisionS: Option[String]): Option[SpanContext] = {
          (
            ByteVector.fromHex(traceIdS)
            .flatTap(bv => Alternative[Option].guard(bv != invalidTraceId)),
            ByteVector.fromHex(spanIdS)
            .flatTap(bv => Alternative[Option].guard(bv != invalidSpanId)),
            sampleDecisionS match {
              case Some("d") => true.some
              case Some("1") => true.some
              case Some("0") => false.some
              case Some(_) => Option.empty
              case None => true.some
            }
          ).mapN{ case (traceIdBV, spanIdBV, sampled) =>
            createSpanContext(traceIdBV, traceIdS, spanIdBV, spanIdS, sampled)
          }
        }
        T.get(carrier, "b3") match {
          case Some(Extract(traceIdS, spanIdS, sampleDecisionS)) =>
            fromStrings(traceIdS, spanIdS, sampleDecisionS.some)
            .map(sc =>
              LocalScoped.insertIntoVault(ctx, LocalScoped.Spanned(sc))
            ).getOrElse(ctx)
          case Some(Extract2(traceIdS, spanIdS)) =>
            fromStrings(traceIdS, spanIdS, None)
            .map(sc =>
              LocalScoped.insertIntoVault(ctx, LocalScoped.Spanned(sc))
            ).getOrElse(ctx)
          case Some(_) => ctx
          case None => // Fallback to multi header
            (
              T.get(carrier, "x-b3-traceid"),
              T.get(carrier, "x-b3-spanid")
            ).tupled.flatMap{ case (traceIdS, spanIdS) =>
              fromStrings(traceIdS, spanIdS, T.get(carrier, "x-b3-sampled"))
            }.map(sc =>
              LocalScoped.insertIntoVault(ctx, LocalScoped.Spanned(sc))
            ).getOrElse(ctx)
        }
      }
    }
  }
}