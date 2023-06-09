package io.chrisdavenport.otel4slocal.trace

import cats._
import cats.effect._
import cats.syntax.all._
import org.typelevel.vault.Vault
import cats.mtl.Local
import org.typelevel.otel4s.trace.{TracerBuilder, TracerProvider, Tracer, SpanContext, SpanBuilder, Span}
import cats.effect.std.{MapRef, Random}
import org.typelevel.otel4s.TextMapPropagator
import org.typelevel.otel4s.Attribute

class LocalTracerBuilder[F[_]: Temporal: Random] private[otel4slocal] (

  serviceName: String,
  resourceAttributes: List[Attribute[_]],

  instrumentationScopeName: String,
  version: Option[String],
  schemaUrl: Option[String],
  enabled: Boolean,

  local: Local[F, Vault],
  textMapPropagator: TextMapPropagator[F],
  state: MapRef[F, SpanContext, Option[LocalSpan]], // Where we store spans in motion
  processor: fs2.concurrent.Channel[F, LocalSpan] // This is how we handle our completed spans
) extends TracerBuilder[F] { self =>

  private def copy(
    instrumentationScopeName: String = self.instrumentationScopeName,
    version: Option[String] = self.version,
    schemaUrl: Option[String] = self.schemaUrl,
  ) = new LocalTracerBuilder[F](self.serviceName, self.resourceAttributes, instrumentationScopeName, version, schemaUrl, self.enabled, self.local, self.textMapPropagator, self.state, self.processor)

  def withSchemaUrl(s: String) = copy(schemaUrl = s.some)
  def withVersion(version: String) = copy(version = version.some)


  def get: F[Tracer[F]] = new LocalTracer[F](serviceName, resourceAttributes, instrumentationScopeName, version, schemaUrl, enabled, local, textMapPropagator, state, processor).pure[F].widen

}