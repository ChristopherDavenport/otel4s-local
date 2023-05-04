package io.chrisdavenport.otel4slocal.trace

import cats._
import cats.effect._
import cats.syntax.all._
import org.typelevel.vault.Vault
import cats.mtl.Local
import org.typelevel.otel4s.trace.{TracerBuilder, TracerProvider, Tracer, SpanContext, SpanBuilder, Span}

class LocalTracerBuilder[F[_]: Concurrent] private (
  name: String,
  version: Option[String],
  schemaUrl: Option[String],
) extends TracerBuilder[F] { self =>

  private def copy(
    name: String = self.name,
    version: Option[String] = self.version,
    schemaUrl: Option[String] = self.schemaUrl,
  ) = new LocalTracerBuilder[F](name, version, schemaUrl)

  def withSchemaUrl(s: String) = copy(schemaUrl = s.some)
  def withVersion(version: String) = copy(version = version.some)


  def get: F[Tracer[F]] = ???

}