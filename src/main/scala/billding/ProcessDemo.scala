package billding

import zio.ZIO
import zio.process.Command

object ProcessDemo extends zio.ZIOAppDefault {
  val command = Command("cat", "build.sbt")
  val ping = Command("ping", "google.com")

  def run =
    ping.linesStream
      .tap(line => ZIO.debug(line))
      .take(10)
      .runDrain

//    ZIO.debug("hi")


}
