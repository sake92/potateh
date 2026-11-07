package ba.sake.potateh

import java.net.InetSocketAddress
import scala.concurrent.duration.*

final class HttpLimits private (
    val requestLineBytes: Int,
    val headerLineBytes: Int,
    val headerSectionBytes: Int,
    val headerCount: Int,
    val chunkLineBytes: Int,
    val chunkExtensionBytes: Int,
    val trailerLineBytes: Int,
    val trailerSectionBytes: Int,
    val trailerCount: Int,
    val bodyBytes: Option[Long]
) {
  require(requestLineBytes > 0, s"requestLineBytes must be at least 1 (was $requestLineBytes)")
  require(headerLineBytes > 0, s"headerLineBytes must be at least 1 (was $headerLineBytes)")
  require(headerSectionBytes >= 2, s"headerSectionBytes must be at least 2 (was $headerSectionBytes)")
  require(headerCount >= 0, s"headerCount must be at least 0 (was $headerCount)")
  require(chunkLineBytes > 0, s"chunkLineBytes must be at least 1 (was $chunkLineBytes)")
  require(chunkExtensionBytes >= 0, s"chunkExtensionBytes must be at least 0 (was $chunkExtensionBytes)")
  require(trailerLineBytes > 0, s"trailerLineBytes must be at least 1 (was $trailerLineBytes)")
  require(trailerSectionBytes >= 2, s"trailerSectionBytes must be at least 2 (was $trailerSectionBytes)")
  require(trailerCount >= 0, s"trailerCount must be at least 0 (was $trailerCount)")
  require(bodyBytes.forall(_ >= 0), s"bodyBytes must be nonnegative when defined (was $bodyBytes)")

  def withRequestLineBytes(value: Int): HttpLimits = {
    copy(requestLineBytes = value)
  }
  def withHeaderLineBytes(value: Int): HttpLimits = {
    copy(headerLineBytes = value)
  }
  def withHeaderSectionBytes(value: Int): HttpLimits = {
    copy(headerSectionBytes = value)
  }
  def withHeaderCount(value: Int): HttpLimits = {
    copy(headerCount = value)
  }
  def withChunkLineBytes(value: Int): HttpLimits = {
    copy(chunkLineBytes = value)
  }
  def withChunkExtensionBytes(value: Int): HttpLimits = {
    copy(chunkExtensionBytes = value)
  }
  def withTrailerLineBytes(value: Int): HttpLimits = {
    copy(trailerLineBytes = value)
  }
  def withTrailerSectionBytes(value: Int): HttpLimits = {
    copy(trailerSectionBytes = value)
  }
  def withTrailerCount(value: Int): HttpLimits = {
    copy(trailerCount = value)
  }
  def withBodyBytes(value: Option[Long]): HttpLimits = {
    copy(bodyBytes = value)
  }

  private def copy(
      requestLineBytes: Int = this.requestLineBytes,
      headerLineBytes: Int = this.headerLineBytes,
      headerSectionBytes: Int = this.headerSectionBytes,
      headerCount: Int = this.headerCount,
      chunkLineBytes: Int = this.chunkLineBytes,
      chunkExtensionBytes: Int = this.chunkExtensionBytes,
      trailerLineBytes: Int = this.trailerLineBytes,
      trailerSectionBytes: Int = this.trailerSectionBytes,
      trailerCount: Int = this.trailerCount,
      bodyBytes: Option[Long] = this.bodyBytes
  ): HttpLimits = {
    new HttpLimits(
      requestLineBytes,
      headerLineBytes,
      headerSectionBytes,
      headerCount,
      chunkLineBytes,
      chunkExtensionBytes,
      trailerLineBytes,
      trailerSectionBytes,
      trailerCount,
      bodyBytes
    )
  }
}

object HttpLimits {
  val Default: HttpLimits = new HttpLimits(
    requestLineBytes = 8192,
    headerLineBytes = 8192,
    headerSectionBytes = 32768,
    headerCount = 100,
    chunkLineBytes = 8192,
    chunkExtensionBytes = 32768,
    trailerLineBytes = 8192,
    trailerSectionBytes = 16384,
    trailerCount = 50,
    bodyBytes = Some(8L * 1024 * 1024)
  )
}

final class HttpTimeouts private (
    val idle: FiniteDuration,
    val head: FiniteDuration,
    val bodyRead: FiniteDuration,
    val write: FiniteDuration,
    val shutdownGrace: FiniteDuration
) {
  List("idle" -> idle, "head" -> head, "bodyRead" -> bodyRead, "write" -> write)
    .foreach { case (name, value) =>
      require(
        value.toNanos > 0 && value.toNanos <= Int.MaxValue.toLong * 1000000L,
        s"$name must be positive and at most 2147483647 milliseconds (was $value)"
      )
    }
  require(
    shutdownGrace.toNanos >= 0 && shutdownGrace <= 1.day,
    s"shutdownGrace must be between zero and one day (was $shutdownGrace)"
  )

  def withIdle(value: FiniteDuration): HttpTimeouts = {
    copy(idle = value)
  }
  def withHead(value: FiniteDuration): HttpTimeouts = {
    copy(head = value)
  }
  def withBodyRead(value: FiniteDuration): HttpTimeouts = {
    copy(bodyRead = value)
  }
  def withWrite(value: FiniteDuration): HttpTimeouts = {
    copy(write = value)
  }
  def withShutdownGrace(value: FiniteDuration): HttpTimeouts = {
    copy(shutdownGrace = value)
  }

  private def copy(
      idle: FiniteDuration = this.idle,
      head: FiniteDuration = this.head,
      bodyRead: FiniteDuration = this.bodyRead,
      write: FiniteDuration = this.write,
      shutdownGrace: FiniteDuration = this.shutdownGrace
  ): HttpTimeouts = {
    new HttpTimeouts(
      idle,
      head,
      bodyRead,
      write,
      shutdownGrace
    )
  }
}

object HttpTimeouts {
  val Default: HttpTimeouts = new HttpTimeouts(
    idle = 15.seconds,
    head = 10.seconds,
    bodyRead = 30.seconds,
    write = 30.seconds,
    shutdownGrace = 5.seconds
  )
}

final class ServerConfig private (
    val bindAddress: InetSocketAddress,
    val maxConnections: Int,
    val limits: HttpLimits,
    val timeouts: HttpTimeouts
) {
  require(!bindAddress.isUnresolved, "Unresolved bind address")
  require(maxConnections > 0, "maxConnections must be positive")

  def withBindAddress(value: InetSocketAddress): ServerConfig = {
    copy(bindAddress = value)
  }
  def withMaxConnections(value: Int): ServerConfig = {
    copy(maxConnections = value)
  }
  def withLimits(value: HttpLimits): ServerConfig = {
    copy(limits = value)
  }
  def withTimeouts(value: HttpTimeouts): ServerConfig = {
    copy(timeouts = value)
  }

  private def copy(
      bindAddress: InetSocketAddress = this.bindAddress,
      maxConnections: Int = this.maxConnections,
      limits: HttpLimits = this.limits,
      timeouts: HttpTimeouts = this.timeouts
  ): ServerConfig = {
    new ServerConfig(
      bindAddress,
      maxConnections,
      limits,
      timeouts
    )
  }
}

object ServerConfig {
  val Default: ServerConfig = new ServerConfig(
    bindAddress = new InetSocketAddress("127.0.0.1", 8080),
    maxConnections = 128,
    limits = HttpLimits.Default,
    timeouts = HttpTimeouts.Default
  )
}
