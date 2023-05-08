package org.typelevel.otel4s.backdoor

import cats.Applicative
import org.typelevel.otel4s.trace.Span.Backend
import scala.concurrent.duration.FiniteDuration
import org.typelevel.otel4s.trace.{Span, SpanFinalizer}

abstract class BackdoorBackend[F[_]](endF: F[Unit], endF2: FiniteDuration => F[Unit]) extends Backend[F]{
  override def end: F[Unit] = endF
  override def end(timestamp: FiniteDuration): F[Unit] = endF2(timestamp)
}

object StrategyRunBackend {
  def run[F[_]: Applicative](
    backend: Span.Backend[F],
    finalizer: SpanFinalizer
  ) = SpanFinalizer.run(backend, finalizer)
}