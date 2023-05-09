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

import scala.concurrent.duration._

class LocalOtel4s[F[_]: Temporal: Random] private (
  local: Local[F, Vault], // How the fiber state interacts with this system

  state: MapRef[F, SpanContext, Option[trace.LocalSpan]], // Where we store spans in motion
  processor: fs2.concurrent.Channel[F, trace.LocalSpan] // This is how we handle our completed spans
) extends Otel4s[F]{

  // Not doing metrics yet
  def meterProvider: MeterProvider[F] = MeterProvider.noop[F]


  def propagators: ContextPropagators[F] = new ContextPropagators[F] {
    def textMapPropagator: TextMapPropagator[F] = new TextMapPropagator[F] {
      def extract[A: TextMapGetter](ctx: Vault, carrier: A): Vault =
        ???
      def inject[A: TextMapSetter](ctx: Vault, carrier: A): F[Unit] = ???
    }
  }
  def tracerProvider: TracerProvider[F] = new TracerProvider[F] {
    def tracer(name: String): TracerBuilder[F] = new trace.LocalTracerBuilder[F](name, None, None, true, local, state, processor)
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
      // This may be too contentious at some point
      state <- Resource.eval(Ref[F].of(Map.empty[SpanContext, trace.LocalSpan]))
      map = MapRef.fromSingleImmutableMapRef(state)
      channel <- Resource.eval(fs2.concurrent.Channel.unbounded[F, trace.LocalSpan])
      _ <- Resource.make(consumer(channel.stream).start)(fiber => fiber.join.void.timeoutTo(timeoutChannelProcessClose, new RuntimeException("LocalOtel4s: Failed to Process Channel Before Shutdown").raiseError[F, Unit]))
      _ <- Resource.make(Applicative[F].unit){_ =>
        def check: F[Unit] = state.get.flatMap(map => if (map.isEmpty) channel.close.void else Concurrent[F].cede >> check)
        check.timeoutTo(timeoutSpanClose, new RuntimeException("LocalOtel4s: Current spans did not close prior to resource shutdown").raiseError[F, Unit])
      }
    } yield new LocalOtel4s[F](
      local,
      map,
      channel
    )
  }
}