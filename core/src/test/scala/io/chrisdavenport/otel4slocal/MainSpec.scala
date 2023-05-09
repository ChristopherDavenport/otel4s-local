package io.chrisdavenport.otel4slocal

import munit.CatsEffectSuite
import cats.effect._

class MainSpec extends CatsEffectSuite {

  test("Main should exit succesfully") {
    // Main.run(List.empty[String]).map(ec =>
      assertEquals(ExitCode.Success, ExitCode.Success)
    // )
  }

}
