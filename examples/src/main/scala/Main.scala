package io.chrisdavenport.otel4slocal

import cats.effect._
import org.typelevel.vault.Vault
import cats.mtl.Local

import org.typelevel.otel4s.{Otel4s, TextMapPropagator}
import io.chrisdavenport.otel4slocal.trace.LocalSpan
import scala.concurrent.duration._
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.Attribute
import org.http4s.implicits._
import io.chrisdavenport.crossplatformioapp.CrossPlatformIOApp

object Main extends CrossPlatformIOApp {

  def run(args: List[String]): IO[ExitCode] = {
    cats.effect.std.Random.scalaUtilRandom[IO].flatMap{ implicit R: cats.effect.std.Random[IO] =>
      LocalOtel4s.localVault[IO].flatMap{ implicit L: Local[IO, Vault] =>
        io.chrisdavenport.otel4slocal.api.OpenTelemetry.build[IO].use( otel4s =>
        // io.chrisdavenport.otel4slocal.otlp.OTLPExporter.build[IO](uri"http://localhost:4317").use( exporter =>
        // LocalOtel4s.build(local, LocalContextPropagators.traceparent, exporter).use(otel4s =>
          otel4s.tracerProvider.get("ExampleApp").flatMap{tracer =>
            tracer.spanBuilder("Test").build.use{ span =>
              span.addAttribute(Attribute("test.attribute", "huzzah")) >>
              span.addAttribute(Attribute("test.list", List("foo", "bar"))) >>
              tracer.spanBuilder("Test2").build.use_ >> // Normal
              tracer.spanBuilder("Resource").wrapResource(
                Resource.make(tracer.spanBuilder("inside create").build.use_)(_ => tracer.spanBuilder("inside shutdown").build.use_)
              ).build.use{_ =>
                tracer.spanBuilder("inside use").build.use_
              } >> // Resource
              tracer.noopScope(
                tracer.spanBuilder("Test3").build.use_ // Noop Working
              )
            }
          }
        )
      }
    }
  }.as(ExitCode.Success)

}