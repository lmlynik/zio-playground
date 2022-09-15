ThisBuild / scalaVersion := "3.2.0"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "pl.mlynik"

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")

lazy val root = (project in file("."))
  .aggregate(crawler, serverNodes)

lazy val crawler = (project in file("zio-crawler"))
  .settings(
    name := "zio-crawler"
  )
  .settings(commonSettings)

lazy val serverNodes = (project in file("zio-server-nodes"))
  .settings(
    name := "zio-server-nodes"
  )
  .settings(commonSettings)

lazy val commonSettings = Def.settings(
  resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
  testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")),
  libraryDependencies ++= Seq(
    "dev.zio"                       %% "zio"                           % "2.0.2",
    "dev.zio"                       %% "zio-concurrent"                % "2.0.2",
    "dev.zio"                       %% "zio-streams"                   % "2.0.2",
    "dev.zio"                       %% "zio-logging"                   % "2.1.0",
    "com.softwaremill.sttp.client3" %% "zio"                           % "3.7.6",
    "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % "3.7.6",
    "org.jsoup"                      % "jsoup"                         % "1.15.3",
    "dev.zio"                       %% "zio-test"                      % "2.0.2" % Test
  ),
  Test / fork    := true,
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-language:higherKinds",
    "-language:existentials",
    "-unchecked",
    "-Xfatal-warnings",
    "-language:postfixOps"
  )
)
