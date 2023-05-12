package io.chrisdavenport.otel4slocal.otlp.json

import cats.syntax.all._
import cats.effect.syntax.all._
import fs2.Chunk
import io.chrisdavenport.otel4slocal.trace.LocalSpan
import io.circe._
import io.circe.syntax._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.AttributeType
import io.chrisdavenport.otel4slocal.trace.LocalLink
import io.chrisdavenport.otel4slocal.trace.LocalEvent
import org.typelevel.otel4s.trace.Status.Error
import cats.effect._
import org.http4s._
import scala.concurrent.duration._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString
import org.http4s.circe._
import cats.effect.std.{Console}
import org.typelevel.otel4s.trace.SpanKind
import org.typelevel.otel4s.trace.SpanKind.Client
import org.typelevel.otel4s.trace.SpanKind.Server

object OTLPJsonExporters {

  def buildHttpJson[F[_]: Async](
    baseUri: Uri,
    headers: Headers,
    timeout: Duration,
    concurrency: Int,
  ): Resource[F, fs2.Pipe[F, LocalSpan, Nothing]] = {
    EmberClientBuilder.default[F].withHttp2.build
      .map(httpJson(_, baseUri, headers, timeout, concurrency))
  }

  def httpJson[F[_]: Async](client: Client[F], baseUri: Uri, headers: Headers, timeout: Duration, concurrency: Int): fs2.Pipe[F, LocalSpan, Nothing] = {
    
    {(s: fs2.Stream[F, LocalSpan]) =>
      def process: fs2.Stream[F, Nothing] = s.chunks.parEvalMap(concurrency){ chunk =>
        client.run(
          Request[F](Method.POST, baseUri, HttpVersion.`HTTP/1.1`)
            .withEntity(processChunk(chunk))
        ).use{ resp =>
            resp.status.isSuccess.pure[F]
        }.timeout(timeout)
      }.drain.handleErrorWith(_ => process)
      process
    }
  }

  def stdout[F[_]: Console]: fs2.Pipe[F, LocalSpan, Nothing] = {
    (s: fs2.Stream[F, LocalSpan]) =>
      s.chunks.evalMap(chunk => Console[F].println(processChunk(chunk).noSpaces))
        .drain
  }

  // {
  //   "resourceSpans":[
  //     {
  //        "resource": {
  //           "attributes": [
  //                          {"key":"resource-attr","value":{"stringValue":"resource-attr-val-1"}}
  //           ]
  //         },
  //         "scopeSpans":[
  //           {
  //             "scope":{},
  //             "spans":[
  //               {
  //                 traceId":"",
  //                 "spanId":"",
  //                 "parentSpanId":"",
  //                 "name":"operationA",
  //                 "startTimeUnixNano":"1581452772000000321",
  //                 "endTimeUnixNano":"1581452773000000789",
  //                 "droppedAttributesCount":1,
  //                 "events":[
  //                   {
  //                     "timeUnixNano":"1581452773000000123",
  //                     "name":"event-with-attr",
  //                     "attributes":[{"key":"span-event-attr","value":{"stringValue":"span-event-attr-val"}}],
  //                     "droppedAttributesCount":2
  //                   },
  //                   {"timeUnixNano":"1581452773000000123","name":"event","droppedAttributesCount":2}
  //                 ],
  //                 "droppedEventsCount":1,
  //                 "status":{"message":"status-cancelled","code":2}
  //               },
  //               {
  //                 "traceId":"",
  //                 "spanId":"",
  //                 "parentSpanId":"",
  //                 "name":"operationB",
  //                 "startTimeUnixNano":"1581452772000000321",
  //                 "endTimeUnixNano":"1581452773000000789",
  //                 "links":[
  //                   {
  //                      "traceId":"",
  //                      "spanId":"",
  //                      "attributes":[{"key":"span-link-attr","value":{"stringValue":"span-link-attr-val"}}],
  //                      "droppedAttributesCount":4
  //                   },
  //                   {"traceId":"","spanId":"","droppedAttributesCount":1}
  //                 ],
  //                 "droppedLinksCount":3,
  //                 "status":{}
  //               }
  //             ]
  //           }
  //         ]
  //       }
  //     ]
  //   }
  //

  def attributeTypeName(attributeType: AttributeType[_]): String = attributeType match {
    case AttributeType.Boolean => "boolValue"
    case AttributeType.Double => "doubleValue"
    case AttributeType.String => "stringValue"
    case AttributeType.Long => "intValue"
    case AttributeType.BooleanList => "arrayValue"
    case AttributeType.DoubleList => "arrayValue"
    case AttributeType.StringList => "arrayValue"
    case AttributeType.LongList => "arrayValue"
  }

