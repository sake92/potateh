package conformance

import ba.sake.potateh.*
import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*

/** Application fixture only; policy and environment access do not enter the library.
  */
object ConformanceServer {
  def main(args: Array[String]): Unit = {
    val profile = sys.env.getOrElse("HTTP_CONFORMANCE_PROFILE", "h1spec")
    require(
      Set("h1spec", "http11probe", "slowclients", "slowhttptest").contains(
        profile
      )
    )
    val slow = profile == "slowclients" || profile == "slowhttptest"
    val capacity = sys.env.getOrElse("HTTP_CONFORMANCE_CAPACITY", "roomy")
    require(Set("roomy", "limited").contains(capacity))
    val active = new AtomicInteger
    val settings = ServerConfig(
      bindAddress = new InetSocketAddress("127.0.0.1", 0),
      maxConnections = if (slow && capacity == "limited") 2 else 128,
      limits = HttpLimits(bodyBytes = Some(8L * 1024 * 1024)),
      timeouts = HttpTimeouts(shutdownGrace = 200.millis)
    )
    val server = Server.start(settings) { request =>
      if (slow) {
        println("HANDLING " + request.target)
        System.out.flush()
      }
      if (slow && request.target == "/large") {
        Response(body = ResponseBody.Writer(Some(64L * 1024 * 1024)) { out =>
          active.incrementAndGet()
          println("LARGE_STARTED"); System.out.flush()
          try {
            val block = new Array[Byte](8192)
            (0 until 8192).foreach(_ => out.write(block))
            println("LARGE_FINISHED"); System.out.flush()
          } finally active.decrementAndGet()
        })
      } else {
        active.incrementAndGet()
        try {
          val bytes = {
            if (
              profile == "http11probe" && request.target.takeWhile(
                _ != '?'
              ) == "/echo"
            ) {
              val text = new StringBuilder
              request.headers.fields.foreach { case (name, value) =>
                val lower = name.map(c => if (c >= 'A' && c <= 'Z') (c + 32).toChar else c)
                text.append(lower).append(": ").append(value).append('\n')
              }
              text.toString.getBytes(UTF_8)
            } else if (profile == "h1spec" || request.method == "POST") {
              val sink = new ByteArrayOutputStream
              request.body.transferTo(sink)
              sink.toByteArray
            } else "OK".getBytes(UTF_8)
          }
          Response(
            headers = Headers("Content-Type" -> "text/plain"),
            body = ResponseBody.bytes(bytes)
          )
        } finally active.decrementAndGet()
      }
    }
    val closed = new AtomicBoolean(false)
    def cleanup(): Unit = if (closed.compareAndSet(false, true)) {
      val start = System.nanoTime()
      server.stop()
      println("STOPPED millis=" + (System.nanoTime() - start) / 1000000)
      System.out.flush()
    }
    val hook = new Thread(() => cleanup(), "fixture-cleanup")
    Runtime.getRuntime.addShutdownHook(hook)
    try {
      println("LISTENING " + server.address.getPort)
      System.out.flush()
      if (args.contains("--smoke")) {
        val socket = new Socket()
        try {
          socket.connect(server.address, 1000)
          socket.setSoTimeout(3000)
          socket.getOutputStream.write(
            "POST /raw%2F?q=+ HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nConnection: close\r\n\r\nping"
              .getBytes(UTF_8)
          )
          socket.getOutputStream.flush()
          val sink = new ByteArrayOutputStream
          socket.getInputStream.transferTo(sink)
          val response = new String(sink.toByteArray, UTF_8)
          require(
            response.startsWith("HTTP/1.1 200 ") && response.endsWith(
              "\r\n\r\nping"
            ),
            response
          )
          println("SMOKE PASSED")
        } finally socket.close()
      } else if (slow) {
        val stop = Paths.get(sys.env("HTTP_CONFORMANCE_STOP_FILE"))
        while (!Files.exists(stop)) {
          println("WORKERS active=" + active.get()); System.out.flush()
          Thread.sleep(100)
        }
      } else new CountDownLatch(1).await()
    } finally {
      cleanup()
      // Native may unwind the main wait while its TERM shutdown hook runs.
      try Runtime.getRuntime.removeShutdownHook(hook)
      catch { case _: IllegalStateException => () }
    }
  }
}
