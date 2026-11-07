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
  require(
    requestLineBytes > 0 && headerLineBytes > 0 && chunkLineBytes > 0 && trailerLineBytes > 0
  )
  require(headerSectionBytes >= 2 && trailerSectionBytes >= 2)
  require(headerCount >= 0 && trailerCount >= 0 && chunkExtensionBytes >= 0)
  require(bodyBytes.forall(_ >= 0))

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
  def apply(
      requestLineBytes: Int = 8192,
      headerLineBytes: Int = 8192,
      headerSectionBytes: Int = 32768,
      headerCount: Int = 100,
      chunkLineBytes: Int = 8192,
      chunkExtensionBytes: Int = 32768,
      trailerLineBytes: Int = 8192,
      trailerSectionBytes: Int = 16384,
      trailerCount: Int = 50,
      bodyBytes: Option[Long] = Some(8L * 1024 * 1024)
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

final class HttpTimeouts private (
    val idle: FiniteDuration,
    val head: FiniteDuration,
    val bodyRead: FiniteDuration,
    val write: FiniteDuration,
    val shutdownGrace: FiniteDuration
) {
  private val io = List(idle, head, bodyRead, write)
  require(
    io.forall(d => d.toNanos > 0 && d.toNanos <= Int.MaxValue.toLong * 1000000L),
    "I/O timeouts must fit socket milliseconds"
  )
  require(
    shutdownGrace.toNanos >= 0 && shutdownGrace <= 1.day,
    "Shutdown grace must be between zero and one day"
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
  def apply(
      idle: FiniteDuration = 15.seconds,
      head: FiniteDuration = 10.seconds,
      bodyRead: FiniteDuration = 30.seconds,
      write: FiniteDuration = 30.seconds,
      shutdownGrace: FiniteDuration = 5.seconds
  ): HttpTimeouts = {
    new HttpTimeouts(idle, head, bodyRead, write, shutdownGrace)
  }
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
  def apply(
      bindAddress: InetSocketAddress = new InetSocketAddress("127.0.0.1", 8080),
      maxConnections: Int = 128,
      limits: HttpLimits = HttpLimits(),
      timeouts: HttpTimeouts = HttpTimeouts()
  ): ServerConfig = {
    new ServerConfig(bindAddress, maxConnections, limits, timeouts)
  }
}