  def attributeValue[A](attributeType: AttributeType[A], value: A): Json = attributeType match {
    case AttributeType.Boolean =>
      value.asInstanceOf[Boolean].asJson
    case AttributeType.Double =>
      value.asInstanceOf[Double].asJson
    case AttributeType.String =>
      value.asInstanceOf[String].asJson
    case AttributeType.Long =>
      value.asInstanceOf[Long].asJson
    case AttributeType.BooleanList =>
      val list = value.asInstanceOf[List[Boolean]]
      val typeName = attributeTypeName(AttributeType.Boolean)
      Json.obj(
        "values" -> list.map(bool =>
          Json.obj(typeName -> bool.asJson)
        ).asJson
      )
    case AttributeType.DoubleList =>
      val list = value.asInstanceOf[List[Double]]
      val typeName = attributeTypeName(AttributeType.Double)
      Json.obj(
        "values" -> list.map(double =>
          Json.obj(typeName -> double.asJson)
        ).asJson
      )
    case AttributeType.StringList =>
      val list = value.asInstanceOf[List[String]]
      val typeName = attributeTypeName(AttributeType.String)
      Json.obj(
        "values" -> list.map(string =>
          Json.obj(typeName -> string.asJson)
        ).asJson
      )
    case AttributeType.LongList =>
      val list = value.asInstanceOf[List[Long]]
      val typeName = attributeTypeName(AttributeType.Long)
      Json.obj(
        "values" -> list.map(long =>
          Json.obj(typeName -> long.asJson)
        ).asJson
      )
  }

  def convertAttribute(attribute: Attribute[_]): Json = {
    Json.obj(
      "key" -> attribute.key.name.asJson,
      "value" -> Json.obj(
        attributeTypeName(attribute.key.`type`) -> attributeValue(attribute.key.`type`, attribute.value)
      )
    )
  }

  def convertLink(link: LocalLink): Json = {
    Json.obj(
      "traceId" -> link.spanContext.traceIdHex.asJson,
      "spanId" -> link.spanContext.spanIdHex.asJson,
      "attributes" -> link.attributes.map(convertAttribute).asJson,
      "droppedAttributesCount" -> link.droppedAttributes.asJson
    )
  }

  def convertEvent(event: LocalEvent): Json = {
    Json.obj(
      "name" -> event.name.asJson,
      "timeUnixNano" -> (event.time.toMicros * 1000).toString().asJson,
      "attributes" -> event.attributes.map(convertAttribute).asJson,
      "droppedAttributesCount" -> event.droppedAttributes.asJson
    )
  }
  def convertStatus(status: org.typelevel.otel4s.trace.Status): Json = status match {
    case org.typelevel.otel4s.trace.Status.Unset => 0.asJson
    case org.typelevel.otel4s.trace.Status.Ok => 1.asJson
    case org.typelevel.otel4s.trace.Status.Error => 2.asJson
  }
  def convertKind(kind: SpanKind): Json = kind match{
    case SpanKind.Internal => 1.asJson
    case SpanKind.Server => 2.asJson
    case SpanKind.Client => 3.asJson
    case SpanKind.Producer => 4.asJson
    case SpanKind.Consumer => 5.asJson
  }

  def convertSpan(span: LocalSpan): Json = {
    Json.obj(
      "traceId" -> span.spanContext.traceIdHex.asJson,
      "spanId" -> span.spanContext.spanIdHex.asJson,
      "traceState" -> "".asJson,
      "parentSpanId" -> span.parentSpan.map(_.spanIdHex).asJson,
      "name" -> span.mutable.name.asJson,
      "kind" -> convertKind(span.kind).asJson,
      "startTimeUnixNano" -> (span.mutable.startTime.toMicros * 1000).toString().asJson,
      "endTimeUnixNano" -> (span.mutable.endTime.get.toMicros * 1000).toString().asJson,
      "attributes" -> span.mutable.attributes.map(convertAttribute).asJson,
      "droppedAttributesCount" -> span.mutable.droppedAttributes.asJson,

      "events" -> span.mutable.events.map(convertEvent).asJson,
      "droppedEventsCount" -> span.mutable.droppedEvents.asJson,
      "links" -> span.mutable.links.map(convertLink).asJson,
      "droppedLinksCount" -> span.mutable.droppedLinks.asJson,

      "status" -> Json.obj(
        "message" -> span.mutable.statusDescription.getOrElse("").asJson,
        "code" -> convertStatus(span.mutable.status)
      )
    )
  }

  def processChunk(chunk: Chunk[LocalSpan]): Json = {
    val resourceState = chunk.head.get.resourceState
    Json.obj(
      "resourceSpans" -> Json.arr(
        Json.obj(
          "resource" -> Json.obj(
            "attributes" -> (
              Attribute("service.name", resourceState.serviceName) :: resourceState.resourceAttributes
            ).map(convertAttribute).asJson,
            "droppedAttributesCount" -> 0.asJson
          ),
          "scopeSpans" -> chunk.toVector.groupBy(_.scopeState).map{
            case (scopeState, spans) =>
              Json.obj(
                "scope" -> Json.obj(
                  "name" -> scopeState.instrumentationScopeName.asJson,
                  "version" -> scopeState.version.getOrElse("").asJson,
                  "schemaUrl" -> scopeState.schemaUrl.getOrElse("").asJson,
                  "attributes" -> List.empty.asJson,
                  "droppedAttributesCount" -> 0.asJson,
                ),
                "spans" -> spans.map(convertSpan).asJson
              )
          }.asJson
        )
      )
    ).deepDropNullValues
  }
}