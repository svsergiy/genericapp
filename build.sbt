ThisBuild / version := "0.1.1"
ThisBuild / scalaVersion := "2.13.8"
ThisBuild / organization := "com.svsergiy"

ThisBuild / assemblyMergeStrategy := {
  case x if Assembly.isConfigFile(x) =>
    MergeStrategy.concat
  case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
    MergeStrategy.rename
  case PathList("META-INF", xs @ _*) =>
    xs.map(_.toLowerCase) match {
      case "manifest.mf" :: Nil | "index.list" :: Nil | "dependencies" :: Nil =>
        MergeStrategy.discard
      case ps @ x :: xs if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
        MergeStrategy.discard
      case "maven" :: xs =>
        MergeStrategy.discard
      case "versions" :: xs =>
        MergeStrategy.discard
      case "plexus" :: xs =>
        MergeStrategy.discard
      case "services" :: xs =>
        MergeStrategy.filterDistinctLines
      case "spring.schemas" :: Nil | "spring.handlers" :: Nil =>
        MergeStrategy.filterDistinctLines
      case _ => MergeStrategy.deduplicate
    }
  case _ => MergeStrategy.deduplicate
}

lazy val root = (project in file("."))
  .settings(
    name := "genericapp",
    assemblyPackageScala / assembleArtifact := false,
    assemblyPackageDependency / assembleArtifact := false
  )

val akkaVersion = "2.6.19"
val akkaHttpVersion = "10.2.9"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "2.8.0",
  "ch.qos.logback" % "logback-classic" % "1.2.11",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.slick" %% "slick" % "3.3.3",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3",
  "com.microsoft.sqlserver" % "mssql-jdbc" % "7.2.2.jre8",
  "org.scalameta" %% "munit" % "0.7.29" % Test
)
