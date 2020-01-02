ThisBuild / scalaVersion := "2.12.10"
ThisBuild / organization := "org.rise-lang"

lazy val arithExpr = (project in file("."))
  .settings(
    name          := "ArithExpr",
    version       := "1.0",

    scalacOptions ++= Seq("-Xmax-classfile-name", "100", "-unchecked", "-deprecation", "-feature"),
    scalacOptions in (Compile, doc) := Seq("-implicits", "-diagrams"),

    // dependencies specified in project/Dependencies.scala
    libraryDependencies ++= Seq(
        // scala
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        "org.scala-lang" % "scala-compiler" % scalaVersion.value,
        "org.scala-lang" % "scala-library" % scalaVersion.value,
        // testing
        "junit" % "junit" % "4.11",
        "com.novocode" % "junit-interface" % "0.11" % "test",
        "org.scalacheck" %% "scalacheck" % "1.14.1" % "test",
        // XML
        "org.scala-lang.modules" %% "scala-xml" % "1.2.0"
    ),

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
    testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "5"),

    fork := true
  )
