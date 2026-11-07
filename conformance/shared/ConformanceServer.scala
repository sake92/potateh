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
      Set("h1spec", "http11probe", "slowclients", "slowhttptest", "sustained", "fuzz").contains(
        profile
      )
    )
    val sustained = profile == "sustained"
    val slow = profile == "slowclients" || profile == "slowhttptest" || sustained
    val fuzz = profile == "fuzz"
    val capacity = sys.env.getOrElse("HTTP_CONFORMANCE_CAPACITY", "roomy")
    require(Set("roomy", "limited").contains(capacity))
    val active = new AtomicInteger
    val settings = ServerConfig(
      bindAddress = new InetSocketAddress("127.0.0.1", 0),
      maxConnections = if (slow && capacity == "limited") 2 else 128,
      limits = if (fuzz) {
        HttpLimits(
          requestLineBytes = 128,
          headerLineBytes = 128,
          headerSectionBytes = 512,
          headerCount = 8,
          chunkLineBytes = 64,
          chunkExtensionBytes = 96,
          trailerLineBytes = 64,
          trailerSectionBytes = 128,
          trailerCount = 4,
          bodyBytes = Some(256)
        )
      } else HttpLimits(bodyBytes = Some(8L * 1024 * 1024)),
      timeouts = if (fuzz) {
        HttpTimeouts(idle = 2.seconds, head = 2.seconds, bodyRead = 2.seconds, shutdownGrace = 200.millis)
      } else if (sustained) {
        HttpTimeouts(
          idle = 2.seconds,
          head = 1.second,
          bodyRead = 1.second,
          write = 1.second,
          shutdownGrace = 200.millis
        )
      } else HttpTimeouts(shutdownGrace = 200.millis)
    )
    val server = Server.start(settings) { request =>
      if (slow) {
        println("HANDLING " + request.target)
        System.out.flush()
      }
      if (sustained && Set("/stream-fixed", "/stream-chunked").contains(request.target)) {
        val length = Option.when(request.target == "/stream-fixed")(4096L)
        Response(body = ResponseBody.Writer(length) { out =>
          active.incrementAndGet()
          try {
            val block = Array.fill[Byte](256)('s'.toByte)
            for (_ <- 0 until 16) {
              out.write(block)
              out.flush()
              Thread.sleep(100)
            }
          } finally active.decrementAndGet()
        })
      } else if (slow && request.target == "/large") {
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
            if (fuzz) {
              val sink = new ByteArrayOutputStream
              request.body.transferTo(sink)
              val trailers = request.body.trailers.getOrElse(throw new IllegalStateException("Incomplete body"))
              val prefix = request.target + "\n" + trailers.first("X-End").getOrElse("") + "\n"
              prefix.getBytes(UTF_8) ++ sink.toByteArray
            } else if (
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
      } else if (slow || fuzz) {
        val stop = Paths.get(sys.env("HTTP_CONFORMANCE_STOP_FILE"))
        while (!Files.exists(stop)) {
          if (slow) { println("WORKERS active=" + active.get()); System.out.flush() }
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
