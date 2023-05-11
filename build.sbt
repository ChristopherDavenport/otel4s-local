ThisBuild / tlBaseVersion := "0.0" // your current series x.y

ThisBuild / organization := "io.chrisdavenport"
ThisBuild / organizationName := "Christopher Davenport"
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("christopherdavenport", "Christopher Davenport")
)
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / tlSonatypeUseLegacyHost := true


val Scala3 = "3.2.2"

ThisBuild / crossScalaVersions := Seq("2.13.8", Scala3)
ThisBuild / scalaVersion := Scala3

ThisBuild / testFrameworks += new TestFramework("munit.Framework")

val catsV = "2.9.0"
val catsEffectV = "3.4.8"
val fs2V = "3.6.1"
val http4sV = "0.23.18"
val circeV = "0.14.5"
val doobieV = "1.0.0-RC2"
val munitCatsEffectV = "2.0.0-M3"


// Projects
lazy val `otel4s-local` = tlCrossRootProject
  .aggregate(core, otlp, examples)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .enablePlugins(NoPublishPlugin)
  .in(file("core"))
  .settings(
    name := "otel4s-local",
    libraryDependencies += compilerPlugin("org.polyvariant" % "better-tostring" % "0.3.17" cross CrossVersion.full),

    libraryDependencies ++= Seq(
      "org.typelevel"               %%% "cats-core"                  % catsV,
      "org.typelevel"               %%% "cats-effect"                % catsEffectV,

      "co.fs2"                      %%% "fs2-core"                   % fs2V,
      "co.fs2"                      %%% "fs2-io"                     % fs2V,

      "org.typelevel" %%% "vault" % "3.5.0",
      "org.typelevel" %%% "cats-mtl" % "1.3.1",
      "org.typelevel" %%% "otel4s-core" % "0.2.1",
      "org.typelevel"               %%% "munit-cats-effect"        % munitCatsEffectV         % Test,

    )
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  ).nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
  .platformsSettings(NativePlatform)(
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "epollcat" % "0.1.4" % Test
    ),
    Test / nativeBrewFormulas ++= Set("s2n", "utf8proc"),
    Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1")
  )

lazy val otlp = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("otlp"))
  .enablePlugins(Http4sGrpcPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(core)
  .settings(
    name := "otel4s-local-otel",
    Compile / PB.protoSources += baseDirectory.value.getParentFile / "src" / "main" / "protobuf",

    libraryDependencies ++= Seq(
      "io.opentelemetry.proto" % "opentelemetry-proto" % "0.19.0-alpha" % "protobuf-src" intransitive (),
      "org.typelevel"               %%% "munit-cats-effect"        % munitCatsEffectV         % Test,

    ),
    Compile / PB.targets ++= Seq(
      scalapb.gen(grpc = false) -> (Compile / sourceManaged).value / "scalapb"
    )
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  )


lazy val examples = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .enablePlugins(NoPublishPlugin)
  .in(file("examples"))
  .dependsOn(core, otlp)
  .settings(
    name := "otel4s-local-examples",
    libraryDependencies ++= Seq(
      "io.chrisdavenport" %%% "crossplatformioapp" % "0.1.0",
    )
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
    scalaJSUseMainModuleInitializer := true,
  )
  // .nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
  // .platformsSettings(NativePlatform)(
  //   Test / nativeBrewFormulas ++= Set("s2n", "utf8proc"),
  //   Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1")
  // )

lazy val site = project.in(file("site"))
  .enablePlugins(TypelevelSitePlugin)
  .dependsOn(core.jvm)
