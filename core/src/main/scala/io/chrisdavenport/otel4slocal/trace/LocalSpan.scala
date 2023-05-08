package io.chrisdavenport.otel4slocal.trace

import cats._
import cats.syntax.all._
import cats.effect._
import scodec.bits.ByteVector
import org.typelevel.otel4s.Attribute
import scala.concurrent.duration._
import org.typelevel.otel4s.trace.SamplingDecision
import org.typelevel.otel4s.trace.SpanContext
import cats.effect.std.Random
import org.typelevel.otel4s.trace.SpanBuilder
import org.typelevel.otel4s.trace.SpanKind

// Need secondary scope as the reference to the type of the current next span to use and build.

case class LocalScope(
  name: String,
  version: Option[String],
  attributes: List[Attribute[_]],
  droppedAttributes: Int,
)

case class LocalEvent(
  time: FiniteDuration,
  name: String,
  attributes: List[Attribute[_]],
  droppedAttributes: Int
)

case class LocalLink(
  spanContext: SpanContext,
  traceState: String,
  attributes: List[Attribute[_]],
  droppedAttributes: Int,
)
/*
Spans encapsulate:
    The span name
    An immutable SpanContext that uniquely identifies the Span
    A parent span in the form of a Span, SpanContext, or null
    A SpanKind
    A start timestamp
    An end timestamp
    Attributes
    A list of Links to other Spans
    A list of timestamped Events
    A Status.
*/
// TODO add references for TracerName, version, url
case class LocalSpan(
  spanContext: SpanContext, // Needs equality concept
  parentSpan: Option[SpanContext], // Zero Element if Root when reporting to externals - otel wants this
  kind: SpanKind, // Need typed model for kind, this is editable?
  mutable: LocalSpan.MutableState
)

object LocalSpan {

  def createSpanContext[F[_]: Random: Monad](
    traceIdOpt: Option[ByteVector],
    sampling: Boolean
  ): F[SpanContext] = {
    for {
      traceIdBV <- traceIdOpt.fold(Random[F].nextBytes(16).map(ByteVector(_)))(_.pure[F])
      spanIdBV <- Random[F].nextBytes(8).map(ByteVector(_))
    } yield create(traceIdBV, spanIdBV, false, sampling)
  }


  def create(traceIdBV: ByteVector, spanIdBV: ByteVector, remote: Boolean, sampling: Boolean): SpanContext =
    new SpanContext {
      def isRemote: Boolean = remote
      def isValid: Boolean = true
      def samplingDecision: SamplingDecision = SamplingDecision.fromBoolean(sampling)
      def spanId: scodec.bits.ByteVector = spanIdBV
      def spanIdHex: String = spanId.toHex
      def traceId: scodec.bits.ByteVector = traceIdBV
      def traceIdHex: String = traceId.toHex
    }


  case class MutableState(
    name: String,
    startTime: FiniteDuration, // Optional to create this at runtime?
    endTime: Option[FiniteDuration], // When this is set we are done and report it.
    attributes: List[Attribute[_]],
    droppedAttributes: Int,
    events: List[LocalEvent],
    droppedEvents: Int,
    links: List[LocalLink],
    droppedLinks: Int,

    status: org.typelevel.otel4s.trace.Status,
    statusDescription: Option[String],
  )


  def eqSpanContext(a: SpanContext, b: SpanContext): Boolean = {
    a.spanId === b.spanId &&
    a.traceId === b.traceId &&
    a.isRemote === b.isRemote &&
    a.isValid === b.isValid &&
    a.samplingDecision.isSampled === b.samplingDecision.isSampled
  }

}