package io.chrisdavenport.otel4slocal

import cats.effect._
import org.typelevel.vault.Vault
import cats.mtl.Local

import org.typelevel.otel4s.{Otel4s, TextMapPropagator}

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    IO(println("I am a new project!")).as(ExitCode.Success)
  }



  class Insanity[F[_]](local: Local[F, Vault]){

  }

}