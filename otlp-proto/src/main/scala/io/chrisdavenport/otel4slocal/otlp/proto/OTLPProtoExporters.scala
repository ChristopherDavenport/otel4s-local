package io.chrisdavenport.otel4slocal.otlp.proto

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.std.Random
import cats.mtl.Local
import org.typelevel.vault.Vault
import org.typelevel.otel4s.Otel4s
import io.chrisdavenport.otel4slocal.LocalOtel4s
import io.chrisdavenport.otel4slocal.trace.{LocalSpan}

import scala.concurrent.duration._
import org.http4s.ember.client.EmberClientBuilder
import fs2.{Stream, Chunk, Pipe}
import fs2.io.net.Network
import io.opentelemetry.proto.collector.trace.v1.trace_service.TraceService
import org.http4s.Uri
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.AttributeType
import io.opentelemetry.proto.common.v1.common.AnyValue.Value.ArrayValue
import org.typelevel.otel4s.trace.SpanKind
import io.opentelemetry.proto.trace.v1.trace.Status
import org.typelevel.otel4s.trace.Status.Unset
import org.typelevel.otel4s.trace.Status.Ok
import io.chrisdavenport.otel4slocal.trace.LocalEvent
import io.opentelemetry.proto.trace.v1.trace.Span.Event
import io.chrisdavenport.otel4slocal.trace.LocalLink
import io.opentelemetry.proto.trace.v1.trace.Span.Link
import io.opentelemetry.proto.collector.trace.v1.trace_service.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.trace.ResourceSpans
import io.opentelemetry.proto.trace.v1.trace.ScopeSpans
import io.opentelemetry.proto.trace.v1.trace.Span
import io.opentelemetry.proto.common.v1.common.KeyValue
import io.opentelemetry.proto.common.v1.common.AnyValue
import com.google.protobuf.ByteString
import org.typelevel.otel4s.ContextPropagators
import org.http4s._
import org.http4s.client.Client
import io.opentelemetry.proto.common.v1.common.InstrumentationScope
import org.typelevel.ci.CIString

object OTLPProtoExporters {

  def buildGrpc[F[_]: Async](
    baseUri: Uri,
    headers: Headers,
    timeout: Duration,
    concurrency: Int,
  ): Resource[F, fs2.Pipe[F, LocalSpan, Nothing]] = {
    EmberClientBuilder.default[F].withHttp2.build
      .map(grpc(_, baseUri, headers, timeout, concurrency))
  }

  def grpc[F[_]: Temporal](client: Client[F], baseUri: Uri, headers: Headers, timeout: Duration, concurrency: Int): fs2.Pipe[F, LocalSpan, Nothing] = {
    val traceService = TraceService.fromClient(client, baseUri)

    {(s: Stream[F, LocalSpan]) =>
      def process: Stream[F, Nothing] = s.chunks.parEvalMap(concurrency){ chunk =>
        processChunk(chunk)
          .traverse(traceService.`export`(_, headers))
          .timeout(timeout)
      }.drain.handleErrorWith(_ => process)
      process
    }
  }

  def buildHttpProto[F[_]: Async](
    baseUri: Uri,
    headers: Headers,
    timeout: Duration,
    concurrency: Int,
  ): Resource[F, fs2.Pipe[F, LocalSpan, Nothing]] = {
    EmberClientBuilder.default[F].withHttp2.build
      .map(httpProto(_, baseUri, headers, timeout, concurrency))
  }

  def httpProto[F[_]: Async](client: Client[F], baseUri: Uri, headers: Headers, timeout: Duration, concurrency: Int): fs2.Pipe[F, LocalSpan, Nothing] = {
    val proto = Header.Raw(CIString("Content-Type"),"application/x-protobuf")

    {(s: Stream[F, LocalSpan]) =>
      def process: Stream[F, Nothing] = s.chunks.parEvalMap(concurrency){ chunk =>
        processChunk(chunk)
          .traverse{ exportReq =>
            client.run(
              Request[F](Method.POST, baseUri, HttpVersion.`HTTP/1.1`)
                .withEntity(exportReq.toByteArray)
                .removeHeader(CIString("Content-Type"))
                .putHeaders(proto)
            ).use{ resp =>
              resp.status.isSuccess.pure[F]
            }
          }
          .timeout(timeout)
      }.drain.handleErrorWith(_ => process)
      process
    }
  }

  def processChunk(chunk: Chunk[LocalSpan]): Option[ExportTraceServiceRequest] = {
    val vector = chunk.toVector
    vector.headOption.map{ls =>
      val base : Seq[ScopeSpans] = vector.groupBy(ls => ls.scopeState)
      .toList
      .map{ case (tracerState, localSpans) =>
        ScopeSpans(
          Some(InstrumentationScope(tracerState.instrumentationScopeName)),
          spans = localSpans.map(spanTransform)
        )
      }
      val rs = Seq(
        ResourceSpans(
            Some(
              io.opentelemetry.proto.resource.v1.resource.Resource(Seq(
                KeyValue("service.name", Some(AnyValue(AnyValue.Value.StringValue(ls.resourceState.serviceName)))),
              ).appendedAll(ls.resourceState.resourceAttributes.map(attributeTransform(_))))
            ),
            base
        )
      )
      ExportTraceServiceRequest(rs)
    }

    // val request = ExportTraceServiceRequest(base)

    // traceService.`export`(request, headers).void
  }

