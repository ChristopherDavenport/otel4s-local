package io.chrisdavenport.otel4slocal.trace

import cats._
import cats.effect._
import cats.syntax.all._
import org.typelevel.vault.Vault
import cats.mtl.Local
import org.typelevel.otel4s.trace.{TracerBuilder, TracerProvider, Tracer, SpanContext, SpanBuilder, Span}
import cats.effect.std.{MapRef, Random}

class LocalTracerBuilder[F[_]: Temporal: Random] private[otel4slocal] (
  name: String,
  version: Option[String],
  schemaUrl: Option[String],
  enabled: Boolean,

  local: Local[F, Vault],
  state: MapRef[F, SpanContext, Option[LocalSpan]], // Where we store spans in motion
  processor: fs2.concurrent.Channel[F, LocalSpan] // This is how we handle our completed spans
) extends TracerBuilder[F] { self =>

  private def copy(
    name: String = self.name,
    version: Option[String] = self.version,
    schemaUrl: Option[String] = self.schemaUrl,
  ) = new LocalTracerBuilder[F](name, version, schemaUrl, self.enabled, self.local, self.state, self.processor)

  def withSchemaUrl(s: String) = copy(schemaUrl = s.some)
  def withVersion(version: String) = copy(version = version.some)


  def get: F[Tracer[F]] = new LocalTracer[F](name, version, schemaUrl, enabled, local, state, processor).pure[F]

}