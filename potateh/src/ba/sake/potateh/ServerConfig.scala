package ba.sake.potateh

import java.net.InetSocketAddress

import scala.concurrent.duration.*

final class HttpLimits private (
    val maxRequestLineBytes: Int,
    val maxHeaderLineBytes: Int,
    val maxHeaderSectionBytes: Int,
    val maxHeaderCount: Int,
    val maxChunkLineBytes: Int,
    val maxChunkExtensionBytes: Int,
    val maxTrailerLineBytes: Int,
    val maxTrailerSectionBytes: Int,
    val maxTrailerCount: Int,
    val maxBodyBytes: Option[Long]
) {
  require(maxRequestLineBytes > 0, s"maxRequestLineBytes must be at least 1 (was $maxRequestLineBytes)")
  require(maxHeaderLineBytes > 0, s"maxHeaderLineBytes must be at least 1 (was $maxHeaderLineBytes)")
  require(maxHeaderSectionBytes >= 2, s"maxHeaderSectionBytes must be at least 2 (was $maxHeaderSectionBytes)")
  require(maxHeaderCount >= 0, s"maxHeaderCount must be at least 0 (was $maxHeaderCount)")
  require(maxChunkLineBytes > 0, s"maxChunkLineBytes must be at least 1 (was $maxChunkLineBytes)")
  require(maxChunkExtensionBytes >= 0, s"maxChunkExtensionBytes must be at least 0 (was $maxChunkExtensionBytes)")
  require(maxTrailerLineBytes > 0, s"maxTrailerLineBytes must be at least 1 (was $maxTrailerLineBytes)")
  require(maxTrailerSectionBytes >= 2, s"maxTrailerSectionBytes must be at least 2 (was $maxTrailerSectionBytes)")
  require(maxTrailerCount >= 0, s"maxTrailerCount must be at least 0 (was $maxTrailerCount)")
  require(maxBodyBytes.forall(_ >= 0), s"maxBodyBytes must be nonnegative when defined (was $maxBodyBytes)")

  def withMaxRequestLineBytes(value: Int): HttpLimits = {
    copy(maxRequestLineBytes = value)
  }

  def withMaxHeaderLineBytes(value: Int): HttpLimits = {
    copy(maxHeaderLineBytes = value)
  }

  def withMaxHeaderSectionBytes(value: Int): HttpLimits = {
    copy(maxHeaderSectionBytes = value)
  }

  def withMaxHeaderCount(value: Int): HttpLimits = {
    copy(maxHeaderCount = value)
  }

  def withMaxChunkLineBytes(value: Int): HttpLimits = {
    copy(maxChunkLineBytes = value)
  }

  def withMaxChunkExtensionBytes(value: Int): HttpLimits = {
    copy(maxChunkExtensionBytes = value)
  }

  def withMaxTrailerLineBytes(value: Int): HttpLimits = {
    copy(maxTrailerLineBytes = value)
  }

  def withMaxTrailerSectionBytes(value: Int): HttpLimits = {
    copy(maxTrailerSectionBytes = value)
  }

  def withMaxTrailerCount(value: Int): HttpLimits = {
    copy(maxTrailerCount = value)
  }

  def withMaxBodyBytes(value: Option[Long]): HttpLimits = {
    copy(maxBodyBytes = value)
  }

  private def copy(
      maxRequestLineBytes: Int = this.maxRequestLineBytes,
      maxHeaderLineBytes: Int = this.maxHeaderLineBytes,
      maxHeaderSectionBytes: Int = this.maxHeaderSectionBytes,
      maxHeaderCount: Int = this.maxHeaderCount,
      maxChunkLineBytes: Int = this.maxChunkLineBytes,
      maxChunkExtensionBytes: Int = this.maxChunkExtensionBytes,
      maxTrailerLineBytes: Int = this.maxTrailerLineBytes,
      maxTrailerSectionBytes: Int = this.maxTrailerSectionBytes,
      maxTrailerCount: Int = this.maxTrailerCount,
      maxBodyBytes: Option[Long] = this.maxBodyBytes
  ): HttpLimits = {
    new HttpLimits(
      maxRequestLineBytes,
      maxHeaderLineBytes,
      maxHeaderSectionBytes,
      maxHeaderCount,
      maxChunkLineBytes,
      maxChunkExtensionBytes,
      maxTrailerLineBytes,
      maxTrailerSectionBytes,
      maxTrailerCount,
      maxBodyBytes
    )
  }

}

