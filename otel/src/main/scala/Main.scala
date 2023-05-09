package io.chrisdavenport.grpcplayground

import cats.effect._
import cats.syntax.all._
import org.http4s.Headers
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits._
import io.opentelemetry.proto.collector.trace.v1.trace_service.{
  TraceService
}
import io.opentelemetry.proto.collector.trace.v1.trace_service.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.trace.ResourceSpans
import io.opentelemetry.proto.trace.v1.trace.ScopeSpans
import io.opentelemetry.proto.trace.v1.trace.Span
import io.opentelemetry.proto.resource.v1.resource.Resource
import com.google.protobuf.ByteString
import scodec.bits.ByteVector
import io.opentelemetry.proto.common.v1.common.InstrumentationScope
import com.comcast.ip4s.Port
import io.opentelemetry.proto.common.v1.common.KeyValue
import io.opentelemetry.proto.common.v1.common.AnyValue
import java.time.Instant

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = EmberClientBuilder.default[IO].withHttp2.build.use{
    iclient =>
    val client = org.http4s.client.middleware.Logger(true, true, logAction = {(s: String) => IO.println(s)}.some)(iclient)
    
    val systemAlg = TraceService.fromClient(client, uri"http://localhost:4317")
    
    systemAlg.`export`(
      ExportTraceServiceRequest(
        Seq(
          ResourceSpans(
            Some(
              Resource(Seq(
                KeyValue("service.name", Some(AnyValue(AnyValue.Value.StringValue("grpc-playground"))))
              ))
            ), 
            Seq(
              ScopeSpans(
                None,
                spans = Seq(
                  Span(
                    traceId = {
                      val traceId = scala.util.Random.nextBytes(16)
                      println(s"TraceId: ${ByteVector(traceId).toHex}")
                      ByteString.copyFrom(traceId)
                    },
                    spanId = {
                      val spanId = scala.util.Random.nextBytes(8)
                      println(s"SpanId: ${ByteVector(spanId).toHex}")
                      ByteString.copyFrom(spanId)
                    },
                    startTimeUnixNano = Instant.now().getEpochSecond() * 1000000000,
                    endTimeUnixNano =  (Instant.now().getEpochSecond() + 1)* 1000000000,
                    name = "Hello-Greetings"
                  )
                )
              )
            )
          )
        )
      ),
      Headers.empty
    ).flatTap(IO.println) >>
    IO.unit.as(ExitCode.Success)
  }
}