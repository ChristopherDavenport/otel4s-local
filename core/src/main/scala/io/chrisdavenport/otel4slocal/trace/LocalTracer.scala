package io.chrisdavenport.otel4slocal.trace

import cats._
import cats.effect._
import cats.syntax.all._
import org.typelevel.vault.Vault
import cats.mtl.Local
import org.typelevel.otel4s.TextMapGetter
import org.typelevel.otel4s.trace.{TracerBuilder, TracerProvider, Tracer, SpanContext, SpanBuilder, Span}

class LocalTracer[F[_]: Concurrent](
  name: String,
  version: Option[String],
  schemaUrl: Option[String],
  local: Local[F, Vault]
) extends Tracer[F]{
  def childScope[A](parent: SpanContext)(fa: F[A]): F[A] = ???
  def currentSpanContext: F[Option[SpanContext]] = ???
  def joinOrRoot[A, C: TextMapGetter](carrier: C)(fa: F[A]): F[A] = ???
  def meta: Tracer.Meta[F] = ???
  def noopScope[A](fa: F[A]): F[A] = ???
  def rootScope[A](fa: F[A]): F[A] = ???
  def spanBuilder(name: String): SpanBuilder.Aux[F,Span[F]] = ???
}