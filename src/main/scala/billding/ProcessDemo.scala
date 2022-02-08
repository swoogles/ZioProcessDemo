package billding

import zio.{DurationOps, NonEmptyChunk, Schedule, ZIO, ZIOAppDefault}
import zio.process.{Command, ProcessInput, ProcessOutput}
import zio.stream.ZStream

import java.io.File
import java.nio.charset.Charset
import java.time.{Duration, Instant, ZoneId}
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.Locale
import zio.durationInt

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

object WriteToFile extends ZIOAppDefault:
  private val touch = Command("touch", "junk.txt")
  private def append(line: String) = Command("echo", line) >> new java.io.File("junk.txt")

  private val formatter =
    DateTimeFormatter.ofPattern("h:mm a"	)
      .withLocale( Locale.US )
      .withZone( ZoneId.systemDefault() );

  private val appendLogLine =
    for
      timestamp <- zio.Clock.instant
      _ <- append(formatter.format(timestamp)).run
    yield ()

  val createAndRepeatedlyAppendTo =
    for
      _ <- touch.run
      _ <- (ZIO.debug("Appending now.") *> appendLogLine).repeat(Schedule.spaced(2.seconds) && Schedule.recurs(10))
    yield "Finished"

  def run =
    createAndRepeatedlyAppendTo

object ObserveFile extends ZIOAppDefault:
  val touch = Command("tail", "-f", "junk.txt")

  def monitorFile(startTime: Instant) =
    for
      _ <- touch
      .linesStream
      .takeUntilZIO(line =>
        (
          for {
            _ <- ZIO.debug(line)
            _ <- ZIO.debug("Checking")
            curTime <- zio.Clock.instant
            _ <- ZIO.debug(curTime)
          } yield Duration.between(startTime, curTime).compareTo(Duration.ofSeconds(10))  > 0
          )
      )
      .tap(line => ZIO.debug(line))
      .runDrain
    yield ()

  def run =
    for
      startTime <- zio.Clock.instant
      _ <- ZIO.debug(startTime)
      _ <-  monitorFile(startTime).raceEither(WriteToFile.createAndRepeatedlyAppendTo)
    yield "Finished"