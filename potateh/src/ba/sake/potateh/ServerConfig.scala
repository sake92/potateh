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

  @deprecated("Use maxRequestLineBytes instead.", "next")
  def requestLineBytes: Int = maxRequestLineBytes

  @deprecated("Use withMaxRequestLineBytes instead.", "next")
  def withRequestLineBytes(value: Int): HttpLimits = withMaxRequestLineBytes(value)

  @deprecated("Use maxHeaderLineBytes instead.", "next")
  def headerLineBytes: Int = maxHeaderLineBytes

  @deprecated("Use withMaxHeaderLineBytes instead.", "next")
  def withHeaderLineBytes(value: Int): HttpLimits = withMaxHeaderLineBytes(value)

  @deprecated("Use maxHeaderSectionBytes instead.", "next")
  def headerSectionBytes: Int = maxHeaderSectionBytes

  @deprecated("Use withMaxHeaderSectionBytes instead.", "next")
  def withHeaderSectionBytes(value: Int): HttpLimits = withMaxHeaderSectionBytes(value)

  @deprecated("Use maxHeaderCount instead.", "next")
  def headerCount: Int = maxHeaderCount

  @deprecated("Use withMaxHeaderCount instead.", "next")
  def withHeaderCount(value: Int): HttpLimits = withMaxHeaderCount(value)

  @deprecated("Use maxChunkLineBytes instead.", "next")
  def chunkLineBytes: Int = maxChunkLineBytes

  @deprecated("Use withMaxChunkLineBytes instead.", "next")
  def withChunkLineBytes(value: Int): HttpLimits = withMaxChunkLineBytes(value)

  @deprecated("Use maxChunkExtensionBytes instead.", "next")
  def chunkExtensionBytes: Int = maxChunkExtensionBytes

  @deprecated("Use withMaxChunkExtensionBytes instead.", "next")
  def withChunkExtensionBytes(value: Int): HttpLimits = withMaxChunkExtensionBytes(value)

  @deprecated("Use maxTrailerLineBytes instead.", "next")
  def trailerLineBytes: Int = maxTrailerLineBytes

  @deprecated("Use withMaxTrailerLineBytes instead.", "next")
  def withTrailerLineBytes(value: Int): HttpLimits = withMaxTrailerLineBytes(value)

  @deprecated("Use maxTrailerSectionBytes instead.", "next")
  def trailerSectionBytes: Int = maxTrailerSectionBytes

  @deprecated("Use withMaxTrailerSectionBytes instead.", "next")
  def withTrailerSectionBytes(value: Int): HttpLimits = withMaxTrailerSectionBytes(value)

  @deprecated("Use maxTrailerCount instead.", "next")
  def trailerCount: Int = maxTrailerCount

  @deprecated("Use withMaxTrailerCount instead.", "next")
  def withTrailerCount(value: Int): HttpLimits = withMaxTrailerCount(value)

  @deprecated("Use maxBodyBytes instead.", "next")
  def bodyBytes: Option[Long] = maxBodyBytes

  @deprecated("Use withMaxBodyBytes instead.", "next")
  def withBodyBytes(value: Option[Long]): HttpLimits = withMaxBodyBytes(value)

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
    "bodyReadTimeout" -> bodyReadTimeout,
    "writeTimeout" -> writeTimeout
  )
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

  def withIdleTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(idleTimeout = value)
  }

  def withRequestHeadTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(requestHeadTimeout = value)
  }

  def withBodyReadTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(bodyReadTimeout = value)
  }

  def withWriteTimeout(value: FiniteDuration): HttpTimeouts = {
    copy(writeTimeout = value)
  }

  def withShutdownGrace(value: FiniteDuration): HttpTimeouts = {
    copy(shutdownGrace = value)
  }

  @deprecated("Use idleTimeout instead.", "next")
  def idle: FiniteDuration = idleTimeout

  @deprecated("Use withIdleTimeout instead.", "next")
  def withIdle(value: FiniteDuration): HttpTimeouts = withIdleTimeout(value)

  @deprecated("Use requestHeadTimeout instead.", "next")
  def head: FiniteDuration = requestHeadTimeout

  @deprecated("Use withRequestHeadTimeout instead.", "next")
  def withHead(value: FiniteDuration): HttpTimeouts = withRequestHeadTimeout(value)

  @deprecated("Use bodyReadTimeout instead.", "next")
  def bodyRead: FiniteDuration = bodyReadTimeout

  @deprecated("Use withBodyReadTimeout instead.", "next")
  def withBodyRead(value: FiniteDuration): HttpTimeouts = withBodyReadTimeout(value)

  @deprecated("Use writeTimeout instead.", "next")
  def write: FiniteDuration = writeTimeout

  @deprecated("Use withWriteTimeout instead.", "next")
  def withWrite(value: FiniteDuration): HttpTimeouts = withWriteTimeout(value)

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
    writeTimeout = 30.seconds,
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

  /** Reports exchange failures synchronously on the connection worker. May run concurrently. Keep this callback short;
    * nonfatal exceptions from it are ignored.
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