  def attributeTransform(attribute: Attribute[_]): KeyValue = {
    // Wearing my "I sure hope I know what I'm doing hat" 
    val value: AnyValue =  attribute.key.`type` match {
      case AttributeType.Boolean => 
        AnyValue(AnyValue.Value.BoolValue(attribute.value.asInstanceOf[Boolean]))
      case AttributeType.Double =>
        AnyValue(AnyValue.Value.DoubleValue(attribute.value.asInstanceOf[Double]))
      case AttributeType.String => 
        AnyValue(AnyValue.Value.StringValue(attribute.value.asInstanceOf[String]))
      case AttributeType.Long => 
        AnyValue(AnyValue.Value.IntValue(attribute.value.asInstanceOf[Long]))
      case AttributeType.BooleanList => 
        val typedList = attribute.value.asInstanceOf[List[Boolean]]
        AnyValue(AnyValue.Value.ArrayValue(io.opentelemetry.proto.common.v1.common.ArrayValue(
          typedList.map( value =>
            AnyValue(AnyValue.Value.BoolValue(value))
          )
        )))
      case AttributeType.DoubleList =>
        val typedList = attribute.value.asInstanceOf[List[Double]]
        AnyValue(AnyValue.Value.ArrayValue(io.opentelemetry.proto.common.v1.common.ArrayValue(
          typedList.map( value =>
            AnyValue(AnyValue.Value.DoubleValue(value))
          )
        )))
      case AttributeType.StringList => 
        val typedList = attribute.value.asInstanceOf[List[String]]
        AnyValue(AnyValue.Value.ArrayValue(io.opentelemetry.proto.common.v1.common.ArrayValue(
          typedList.map( value =>
            AnyValue(AnyValue.Value.StringValue(value))
          )
        )))
      case AttributeType.LongList => 
        val typedList = attribute.value.asInstanceOf[List[Long]]
        AnyValue(AnyValue.Value.ArrayValue(io.opentelemetry.proto.common.v1.common.ArrayValue(
          typedList.map( value =>
            AnyValue(AnyValue.Value.IntValue(value))
          )
        )))
    }
    
    KeyValue(attribute.key.name, value.some)
  }

  def kindTransform(kind: SpanKind): Span.SpanKind = kind match {
    case SpanKind.Internal =>  Span.SpanKind.SPAN_KIND_INTERNAL
    case SpanKind.Server => Span.SpanKind.SPAN_KIND_SERVER
    case SpanKind.Client => Span.SpanKind.SPAN_KIND_CLIENT
    case SpanKind.Producer => Span.SpanKind.SPAN_KIND_PRODUCER
    case SpanKind.Consumer => Span.SpanKind.SPAN_KIND_CONSUMER
  }

  def statusTransform(status: org.typelevel.otel4s.trace.Status, message: String): Status = status match {
    case org.typelevel.otel4s.trace.Status.Unset => 
      Status(message, Status.StatusCode.STATUS_CODE_UNSET)
    case org.typelevel.otel4s.trace.Status.Ok =>
      Status(message, Status.StatusCode.STATUS_CODE_OK)
    case org.typelevel.otel4s.trace.Status.Error =>
      Status(message, Status.StatusCode.STATUS_CODE_ERROR)
  }

  def eventTransform(ls: LocalEvent): Event = Event(
    timeUnixNano = ls.time.toMicros * 1000,
    name = ls.name,
    attributes = ls.attributes.map(attributeTransform),
    droppedAttributesCount = ls.droppedAttributes,
  )

  def linkTransform(ls: LocalLink): Link = Link(
    traceId = ByteString.copyFrom(ls.spanContext.traceId.toArray),
    spanId = ByteString.copyFrom(ls.spanContext.spanId.toArray),
    attributes = ls.attributes.map(attributeTransform),
    droppedAttributesCount = ls.droppedAttributes,
  )

  

  def spanTransform(ls: LocalSpan): Span = Span(
    traceId = ByteString.copyFrom(ls.spanContext.traceId.toArray),
    spanId = ByteString.copyFrom(ls.spanContext.spanId.toArray),
    startTimeUnixNano = ls.mutable.startTime.toMicros * 1000,
    endTimeUnixNano = ls.mutable.endTime.get.toMicros * 1000, // Spans never get processed without an end time
    name = ls.mutable.name,
    kind = kindTransform(ls.kind),
    
    attributes = ls.mutable.attributes.map(attributeTransform),
    droppedAttributesCount = ls.mutable.droppedAttributes,
    events = ls.mutable.events.map(eventTransform),
    droppedEventsCount = ls.mutable.droppedEvents,
    links = ls.mutable.links.map(linkTransform),
    droppedLinksCount = ls.mutable.droppedLinks,

    status = statusTransform(ls.mutable.status, ls.mutable.statusDescription.getOrElse("")).some
  )

}