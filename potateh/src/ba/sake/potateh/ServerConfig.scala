package ba.sake.potateh

import java.net.InetSocketAddress
import scala.concurrent.duration.*

final case class HttpLimits(
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
) {
  require(requestLineBytes > 0 && headerLineBytes > 0 && chunkLineBytes > 0 && trailerLineBytes > 0)
  require(headerSectionBytes >= 2 && trailerSectionBytes >= 2)
  require(headerCount >= 0 && trailerCount >= 0 && chunkExtensionBytes >= 0)
  require(bodyBytes.forall(_ >= 0))
}
final case class HttpTimeouts(
    idle: FiniteDuration = 15.seconds,
    head: FiniteDuration = 10.seconds,
    bodyRead: FiniteDuration = 30.seconds,
    write: FiniteDuration = 30.seconds,
    shutdownGrace: FiniteDuration = 5.seconds
) {
  private val io = List(idle, head, bodyRead, write)
  require(io.forall(d => d.toNanos > 0 && d.toMillis <= Int.MaxValue), "I/O timeouts must fit socket milliseconds")
  require(shutdownGrace.toNanos >= 0 && shutdownGrace.toDays <= 1, "Shutdown grace must be between zero and one day")
}
final case class ServerConfig(
    bindAddress: InetSocketAddress = new InetSocketAddress("127.0.0.1", 8080),
    maxConnections: Int = 128,
    limits: HttpLimits = HttpLimits(),
    timeouts: HttpTimeouts = HttpTimeouts()
) {
  require(!bindAddress.isUnresolved, "Unresolved bind address")
  require(maxConnections > 0, "maxConnections must be positive")
}
