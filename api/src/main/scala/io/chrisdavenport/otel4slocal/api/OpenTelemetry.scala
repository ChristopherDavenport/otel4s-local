package io.chrisdavenport.otel4slocal.api

import cats.syntax.all._
import cats._ 
import cats.effect._
import cats.effect.std.{Random, Env, Console}
import fs2.io.net.Network
import org.http4s._
import scala.concurrent.duration._
import org.http4s.implicits._
import cats.mtl.Local
import org.typelevel.vault.Vault

import io.chrisdavenport.otel4slocal.{LocalOtel4s, LocalContextPropagators}
import io.chrisdavenport.otel4slocal.otlp.proto.OTLPProtoExporters
import io.chrisdavenport.otel4slocal.otlp.json.OTLPJsonExporters
import org.typelevel.ci.CIString
import io.chrisdavenport.otel4slocal.trace.LocalSpan

object OpenTelemetry {
  private type VaultLocal[F[_]] = Local[F, Vault]

  def build[F[_]: Async : Random : Env : Network : VaultLocal: Console] = {
    val env = Env[F].mapK(Resource.liftK[F])
    def envNonEmpty(name: String) = env.get(name).map(_.filter(_.nonEmpty))

    for {
      disabled <- envNonEmpty("OTEL_SDK_DISABLED")
      serviceName <- envNonEmpty("OTEL_SERVICE_NAME").map(_.getOrElse("unknown_service"))
      propagatorsStringList <- envNonEmpty("OTEL_PROPAGATORS").map(_.getOrElse("tracecontext"))
        .map(_.toLowerCase())
        .map{_.split(",").toList}
      propagators = {
        val propagatorsList = propagatorsStringList.mapFilter{
          case "tracecontext" => LocalContextPropagators.traceparent[F].some
          case "b3" =>  LocalContextPropagators.b3.some
          case "b3multi" => LocalContextPropagators.b3Multi.some
          case _ => None
        }
        LocalContextPropagators.composite(propagatorsList)
      }
      resourceAttributes <- envNonEmpty("OTEL_RESOURCE_ATTRIBUTES")
        .map(_.map(splitKV(_)).map(_.map{case (k, v) =>
          org.typelevel.otel4s.Attribute(k , v)
        }).getOrElse(List.empty))
      disabledKeys <- envNonEmpty("OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS")

      traceExporter <- envNonEmpty("OTEL_TRACES_EXPORTER")
        .map(_.getOrElse("otlp")) // Only supports otlp or none
      traceSamplerOpt <- envNonEmpty("OTEL_TRACES_SAMPLER")
        .map(_.getOrElse("parent_based_always_on"))
      traceSamplerArgOpt <- envNonEmpty("OTEL_TRACES_SAMPLER_ARG")


      defaultEndpointOpt <- envNonEmpty("OTEL_EXPORTER_OTLP_ENDPOINT")
        .map(_.flatMap(Uri.fromString(_).toOption))

      defaultEndpointGrpc = defaultEndpointOpt.getOrElse(uri"http://localhost:4317")
      defaultEndpointHttp = defaultEndpointOpt.map(_ / "v1" / "traces").getOrElse(uri"http://localhost:4318/v1/traces")

      defaultHeaders <- envNonEmpty("OTEL_EXPORTER_OTLP_HEADERS")
        .map(
          _.map(splitKV(_))
            .map(ls => Headers(ls.map(t => Header.ToRaw.rawToRaw(Header.Raw(CIString(t._1), t._2)))))
            .getOrElse(Headers.empty)
        )
      defaultOTLPTimeout <- envNonEmpty("OTEL_EXPORTER_OTLP_TIMEOUT")
        .map(_.flatMap(s => Either.catchNonFatal(Duration(s)).toOption).getOrElse(10.seconds)) // Default 10s
      defaultExporterProtocol <- envNonEmpty("OTEL_EXPORTER_OTLP_PROTOCOL")
        .map(_.getOrElse("grpc")) // Currently only support grpc
      defaultCertificate <- envNonEmpty("OTEL_EXPORTER_OTLP_CERTIFICATE")
      defaultClientKey <- envNonEmpty("OTEL_EXPORTER_OTLP_CLIENT_KEY")

      traceEndpointOpt <- envNonEmpty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
        .map(_.flatMap(Uri.fromString(_).toOption))
      tracesHeadersOpt <- envNonEmpty("OTEL_EXPORTER_OTLP_TRACES_HEADERS")
      .map(
          _.map(splitKV(_))
            .map(ls => Headers(ls.map(t => Header.ToRaw.rawToRaw(Header.Raw(CIString(t._1), t._2)))))
        )
      tracesOTLPTimeout <- envNonEmpty("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT")
        .map(_.flatMap(s => Either.catchNonFatal(Duration(s)).toOption))
      tracesExporterProtocolOpt <- envNonEmpty("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL")
      tracesCertificate <- envNonEmpty("OTEL_EXPORTER_OTLP_TRACES_CERTIFICATE")
      tracesClientKey <- envNonEmpty("OTEL_EXPORTER_OTLP_TRACES_CLIENT_KEY")
      // Custom Trace OTLP Settings
      tracesConcurrency <- envNonEmpty("OTEL_EXPORTER_OTLP_TRACES_BATCH_CONCURRENCY")
        .map(_.flatMap(s => s.toIntOption).getOrElse(10))

      tracesProtocol = tracesExporterProtocolOpt.getOrElse(defaultExporterProtocol)
      exporter <- (traceExporter, tracesProtocol) match {
        case ("none", _) =>
          Resource.pure[F, fs2.Pipe[F, LocalSpan, Nothing]]({(s: fs2.Stream[F, LocalSpan]) => s.drain})
        case ("otlp", "grpc") => OTLPProtoExporters.buildGrpc[F](
          traceEndpointOpt.getOrElse(defaultEndpointGrpc),
          tracesHeadersOpt.getOrElse(defaultHeaders),
          tracesOTLPTimeout.getOrElse(defaultOTLPTimeout),
          concurrency = tracesConcurrency
        )
        case ("otlp", "http/protobuf") =>
          OTLPProtoExporters.buildHttpProto(
            traceEndpointOpt.getOrElse(defaultEndpointHttp),
            tracesHeadersOpt.getOrElse(defaultHeaders),
            tracesOTLPTimeout.getOrElse(defaultOTLPTimeout),
            concurrency = tracesConcurrency
          )
        case ("otlp", "http/json") =>
          OTLPJsonExporters.buildHttpJson(
            traceEndpointOpt.getOrElse(defaultEndpointHttp),
            tracesHeadersOpt.getOrElse(defaultHeaders),
            tracesOTLPTimeout.getOrElse(defaultOTLPTimeout),
            concurrency = tracesConcurrency
          )
        case ("otlp", "stdout") =>
          Resource.pure[F, fs2.Pipe[F, LocalSpan, Nothing]](OTLPJsonExporters.stdout[F])
        case (exporter, protocol) =>
          Resource.eval(Console[F].println(s"Unknown Exporter Protocol Pair - ($exporter, $protocol) - defaulting to noop")) >>
          Resource.pure[F, fs2.Pipe[F, LocalSpan, Nothing]]({(s: fs2.Stream[F, LocalSpan]) => s.drain})
      }
      otel4s <- LocalOtel4s.build[F](
        local = Local[F, Vault],
        propagator = propagators,
        exporter = exporter,
        serviceName = serviceName,
        resourceAttributes = resourceAttributes
      )
    } yield otel4s

  }

  // api-key=key,other-config-value=value
  private def splitKV(string: String): List[(String, String)] = {
    string.split(",").toList.flatMap{kvString =>
      kvString.split("=").toList match {
        case k :: v :: Nil => (k, v) :: Nil
        case _ => Nil
      }
    }
  }
}