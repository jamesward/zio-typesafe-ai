organization := "com.jamesward"

name := "zio-typesafe-ai"

scalaVersion := "3.9.0"

scalacOptions ++= Seq(
  // "-Yexplicit-nulls", // not sure where it went
  "-language:strictEquality",
  "-deprecation",
  "-release", "17",
  // "-Xfatal-warnings", // not sure where it went
)

val zioVersion = "2.1.26"

val zioSchemaVersion = "1.9.0"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                    % zioVersion,
  "dev.zio" %% "zio-direct"             % "1.0.0-RC7",
  "dev.zio" %% "zio-http"               % "3.11.6",
  "dev.zio" %% "zio-schema-derivation"  % zioSchemaVersion,
  "dev.zio" %% "zio-schema-json"        % zioSchemaVersion,
  "dev.zio" %% "zio-json"               % "1.1.0",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,
)

fork := true

javaOptions ++= Seq(
  "-Djava.net.preferIPv4Stack=true",
  // JDK 25: suppress sun.misc.Unsafe / restricted-method warnings
  // emitted by upstream libs (scala-library, netty-common).
  "--enable-native-access=ALL-UNNAMED",
  "--sun-misc-unsafe-memory-access=allow",
)

// sbt 2 maps plain `test` to incremental testQuick; CI and local release
// validation should execute every suite, including live tests gated by env vars.
Test / test := (Test / testFull).value

licenses := Seq("MIT License" -> uri("https://opensource.org/licenses/MIT"))

homepage := Some(uri("https://github.com/jamesward/zio-typesafe-ai"))

developers := List(
  Developer(
    "jamesward",
    "James Ward",
    "james@jamesward.com",
    uri("https://jamesward.com")
  )
)

ThisBuild / versionScheme := Some("semver-spec")

// sbt-mcp (loopback-only: its tools can execute build tasks)
Global / mcpEnabled := true
Global / mcpHost := "127.0.0.1"
Global / mcpPort := 5122

// SkillsJars: extract agent Skills with `./sbt extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.10" % Skills
