
name := "handbookMS"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.1.8",
  "com.typesafe.akka" %% "akka-stream" % "2.5.22",
  "com.typesafe.akka" %% "akka-actor" % "2.5.22",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.8",
  "org.mongodb.scala" %% "mongo-scala-driver" % "2.6.0",
  "io.spray" %%  "spray-json" % "1.3.5",
  "itc" %% "global-data" % "0.0.1-SNAPSHOT",
  "itc" %% "wapnee-data-model" % "0.0.3-SNAPSHOT"
)