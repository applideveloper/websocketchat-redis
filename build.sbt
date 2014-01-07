name :="websocket-chat"

version := "1.0"

libraryDependencies ++= Seq(
  cache,
  "net.debasishg" % "redisclient_2.10" % "2.11"
)

play.Project.playScalaSettings
