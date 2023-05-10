package io.chrisdavenport.otel4slocal.otel

import cats.syntax.all._
import cats.effect._
import cats.effect.std.Random
import cats.mtl.Local
import org.typelevel.vault.Vault
import org.typelevel.otel4s.Otel4s
import io.chrisdavenport.otel4slocal.LocalOtel4s
import io.chrisdavenport.otel4slocal.trace.{LocalSpan}

import scala.concurrent.duration._
import org.http4s.ember.client.EmberClientBuilder
import fs2.{Stream, Chunk}
import fs2.io.net.Network
import io.opentelemetry.proto.collector.trace.v1.trace_service.TraceService
import org.http4s.Uri

object Http4sGrpcOtel {

  def fromLocal[F[_]: Async: Network: Random](
    local: Local[F, Vault],
    otelUri: Uri,
    timeoutSpanClose: FiniteDuration = 5.seconds,
    timeoutChannelProcessClose: FiniteDuration = 5.seconds
  ): Resource[F, Otel4s[F]] = {
    EmberClientBuilder.default[F].withHttp2.build.map(TraceService.fromClient(_, otelUri)).flatMap{
      traceService => 

      LocalOtel4s.build(
        local, 
        {(s: Stream[F, LocalSpan]) => s.chunks.evalMap(processChunk(traceService, _)).compile.drain},
        timeoutSpanClose,
        timeoutChannelProcessClose
      )
    }
  }


  import io.opentelemetry.proto.collector.trace.v1.trace_service.ExportTraceServiceRequest
  import io.opentelemetry.proto.trace.v1.trace.ResourceSpans
  import io.opentelemetry.proto.trace.v1.trace.ScopeSpans
  import io.opentelemetry.proto.trace.v1.trace.Span
  import io.opentelemetry.proto.resource.v1.resource.Resource
  import io.opentelemetry.proto.common.v1.common.KeyValue
  import io.opentelemetry.proto.common.v1.common.AnyValue
  import com.google.protobuf.ByteString
  def processChunk[F[_]: Concurrent](traceService: TraceService[F], chunk: Chunk[LocalSpan]): F[Unit] = {
    val base = chunk.toVector.groupBy(ls => ls.tracerState)
      .toList
      .map{ case (tracerState, localSpans) => 
        ResourceSpans(
          Some(
              Resource(Seq(
                KeyValue("service.name", Some(AnyValue(AnyValue.Value.StringValue(tracerState.name))))
              ))
            ),
          Seq(
            ScopeSpans(
              None,
              spans = localSpans.map(ls => 
                Span(
                  traceId = ByteString.copyFrom(ls.spanContext.traceId.toArray),
                  spanId = ByteString.copyFrom(ls.spanContext.spanId.toArray),
                  startTimeUnixNano = ls.mutable.startTime.toMicros * 1000,
                  endTimeUnixNano = ls.mutable.endTime.get.toMicros * 1000,
                  name = ls.mutable.name,
                  
                )
              )
            )
          )
        )
        
      }
    val request = ExportTraceServiceRequest(base)

    traceService.`export`(request, org.http4s.Headers.empty).void
  }



}