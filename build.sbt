ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.1.1"

scalaVersion := "2.13.7"



lazy val root = (project in file("."))
  .settings(
    name := "ZioProcessDemo",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.0.0-RC2",
      "dev.zio" %% "zio-process" % "0.7.0-RC2+2-9c0d5620-SNAPSHOT"

    ),
    fork := true,
    mainClass :=  Some("billding.TrackBitcoin"),
    resolvers += Resolver.sonatypeRepo("snapshots")

  )
