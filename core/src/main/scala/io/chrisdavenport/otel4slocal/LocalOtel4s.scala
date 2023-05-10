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
  processor: fs2.concurrent.Channel[F, trace.LocalSpan], // This is how we handle our completed spans
  propagator: ContextPropagators[F],
) extends Otel4s[F]{

  // Not doing metrics yet
  def meterProvider: MeterProvider[F] = MeterProvider.noop[F]

  def propagators: ContextPropagators[F] = propagator

  def tracerProvider: TracerProvider[F] = new TracerProvider[F] {
    def tracer(name: String): TracerBuilder[F] = new trace.LocalTracerBuilder[F](name, None, None, true, local, propagators.textMapPropagator, state, processor)
  }
}

object LocalOtel4s {
  def build[F[_]: Temporal: Random](
    local: Local[F, Vault],
    propagator: ContextPropagators[F],
    exporter: fs2.Pipe[F, trace.LocalSpan, Nothing],
    timeoutSpanClose: FiniteDuration = 5.seconds,
    timeoutChannelProcessClose: FiniteDuration = 5.seconds,
  ): Resource[F, Otel4s[F]] = {

    for {
      // This may be too contentious at some point, but gives us the generalized ability
      // to check on the status of all running spans.
      state <- Resource.eval(Ref[F].of(Map.empty[SpanContext, trace.LocalSpan]))
      map = MapRef.fromSingleImmutableMapRef(state)
      channel <- Resource.eval(fs2.concurrent.Channel.unbounded[F, trace.LocalSpan])
      // This is how long we give processing to complete By the time we reach close here the channel is closed
      _ <- Resource.make(channel.stream.through(exporter).compile.drain.start)(fiber => fiber.join.void.timeoutTo(timeoutChannelProcessClose, new RuntimeException("LocalOtel4s: Failed to Process Channel Before Shutdown").raiseError[F, Unit]))
      _ <- Resource.make(Applicative[F].unit){_ =>
        def check: F[Unit] = state.get.flatMap(map => if (map.isEmpty) channel.close.void else Concurrent[F].cede >> check)
        check.timeoutTo(timeoutSpanClose, channel.close.void >> new RuntimeException("LocalOtel4s: Current spans did not close prior to resource shutdown").raiseError[F, Unit])
      } // First make sure all the current running spans complete. This is the amount of time for in progress work to complete before a hard shutdown
    } yield new LocalOtel4s[F](
      local,
      map,
      channel,
      propagator
    )
  }

  def localVault[F[_]: LiftIO: MonadCancelThrow]: F[Local[F, Vault]] = {
    LiftIO[F].liftIO(IOLocal(Vault.empty)).map(localForIoLocal(_))
  }

  private def localForIoLocal[F[_]: MonadCancelThrow: LiftIO, E](
      ioLocal: IOLocal[E]
  ): Local[F, E] =
    new Local[F, E] {
      def applicative =
        Applicative[F]
      def ask[E2 >: E] =
        Functor[F].widen[E, E2](ioLocal.get.to[F])
      def local[A](fa: F[A])(f: E => E): F[A] =
        MonadCancelThrow[F].bracket(ioLocal.modify(e => (f(e), e)).to[F])(_ =>
          fa
        )(ioLocal.set(_).to[F])
    }

}