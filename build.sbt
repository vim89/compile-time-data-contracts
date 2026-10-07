// ===== GLOBAL BUILD SETTINGS =====

val scala213 = "2.13.16"
val scala3 = "3.3.6"
val sparkVersion = "3.5.6"
// Only the `spark4` probe project uses this. The shipped artifact is built against `sparkVersion` alone.
val spark4Version = "4.2.0"
// The version Spark 3.5.6 already resolves, so the probe parses the corpus with the same Avro the runtime has.
val avroVersion = "1.11.4"
val munitVersion = "1.1.1"
val magnoliaVersion = "1.1.10"

ThisBuild / organization := "com.vitthalmirji"
ThisBuild / version           := "0.1.0"
ThisBuild / scalaVersion      := scala3

// `-Xmax-inlines` is a Scala 3 option, so the shared list stays version-agnostic and the raise goes in
// `core` where the macro actually inlines.
val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wconf:msg=unused:info"
)

// See https://github.com/apache/spark/blob/v3.5.6/launcher/src/main/java/org/apache/spark/launcher/JavaModuleOptions.java
val unnamedJavaOptions = List(
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED",
  "-Djdk.reflect.useDirectMethodHandle=false",
  "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
)

ThisBuild / Test / parallelExecution := false

ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/vim89/compile-time-data-contracts"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/vim89/compile-time-data-contracts"),
    "scm:git:https://github.com/vim89/compile-time-data-contracts.git"
  )
)
ThisBuild / developers := List(
  Developer("vim89", "Vitthal Mirji", "vitthalmirji@gmail.com", url("https://vitthalmirji.com"))
)

// ===== PUBLISHING =====
// GitHub Packages needs no plugin and no signing key, so it is the cheapest way to make this engine
// resolvable by other projects. Maven Central can be added later; the POM metadata above is already what
// Central asks for.
//
// The version above is a release and not a snapshot, because a consumer pinned to a snapshot can change
// behaviour without a commit of its own. GitHub Packages will not overwrite a published release, so
// publishing is driven by a tag rather than by every push to main.
ThisBuild / publishMavenStyle := true
ThisBuild / publishTo := Some(
  "GitHub Packages" at "https://maven.pkg.github.com/vim89/compile-time-data-contracts"
)

// GitHub Packages authenticates reads as well as writes, so consumers need these too. Absent in a plain
// local build, where `publishLocal` and `~/.ivy2/local` are used instead.
ThisBuild / credentials ++= (for {
  user  <- sys.env.get("GITHUB_ACTOR")
  token <- sys.env.get("GITHUB_TOKEN")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

// A macro is spelled differently on each Scala version, so the per-version sources live in their own
// directories and only the version being compiled is on the source path.
val perVersionSources = Seq(
  Compile / unmanagedSourceDirectories ++= versionedDirs((Compile / sourceDirectory).value, scalaVersion.value),
  Test / unmanagedSourceDirectories ++= versionedDirs((Test / sourceDirectory).value, scalaVersion.value)
)

def versionedDirs(base: File, scalaVersion: String): Seq[File] =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, _)) => Seq(base / "scala-2")
    case Some((3, _)) => Seq(base / "scala-3")
    case _            => Nil
  }

lazy val core = (project in file("modules/core"))
  .settings(perVersionSources: _*)
  .settings(
    name := "ctdc-core",
    description := "Compile-time structural data contracts",
    crossScalaVersions := Seq(scala213, scala3),
    scalacOptions ++= commonScalacOptions ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      // Add "-Xprint:postInlining" here only when debugging macro expansion / inlining.
      case Some((3, _)) => Seq("-Xmax-inlines:100000")
      case _            => Nil
    }),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test
    ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      // Scala 2 has no `Mirror`, so shape derivation goes through Magnolia and the macro needs scala-reflect.
      case Some((2, _)) =>
        Seq(
          "com.softwaremill.magnolia1_2" %% "magnolia" % magnoliaVersion,
          "org.scala-lang" % "scala-reflect" % scalaVersion.value,
          // Scala 2 has no `scala.compiletime.testing`, so the fixtures that assert a *rejection* compile their
          // snippet through a toolbox instead. Test-only: nothing shipped invokes the compiler.
          "org.scala-lang" % "scala-compiler" % scalaVersion.value % Test
        )
      case _ => Nil
    }),
    Test / fork := true
  )

