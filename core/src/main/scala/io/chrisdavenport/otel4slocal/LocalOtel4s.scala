package io.chrisdavenport.otel4slocal

import cats._
import cats.effect._
import cats.syntax.all._
import org.typelevel.vault.Vault
import cats.mtl.Local

import org.typelevel.otel4s.{ContextPropagators, Otel4s, TextMapPropagator, TextMapGetter, TextMapSetter}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.{TracerBuilder, TracerProvider, Tracer, SpanContext, SpanBuilder, Span}

class LocalOtel4s[F[_]: Concurrent] private (
  local: Local[F, Vault]
) extends Otel4s[F]{

  // Not doing tracing yet
  def meterProvider: MeterProvider[F] = MeterProvider.noop[F]


  def propagators: ContextPropagators[F] = new ContextPropagators[F] {
    def textMapPropagator: TextMapPropagator[F] = new TextMapPropagator[F] {
      def extract[A: TextMapGetter](ctx: Vault, carrier: A): Vault =
        ???
      def inject[A: TextMapSetter](ctx: Vault, carrier: A): F[Unit] = ???
    }
  }
  def tracerProvider: TracerProvider[F] = new TracerProvider[F] {
    def tracer(name: String): TracerBuilder[F] = new TracerBuilder[F] {
      def get: F[Tracer[F]] = Applicative[F].unit.map(_ =>
        new Tracer[F]{
          def childScope[A](parent: SpanContext)(fa: F[A]): F[A] = ???
          def currentSpanContext: F[Option[SpanContext]] = ???
          def joinOrRoot[A, C: TextMapGetter](carrier: C)(fa: F[A]): F[A] = ???
          def meta: Tracer.Meta[F] = new Tracer.Meta[F]{
              // Members declared in org.typelevel.otel4s.meta.InstrumentMeta
              def isEnabled: Boolean = ???
              def unit: F[Unit] = ???

              // Members declared in org.typelevel.otel4s.trace.Tracer$.Meta
              def noopResSpan[A](resource: Resource[F, A]):SpanBuilder.Aux[F,Span.Res[F, A]] = ???
              def noopSpanBuilder: SpanBuilder.Aux[F,Span[F]] = ???
          }
          def noopScope[A](fa: F[A]): F[A] = ???
          def rootScope[A](fa: F[A]): F[A] = ???
          def spanBuilder(name: String):SpanBuilder.Aux[F,Span[F]] = ???
        }
      )
      def withSchemaUrl(schemaUrl: String): TracerBuilder[F] = ???
      def withVersion(version: String): TracerBuilder[F] = ???

    }
  }

}