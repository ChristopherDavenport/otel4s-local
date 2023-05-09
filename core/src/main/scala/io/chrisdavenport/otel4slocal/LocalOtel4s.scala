package io.chrisdavenport.otel4slocal

import cats._
import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import org.typelevel.vault.Vault
import cats.mtl.Local

import org.typelevel.otel4s.{ContextPropagators, Otel4s, TextMapPropagator, TextMapGetter, TextMapSetter}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.{TracerBuilder, TracerProvider, Tracer, SpanContext, SpanBuilder, Span}
import cats.effect.std.{MapRef, Random}
import scodec.bits._

import scala.concurrent.duration._
import io.chrisdavenport.otel4slocal.trace.LocalScoped
import org.typelevel.otel4s.trace.SamplingDecision
import io.chrisdavenport.otel4slocal.trace.LocalScoped.Root
import io.chrisdavenport.otel4slocal.trace.LocalScoped.Noop
import io.chrisdavenport.otel4slocal.trace.LocalScoped.Spanned

class LocalOtel4s[F[_]: Temporal: Random] private (
  local: Local[F, Vault], // How the fiber state interacts with this system

  state: MapRef[F, SpanContext, Option[trace.LocalSpan]], // Where we store spans in motion
  processor: fs2.concurrent.Channel[F, trace.LocalSpan] // This is how we handle our completed spans
) extends Otel4s[F]{

  // Not doing metrics yet
  def meterProvider: MeterProvider[F] = MeterProvider.noop[F]

  def propagators: ContextPropagators[F] = LocalOtel4s.w3cPropagators[F] // TODO should require sync :(

  def tracerProvider: TracerProvider[F] = new TracerProvider[F] {
    def tracer(name: String): TracerBuilder[F] = new trace.LocalTracerBuilder[F](name, None, None, true, local, propagators.textMapPropagator, state, processor)
  }
}

object LocalOtel4s {
  def build[F[_]: Temporal: Random](
    local: Local[F, Vault],
    consumer: fs2.Stream[F, trace.LocalSpan] => F[Unit],
    timeoutSpanClose: FiniteDuration = 5.seconds,
    timeoutChannelProcessClose: FiniteDuration = 5.seconds
  ): Resource[F, Otel4s[F]] = {

    for {
      // This may be too contentious at some point, but gives us the generalized ability
      // to check on the status of all running spans.
      state <- Resource.eval(Ref[F].of(Map.empty[SpanContext, trace.LocalSpan]))
      map = MapRef.fromSingleImmutableMapRef(state)
      channel <- Resource.eval(fs2.concurrent.Channel.unbounded[F, trace.LocalSpan])
      // This is how long we give processing to complete By the time we reach close here the channel is closed
      _ <- Resource.make(consumer(channel.stream).start)(fiber => fiber.join.void.timeoutTo(timeoutChannelProcessClose, new RuntimeException("LocalOtel4s: Failed to Process Channel Before Shutdown").raiseError[F, Unit]))
      _ <- Resource.make(Applicative[F].unit){_ =>
        def check: F[Unit] = state.get.flatMap(map => if (map.isEmpty) channel.close.void else Concurrent[F].cede >> check)
        check.timeoutTo(timeoutSpanClose, channel.close.void >> new RuntimeException("LocalOtel4s: Current spans did not close prior to resource shutdown").raiseError[F, Unit])
      } // First make sure all the current running spans complete. This is the amount of time for in progress work to complete before a hard shutdown
    } yield new LocalOtel4s[F](
      local,
      map,
      channel
    )
  }

  def w3cPropagators[F[_]: Applicative] = new ContextPropagators[F] {
    val Extract = "([0-9]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})".r
    val invalidTraceId = hex"00000000000000000000000000000000"
    val invalidSpanId = hex"0000000000000000"
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
              val sc = new SpanContext {
                override def toString(): String = s"SpanContext(traceIdHex=${traceIdHex},spanIdHex=${spanIdHex}, isRemote=${isRemote}, sampling=${samplingDecision}, isValid=${isValid})"
                def isRemote: Boolean = true
                def isValid: Boolean = true
                def samplingDecision: org.typelevel.otel4s.trace.SamplingDecision = SamplingDecision.fromBoolean(sampled)
                def spanId: scodec.bits.ByteVector = parentIdBV
                def spanIdHex: String = parentIdS
                def traceId: scodec.bits.ByteVector = traceIdBV
                def traceIdHex: String = traceIdS

              }
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
}