object HttpLimits {
  val Default: HttpLimits = new HttpLimits(
    maxRequestLineBytes = 8192,
    maxHeaderLineBytes = 8192,
    maxHeaderSectionBytes = 32768,
    maxHeaderCount = 100,
    maxChunkLineBytes = 8192,
    maxChunkExtensionBytes = 32768,
    maxTrailerLineBytes = 8192,
    maxTrailerSectionBytes = 16384,
    maxTrailerCount = 50,
    maxBodyBytes = Some(8L * 1024 * 1024)
  )
}

final class HttpTimeouts private (
    val idleTimeout: FiniteDuration,
    val requestHeadTimeout: FiniteDuration,
    val bodyReadTimeout: FiniteDuration,
    val writeTimeout: FiniteDuration,
    val shutdownGrace: FiniteDuration
) {
  List(
    "idleTimeout" -> idleTimeout,
    "requestHeadTimeout" -> requestHeadTimeout,
    "bodyReadTimeout" -> bodyReadTimeout
  )
    .foreach { case (name, value) =>
      require(
        value.toNanos > 0 && value.toNanos <= Int.MaxValue.toLong * 1000000L,
        s"$name must be positive and at most 2147483647 milliseconds (was $value)"
      )
    }
  require(
    writeTimeout.toNanos >= 0 && writeTimeout.toNanos <= Int.MaxValue.toLong * 1000000L,
    s"writeTimeout must be nonnegative and at most 2147483647 milliseconds (was $writeTimeout)"
  )
  require(
    shutdownGrace.toNanos >= 0 && shutdownGrace <= 1.day,
    s"shutdownGrace must be between zero and one day (was $shutdownGrace)"
  )

  def withIdleTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(idleTimeout = value)
  }

  def withRequestHeadTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(requestHeadTimeout = value)
  }

  def withBodyReadTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(bodyReadTimeout = value)
  }

  /** A zero duration disables the socket write timeout. */
  def withWriteTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(writeTimeout = value)
  }

  def withShutdownGrace(value: FiniteDuration): HttpTimeouts = {
    copy(shutdownGrace = value)
  }

  private def copy(
      idleTimeout: FiniteDuration = this.idleTimeout,
      requestHeadTimeout: FiniteDuration = this.requestHeadTimeout,
      bodyReadTimeout: FiniteDuration = this.bodyReadTimeout,
      writeTimeout: FiniteDuration = this.writeTimeout,
      shutdownGrace: FiniteDuration = this.shutdownGrace
  ): HttpTimeouts = {
    new HttpTimeouts(
      idleTimeout,
      requestHeadTimeout,
      bodyReadTimeout,
      writeTimeout,
      shutdownGrace
    )
  }

}

object HttpTimeouts {
  val Default: HttpTimeouts = new HttpTimeouts(
    idleTimeout = 15.seconds,
    requestHeadTimeout = 10.seconds,
    bodyReadTimeout = 30.seconds,
    writeTimeout = Duration.Zero,
    shutdownGrace = 5.seconds
  )
}

final class ServerConfig private (
    val bindAddress: InetSocketAddress,
    val maxConnections: Int,
    val backlog: Int,
    val onError: Throwable => Unit,
    val limits: HttpLimits,
    val timeouts: HttpTimeouts
) {
  require(!bindAddress.isUnresolved, "Unresolved bind address")
  require(maxConnections > 0, "maxConnections must be positive")
  require(backlog > 0, "backlog must be positive")

  def withBindAddress(value: InetSocketAddress): ServerConfig = {
    copy(bindAddress = value)
  }

  def withMaxConnections(value: Int): ServerConfig = {
    copy(maxConnections = value)
  }

  /** Requested listener queue size; the operating system may cap it. Independent of maxConnections. */
  def withBacklog(value: Int): ServerConfig = {
    copy(backlog = value)
  }

  /** Reports application and cleanup failures synchronously on the connection worker. Transport disconnects and
    * response send/flush failures are treated as connection closure instead. May run concurrently. Keep this callback
    * short; nonfatal exceptions from it are ignored.
    */
  def withOnError(value: Throwable => Unit): ServerConfig = {
    copy(onError = value)
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
      backlog: Int = this.backlog,
      onError: Throwable => Unit = this.onError,
      limits: HttpLimits = this.limits,
      timeouts: HttpTimeouts = this.timeouts
  ): ServerConfig = {
    new ServerConfig(
      bindAddress,
      maxConnections,
      backlog,
      onError,
      limits,
      timeouts
    )
  }

}

object ServerConfig {
  val Default: ServerConfig = new ServerConfig(
    bindAddress = new InetSocketAddress("127.0.0.1", 8080),
    maxConnections = 128,
    backlog = 128,
    onError = _.printStackTrace(),
    limits = HttpLimits.Default,
    timeouts = HttpTimeouts.Default
  )
}
