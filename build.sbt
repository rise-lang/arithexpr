lazy val arithExpr = (project in file("."))
  .settings(
    name          := "ArithExpr",
    version       := "1.0",
    scalaVersion  := "3.1.2",
    organization  := "org.rise-lang",

    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    Compile / doc / scalacOptions := Seq("-implicits", "-diagrams"),

    // dependencies specified in project/Dependencies.scala
    libraryDependencies ++= Seq(
      // testing
      "junit" % "junit" % "4.13.2",
      "com.github.sbt" % "junit-interface" % "0.13.2" % "test"
    ),

    libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.16.0",

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
    testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "5"),

    fork := true
  )
