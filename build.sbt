lazy val arithExpr = (project in file("."))
  .settings(
    name          := "ArithExpr",
    version       := "1.0",
    scalaVersion := "3.0.0-RC3",
    organization := "org.rise-lang",

    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    Compile / doc / scalacOptions := Seq("-implicits", "-diagrams"),

    // dependencies specified in project/Dependencies.scala
    libraryDependencies ++= Seq(
      // testing
      "junit" % "junit" % "4.11",
      "com.novocode" % "junit-interface" % "0.11" % "test"
    ),

    libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.15.4",

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
    testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "5"),

    fork := true
  )
