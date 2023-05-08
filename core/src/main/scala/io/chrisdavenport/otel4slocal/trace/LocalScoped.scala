package io.chrisdavenport.otel4slocal.trace

import cats.effect.SyncIO
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.vault.Vault

sealed trait LocalScoped
object LocalScoped {
  case object Root extends LocalScoped
  case object Noop extends LocalScoped
  case class Spanned(spanContext: SpanContext) extends LocalScoped

  private val key = org.typelevel.vault.Key.newKey[SyncIO, LocalScoped].unsafeRunSync()

  def extractFromVault(vault: Vault): LocalScoped = vault.lookup(key).getOrElse(Root)

  def insertIntoVault(vault: Vault, scoped: LocalScoped): Vault =
    vault.insert(key, scoped)
}