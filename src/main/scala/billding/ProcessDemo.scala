package billding

import zio.{Chunk, DurationOps, NonEmptyChunk, Ref, Schedule, ZIO, ZIOAppDefault, ZQueue, durationInt, process}
import zio.process.{Command, ProcessInput, ProcessOutput}
import zio.stream.ZStream

import java.io.File
import java.nio.charset.{Charset, StandardCharsets}
import java.time.{Duration, Instant, ZoneId}
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.Locale

object InteractiveProcess extends zio.ZIOAppDefault:
//  val command = Command("cat").stdin(ProcessInput.fromStream(ZStream.fromChunk(NonEmptyChunk("Hello", "world!").map(_.toByte))))
  def run =
    for {
      commandQueue <- ZQueue.unbounded[Chunk[Byte]]
//      commandQueue <- ZQueue.unbounded[Byte]
//      process      <- Command("python3", "-qi").stdin(ProcessInput.fromQueue(commandQueue)).run

      process <- Command("cat").stdin(ProcessInput.fromQueue(commandQueue)).run
      //      process <- Command("cat").stdin(ProcessInput.fromString("hi\n", StandardCharsets.UTF_8)).run
//      _            <- process.stdout.linesStream.foreach { response =>
//        ZIO.debug(s"Response from REPL: $response")
//      }.forkDaemon
      _ <- commandQueue.offer(Chunk.fromArray("1+1\n".getBytes(StandardCharsets.UTF_8)))
      _ <- commandQueue.offer(Chunk.fromArray("x\n".getBytes(StandardCharsets.UTF_8)))
      _ <- commandQueue.offer(Chunk.fromArray("y\n".getBytes(StandardCharsets.UTF_8)))
      _ <- ZIO.sleep(2.second)
    } yield ()

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
      ProcessInput.Inherit,
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
  val install = Command("brew", "install", "nonexistentApp")

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
//        .repeat(Schedule.elapsed.whileOutput(_ < 4.seconds))
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
        .filter(_.cpuPercentage > 10.0)
        .take(1)
//        .takeUntilZIO(repeatForATime(startTime))
        .tap(line => ZIO.debug(line))
        .foreach(line => say(line.applicationName + "is using a lot of CPU").run)
//        .runDrain
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
      _ <- ZIO.sleep(35.seconds)
      _ <- gourceProcess.killForcibly
//      _ <- ZIO.debug(gp)
//      _ <- gourceFork.interrupt
    yield ()

def say(message: String) =
  Command("say", message)


object Shpotify extends ZIOAppDefault:
  val playYouSuffer =
    Command("spotify", "play", "uri", "spotify:track:5oD2Z1OOx1Tmcu2mc9sLY2")

  val stop =
    Command("spotify", "stop")

  val audioNotification  =
    (for
      _ <- playYouSuffer.run
      _ <- ZIO.sleep(2.seconds)
    yield ()).ensuring(ZIO.debug("Ensuring" ) *> stop.run.orDie) // TODO Better guarantee when process is killed?

  def run =
    audioNotification

object Ticker extends ZIOAppDefault:
  val ticker = Command("ticker", "-w", "AREN,ZSAN")

  def run =
    for
      startTime <- zio.Clock.instant
      _ <-
        ticker.linesStream
          .takeUntilZIO(repeatForATime(startTime))
          .tap(line => ZIO.debug(line))
          .runDrain
      _ <- ZIO.sleep(1.seconds)
    yield ()


case class BitcoinPrice(value: Double)

object TrackBitcoin extends ZIOAppDefault:
  def alert(price: Double, msg: String, toggle: Boolean) =
    ZIO.unit
//      *> ZIO.debug("Bitcoin price: " + price)
      *> ZIO.debug(msg)
      *> (if (toggle) Shpotify.audioNotification  else ZIO.unit)

  def run =
    val cutOff = 65
    for
      price <- Ref.make(BitcoinPrice(0.0))
      _ <-
        ZStream.repeatZIO(ZIO.sleep(3.second) *> ZIO.succeed(BitcoinPrice(Math.random * 100)))
          .mapZIO(newPrice =>
            for
              oldPrice <- price.get
              priceHasDipped = oldPrice.value >= cutOff && newPrice.value < cutOff
              priceHasRisen = oldPrice.value <= cutOff && newPrice.value > cutOff
              toggle =  priceHasDipped || priceHasRisen
              _ <-
                if (priceHasDipped)
                  alert(newPrice.value, "Bitcoin has dipped and is not profitable to mine", toggle)
                else if (priceHasRisen)
                  alert(newPrice.value, "Bitcoin has risen and is now profitable to mine", toggle)
                else
                  alert(newPrice.value, "Bitcoin price is stable", toggle)
              _ <- price.set(newPrice)
            yield ()
          )
          .runDrain  raceFirst zio.Console.readLine
    yield ()