lazy val spark = (project in file("modules/spark"))
  .dependsOn(core)
  .settings(
    name := "ctdc-spark",
    description := "Spark runtime schema pin for ctdc contracts",
    // Scala 3 only. The runtime pin is written in Scala 3 and callers that need the compile-time engine on
    // 2.13 depend on `core` alone.
    crossScalaVersions := Seq(scala3),
    scalacOptions ++= commonScalacOptions :+ "-Xmax-inlines:100000",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % sparkVersion,
      "org.apache.spark" %% "spark-sql" % sparkVersion
    ).map(_.cross(CrossVersion.for3Use2_13)) ++ Seq(
      "org.scalameta" %% "munit" % munitVersion % Test,
      // Only `ctdc.probe.CorpusRelevance` uses this, to parse the paper's `.avsc` corpus with the reference parser
      // instead of a hand-rolled one. Declared rather than taken transitively from spark-core, which is where it
      // would otherwise come from, so that the probe's dependency is visible. `Provided`: no shipped code needs it.
      "org.apache.avro" % "avro" % avroVersion % Provided,
      // `ctdc.probe.ComparatorMatrix` converts each stimulus pair with Spark's own `SchemaConverters` before handing
      // it to Avro's resolution checker, so the baseline runs through the version-pinned converter rather than a
      // hand-rolled one. `Provided` for the same reason as avro: no shipped code needs it.
      "org.apache.spark" %% "spark-avro" % sparkVersion % Provided cross CrossVersion.for3Use2_13
    ),
    // Ensure the app runs in a separate JVM (so sbt memory != app memory)
    fork := true,
    Test / fork := true,
    javaOptions ++= unnamedJavaOptions,
    // Spark starts a driver in the test JVM and binds it to the machine's resolved hostname, which fails on
    // a laptop whose hostname does not resolve to a local address. Pin it to loopback so `sbt test` works
    // without the reviewer having to export anything first.
    Test / envVars += "SPARK_LOCAL_IP" -> "127.0.0.1",
    // include the 'provided' Spark dependency on the classpath for `sbt run`
    Compile / run := Defaults.runTask(Compile / fullClasspath, Compile / run / mainClass, Compile / run / runner).evaluated,
    // And for `runMain`, which is how every probe in the paper is invoked. Without this the probes see avro and
    // spark-avro at compile time and not at run time, and a predicate built on them reports a thrown verdict for
    // every row rather than failing loudly.
    Compile / runMain := Defaults.runMainTask(Compile / fullClasspath, Compile / run / runner).evaluated,
    // A forked run starts in the subproject directory, so an output path given on the command line would land under
    // modules/spark. The paper's evidence files are addressed from the repo root, so that is where a run starts.
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value
  )

// The same probe sources compiled and run against a Spark 4 build, so that the paper's extension of the
// characterisation to the 4.x line is a measurement and not only an argument from source identity. Nothing is
// published from here and no test lives here: this project exists to produce one CSV that can be diffed against
// the 3.5.6 one. It is not aggregated by `root`, so an ordinary `sbt test` does not resolve a second Spark.
lazy val spark4 = (project in file("modules/spark4"))
  .dependsOn(core)
  .settings(
    name := "ctdc-spark4-probe",
    description := "The comparator matrix, re-measured against a Spark 4 build",
    publish / skip := true,
    crossScalaVersions := Seq(scala3),
    scalacOptions ++= commonScalacOptions :+ "-Xmax-inlines:100000",
    // The sources are the spark module's, not copies: a divergence between what the paper measured at 3.5.6 and
    // what it measured at 4.x must come from Spark and never from a second copy of the harness drifting.
    Compile / unmanagedSourceDirectories := Seq((spark / Compile / scalaSource).value),
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % spark4Version,
      "org.apache.spark" %% "spark-sql" % spark4Version,
      "org.apache.spark" %% "spark-avro" % spark4Version
    ).map(_.cross(CrossVersion.for3Use2_13)) ++ Seq(
      "org.apache.avro" % "avro" % avroVersion
    ),
    fork := true,
    javaOptions ++= unnamedJavaOptions,
    Compile / run := Defaults.runTask(Compile / fullClasspath, Compile / run / mainClass, Compile / run / runner).evaluated,
    Compile / runMain := Defaults.runMainTask(Compile / fullClasspath, Compile / run / runner).evaluated,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value
  )

lazy val root = (project in file("."))
  .aggregate(core, spark)
  .settings(
    name := "compile-time-data-contracts",
    publish / skip := true
  )

// ===== SBT ALIASES =====
addCommandAlias("compileAll", ";compile; test:compile")
addCommandAlias(
  "debugPostInlining",
  """;set ThisBuild / scalacOptions += "-Xprint:postInlining"; compile"""
)
