package billding

import zio.{DurationOps, NonEmptyChunk, Schedule, ZIO}
import zio.process.{Command, ProcessInput, ProcessOutput}
import zio.stream.ZStream

import java.io.File
import java.nio.charset.Charset

object ProcessDemo extends zio.ZIOAppDefault {
  val command = Command("cat", "build.sbt")
  val ping = Command("ping", "google.com")
  val sbt = Command("sbt")

  def run =
    ping.linesStream
      .tap(line => ZIO.debug(line))
      .take(10)
      .runDrain

//    ZIO.debug("hi")


}

object SbtDemo extends zio.ZIOAppDefault {
  import zio.durationInt
  import zio.durationInt

  def sbtCommand(args: String*): Command.Standard =
    Command.Standard(
      NonEmptyChunk("sbt", args: _*),
      Map.empty,
      Some(new File("/Users/bfrasure/Repositories/zio-ecosystem")),
//      Option.empty[File],
      ProcessInput.inherit,
      ProcessOutput.Pipe,
      ProcessOutput.Pipe,
      redirectErrorStream = false
    )


  def run =
    for {
      fiber <- sbtCommand()
        .stdin(ProcessInput.fromString("compile", Charset.defaultCharset))
//        .stdin(ProcessInput.fromStream(ZStream.repeatWithSchedule("compile".getBytes,  Schedule.spaced(20.seconds))))
        .linesStream
        .tap(line => ZIO.debug(line))
        .runDrain
        .exitCode.forkDaemon
      _     <- ZIO.sleep(20.seconds)
      _     <- fiber.interrupt.catchAllDefect(_ => ZIO.debug("Interrupted sbt"))
      _     <- fiber.join
    } yield ()
}

object BrewDemo extends zio.ZIOAppDefault:
  val install = Command("brew", "install nonexistentApp")

  def run =
    install
//      .stderr
      .linesStream
      .tap(line => ZIO.debug(line))
      .runDrain