addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.4.19")
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % "0.4.19")
addSbtPlugin("org.typelevel" % "sbt-typelevel-settings" % "0.4.19")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.13.1")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.2.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.12")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.2.0")
addSbtPlugin("com.armanbilge" % "sbt-scala-native-config-brew-github-actions" % "0.1.2")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.3")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.1"
addSbtPlugin("io.chrisdavenport" % "sbt-http4s-grpc" % "0.0.3")
