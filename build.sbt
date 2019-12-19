ThisBuild / scalaVersion := "2.11.12"
ThisBuild / organization := "org.rise-lang"

lazy val arithExpr = (project in file("."))
  .settings(
    name          := "ArithExpr",
    version       := "1.0",

    scalacOptions ++= Seq("-Xmax-classfile-name", "100", "-unchecked", "-deprecation", "-feature"),
    scalacOptions in (Compile, doc) := Seq("-implicits", "-diagrams"),

    // Source locations (defaults would be: src/main/scala and test/main/java)
    scalaSource in Compile := baseDirectory(_ / "src/main").value,
    scalaSource in Test := baseDirectory(_ / "src/test").value,
    javaSource in Compile := baseDirectory(_ / "src/main").value,
    javaSource in Test := baseDirectory(_ / "src/test").value,

    // dependencies specified in project/Dependencies.scala
    libraryDependencies ++= Seq(
        // scala
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        "org.scala-lang" % "scala-compiler" % scalaVersion.value,
        "org.scala-lang" % "scala-library" % scalaVersion.value,
        // testing
        "junit" % "junit" % "4.11",
        "com.novocode" % "junit-interface" % "0.11" % "test",
        "org.scalacheck" %% "scalacheck" % "1.13.0" % "test",
        // XML
        "org.scala-lang.modules" % "scala-xml_2.11" % "1.0.4",
    ),

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
    testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "5"),

    fork := true
  )
