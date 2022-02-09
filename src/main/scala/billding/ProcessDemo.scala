package billding

import zio.{DurationOps, NonEmptyChunk, Schedule, ZIO, ZIOAppDefault, durationInt, process}
import zio.process.{Command, ProcessInput, ProcessOutput}
import zio.stream.ZStream

import java.io.File
import java.nio.charset.Charset
import java.time.{Duration, Instant, ZoneId}
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.Locale

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

def repeatForATime(startTime: Instant, debug: Boolean = false) = (_: Any) =>
  for {
    curTime <- zio.Clock.instant
    timeSpentRepeating = Duration.between(startTime, curTime)
    timeLeft = Duration.ofSeconds(10).minus(timeSpentRepeating)
    shouldStop = timeLeft.isNegative
    _ <-
      if (debug)
        if (shouldStop)
          ZIO.debug("Done polling.")
        else
          ZIO.debug("Will poll for " + timeLeft.toSeconds + " more seconds.")
      else ZIO.unit
  } yield  shouldStop


object ObserveFile extends ZIOAppDefault:
  val touch = Command("tail", "-f", "junk.txt")

  def monitorFile(startTime: Instant) =
    for
      _ <- touch
      .linesStream
      .takeUntilZIO(repeatForATime(startTime))
      .tap(line => ZIO.debug(line))
      .runDrain
    yield ()

  def run =
    for
      startTime <- zio.Clock.instant
      _ <- ZIO.debug(startTime)
      _ <-  monitorFile(startTime).raceEither(WriteToFile.createAndRepeatedlyAppendTo)
    yield "Finished"

object HtopDemo extends ZIOAppDefault:
  val top = Command("top")

  //
  case class TopLine(applicationName: String, cpuPercentage: Float, mem: String)
  object TopLine:
    def apply(raw: String): TopLine =
      val truncated = raw.take(70)
      val applicationName = truncated.slice(7, 19).trim
      val cpuValue = truncated.slice(23,27).toFloat
      val mem = truncated.slice(58, 63).trim
      TopLine(applicationName, cpuValue, mem)

  val rawInput =
    "73648* top              0.0"
    "69815* top              0.0"
    "352    WindowServer 22.8      26:15:27 28       5    2820   4944M-" // terminal
    "73648* top              0.0  00:00.28 2/1      0   22     4773K" // intellij
    "55970  BetterTouchT 0.7"

  def run =
    for
      startTime <- zio.Clock.instant
      _ <- top
        .linesStream
        .drop(1)
        .filter(line => Range(0, 9).exists(num => line.startsWith(num.toString)) && line.contains("*"))
        .map(TopLine(_))
        .filter(_.cpuPercentage > 3.0)
        .takeUntilZIO(repeatForATime(startTime))
        .tap(line => ZIO.debug(line))
        .runDrain
    yield  ()

object GourceDemo extends ZIOAppDefault:
  val gource = Command("gource")
  val ps = Command("ps", "aux").stream
  val gourceProcess = Command("grep", "gource").stdin(ProcessInput.fromStream(ps))
  def kill(pid: Int) = Command("kill", pid.toString)
  def run =
    for
      startTime <- zio.Clock.instant
      gourceProcess: process.Process <- gource.copy(workingDirectory = Some(new File("/Users/bfrasure/Repositories/zio-ecosystem"))).run
      _ <- ZIO.sleep(15.seconds)
      _ <- gourceProcess.killForcibly
//      _ <- ZIO.debug(gp)
//      _ <- gourceFork.interrupt
    yield ()
