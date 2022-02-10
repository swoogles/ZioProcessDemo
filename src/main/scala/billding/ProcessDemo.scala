package billding

import zio.{Chunk, DurationOps, NonEmptyChunk, Queue, Ref, Schedule, ZIO, ZIOAppDefault, ZQueue, durationInt, process}
import zio.process.{Command, ProcessInput, ProcessOutput}
import zio.stream.ZStream

import java.io.File
import java.nio.charset.{Charset, StandardCharsets}
import java.time.{Duration, Instant, ZoneId}
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.Locale

object ProcessDemo extends zio.ZIOAppDefault {
  val ping = Command("ping", "google.com")

  def run =
    ping.linesStream
      .tap(line => ZIO.debug(line))
      .take(10)
      .runDrain
}



object WriteToFile extends ZIOAppDefault {
  private val touch = Command("touch", "junk.txt")

  private def append(line: String) = Command("echo", line) >> new java.io.File("junk.txt")

  private val appendLogLine =
    for {
      timestamp <- zio.Clock.instant
      _ <- append(formatter.format(timestamp)).run
    } yield ()

  val createAndRepeatedlyAppendTo =
    for {
      _ <- touch.run
      _ <- (ZIO.debug("Appending now.") *> appendLogLine).repeat(Schedule.spaced(2.seconds) && Schedule.recurs(10))
    } yield "Finished"

  def run =
    createAndRepeatedlyAppendTo

  private lazy val formatter =
    DateTimeFormatter.ofPattern("h:mm a")
      .withLocale(Locale.US)
      .withZone(ZoneId.systemDefault());
}



object ObserveFile extends ZIOAppDefault {
  val touch = Command("tail", "-f", "junk.txt")

  def monitorFile(startTime: Instant) =
    for {
      _ <- touch
        .linesStream
        .takeUntilZIO(Utils.repeatForATime(startTime))
        .tap(line => ZIO.debug(line))
        .runDrain
    } yield ()

  def run =
    for {
      startTime <- zio.Clock.instant
      _ <- ZIO.debug(startTime)
      _ <- monitorFile(startTime).raceEither(WriteToFile.createAndRepeatedlyAppendTo)
    } yield "Finished"
}

object TopDemo extends ZIOAppDefault {
  val top = Command("top")

  case class TopLine(applicationName: String, cpuPercentage: Float, mem: String)

  object TopLine {
    def apply(raw: String): TopLine = {

      val truncated = raw.take(70)
      val applicationName = truncated.slice(7, 19).trim
      val cpuValue = truncated.slice(23, 27).toFloat
      val mem = truncated.slice(58, 63).trim
      TopLine(applicationName, cpuValue, mem)
    }
  }

  def run =
    for {
      startTime <- zio.Clock.instant
      _ <- top
        .linesStream
        .drop(1)
        .filter(line => Range(0, 9).exists(num => line.startsWith(num.toString)) && line.contains("*"))
        .map(TopLine(_))
        .filter(_.cpuPercentage > 10.0)
        .take(1)
        .foreach(line =>
          ZIO.debug(line) *>
            Utils.say(line.applicationName + "is using a lot of CPU").run
        )
      //        .runDrain
    } yield ()
}

object Interactive extends ZIOAppDefault {
  val run =
    for {
      commandQueue <- Queue
        .unbounded[Chunk[Byte]]
      process <- Command("python3", "-qi").stdin(ProcessInput.fromQueue(commandQueue)).run
      _ <- process.stdout.linesStream.foreach { response =>
        ZIO.debug(s"RESULT: $response")
      }.forkDaemon
      _ <- process.stderr.linesStream.foreach { response =>
        ZIO.debug(s"ERROR: $response")
      }.forkDaemon
      _ <- zio.Console.readLine.flatMap(line => commandQueue.offer(Chunk.fromArray((line + "\n").getBytes(StandardCharsets.UTF_8)))).forever
    } yield ()

}



object GourceDemo extends ZIOAppDefault {
  val gource = Command("gource")
  val ps = Command("ps", "aux").stream
  val gourceProcess = Command("grep", "gource").stdin(ProcessInput.fromStream(ps))
  val repos = Seq(
    "/Users/bfrasure/Repositories/zio-ecosystem",
    "/Users/bfrasure/Repositories/zio",
  )

  def kill(pid: Int) = Command("kill", pid.toString)

  def run =
    for {
      startTime <- zio.Clock.instant
      ranInt <- zio.Random.nextIntBounded(repos.length)
      gourceProcess <- gource.copy(workingDirectory = Some(new File(repos(ranInt)))).run
      _ <- ZIO.sleep(15.seconds)
      _ <- gourceProcess.killForcibly
      //      _ <- ZIO.debug(gp)
      //      _ <- gourceFork.interrupt
    } yield ()
}


object Utils {
  def say(message: String) =
    Command("say", message)

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


}


object Shpotify extends ZIOAppDefault {
  val playYouSuffer =
    Command("spotify", "play", "uri", "spotify:track:5oD2Z1OOx1Tmcu2mc9sLY2")

  val stop =
    Command("spotify", "stop")

  val audioNotification =
    (for {
      _ <- playYouSuffer.run
      _ <- ZIO.sleep(2.seconds)
    } yield ()).ensuring(ZIO.debug("Ensuring") *> stop.run.orDie) // TODO Better guarantee when process is killed?

  def run =
    audioNotification
}


case class BitcoinPrice(value: Double)

object TrackBitcoin extends ZIOAppDefault {
  def alert(price: Double, msg: String, toggle: Boolean) =
    ZIO.unit *> ZIO
  .debug("Bitcoin price: " + price)
  //      *> ZIO.debug(msg)
  //      *> (if (toggle) Shpotify.audioNotification  else ZIO.unit)

  def run = {

    val cutOff = 65
    for {
      price <- Ref.make(BitcoinPrice(0.0))
      _ <-
        ZStream.repeatZIO(ZIO.sleep(3.second) *> ZIO.succeed(BitcoinPrice(Math.random * 100)))
          .mapZIO(newPrice =>
            for {
              oldPrice <- price.get
              priceHasDipped = oldPrice.value >= cutOff && newPrice.value < cutOff
              priceHasRisen = oldPrice.value <= cutOff && newPrice.value > cutOff
              toggle = priceHasDipped || priceHasRisen
              _ <-
                if (priceHasDipped)
                  alert(newPrice.value, "Bitcoin has dipped and is not profitable to mine", toggle)
                else if (priceHasRisen)
                  alert(newPrice.value, "Bitcoin has risen and is now profitable to mine", toggle)
                else
                  alert(newPrice.value, "Bitcoin price is stable", toggle)
              _ <- price.set(newPrice)
            } yield ()
          )
          .runDrain raceFirst zio.Console.readLine
    } yield ()
  }
}

