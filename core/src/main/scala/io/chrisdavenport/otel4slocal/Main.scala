package io.chrisdavenport.otel4slocal

import cats.effect._
import org.typelevel.vault.Vault
import cats.mtl.Local

import org.typelevel.otel4s.{Otel4s, TextMapPropagator}
import io.chrisdavenport.otel4slocal.trace.LocalSpan
import scala.concurrent.duration._
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.Attribute

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    cats.effect.std.Random.scalaUtilRandom[IO].flatMap{ implicit R: cats.effect.std.Random[IO] =>
      ExternalHelpers.localVault[IO].flatMap{local =>
        LocalOtel4s.build(local, {(s: fs2.Stream[IO, trace.LocalSpan]) => s.evalMap{ls => IO.println(ls)}.compile.drain}).use(otel4s =>
          otel4s.tracerProvider.get("ExampleApp").flatMap{tracer =>
            tracer.spanBuilder("Test").build.use{ span =>
              span.addAttribute(Attribute("test.attribute", "huzzah")) >>
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

import cats.syntax.all._
import cats.effect._
import cats.mtl.Local
import cats._
import org.typelevel.vault.Vault

object ExternalHelpers {

  def localVault[F[_]: LiftIO: MonadCancelThrow]: F[Local[F, Vault]] = {
    LiftIO[F].liftIO(IOLocal(Vault.empty)).map(localForIoLocal(_))
  }

  def localForIoLocal[F[_]: MonadCancelThrow: LiftIO, E](
      ioLocal: IOLocal[E]
  ): Local[F, E] =
    new Local[F, E] {
      def applicative =
        Applicative[F]
      def ask[E2 >: E] =
        Functor[F].widen[E, E2](ioLocal.get.to[F])
      def local[A](fa: F[A])(f: E => E): F[A] =
        MonadCancelThrow[F].bracket(ioLocal.modify(e => (f(e), e)).to[F])(_ =>
          fa
        )(ioLocal.set(_).to[F])
    }


}