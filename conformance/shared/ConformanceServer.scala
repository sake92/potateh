package conformance

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.concurrent.duration.*

import ba.sake.potateh.*

/** Application fixture only; policy and environment access do not enter the library.
  */
object ConformanceServer {

  def main(args: Array[String]): Unit = {
    val profile = sys.env.getOrElse("HTTP_CONFORMANCE_PROFILE", "h1spec")
    require(
      Set("h1spec", "http11probe", "slowclients", "slowhttptest", "sustained", "fuzz", "nmap", "proxy").contains(
        profile
      )
    )
    val sustained = profile == "sustained"
    val slow = profile == "slowclients" || profile == "slowhttptest" || sustained || profile == "nmap"
    val fuzz = profile == "fuzz"
    val proxy = profile == "proxy"
    def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
    val capacity = sys.env.getOrElse("HTTP_CONFORMANCE_CAPACITY", "roomy")
    require(Set("roomy", "limited").contains(capacity))
    val active = new AtomicInteger
    val settings = ServerConfig.Default
      .withBindAddress(new InetSocketAddress("127.0.0.1", 0))
      .withMaxConnections(if (slow && capacity == "limited") 2 else 128)
      .withLimits(if (fuzz) {
        HttpLimits.Default
          .withMaxRequestLineBytes(128)
          .withMaxHeaderLineBytes(128)
          .withMaxHeaderSectionBytes(512)
          .withMaxHeaderCount(8)
          .withMaxChunkLineBytes(64)
          .withMaxChunkExtensionBytes(96)
          .withMaxTrailerLineBytes(64)
          .withMaxTrailerSectionBytes(128)
          .withMaxTrailerCount(4)
          .withMaxBodyBytes(Some(256))
      } else HttpLimits.Default.withMaxBodyBytes(Some(8L * 1024 * 1024)))
      .withTimeouts(if (fuzz) {
        HttpTimeouts.Default
          .withIdleTimeout(2.seconds)
          .withRequestHeadTimeout(2.seconds)
          .withBodyReadTimeout(2.seconds)
          .withShutdownGrace(200.millis)
      } else if (sustained) {
        HttpTimeouts.Default
          .withIdleTimeout(2.seconds)
          .withRequestHeadTimeout(1.second)
          .withBodyReadTimeout(1.second)
          .withWriteTimeout(1.second)
          .withShutdownGrace(200.millis)
      } else if (profile == "nmap") {
        HttpTimeouts.Default.withRequestHeadTimeout(15.seconds).withShutdownGrace(200.millis)
      } else HttpTimeouts.Default.withShutdownGrace(200.millis))
    val serverSettings = {
      if (proxy) settings.withOnError(error => println("PROXY_ERROR\t" + error.toString))
      else settings
    }
    val server = Server.start(serverSettings) { request =>
      if (proxy) {
        println(
          s"PROXY_HEAD\t${request.remoteAddress.getPort}\t${request.method}\t${hex(request.target.getBytes(UTF_8))}"
        )
        System.out.flush()
      }
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
          println("LARGE_STARTED")
          System.out.flush()
          try {
            val block = new Array[Byte](8192)
            (0 until 8192).foreach(_ => out.write(block))
            println("LARGE_FINISHED")
            System.out.flush()
          } finally active.decrementAndGet()
        })
      } else {
        active.incrementAndGet()
        try {
          val bytes = {
            if (proxy) {
              val sink = new ByteArrayOutputStream
              request.body.transferTo(sink)
              val body = sink.toByteArray
              val target = hex(request.target.getBytes(UTF_8))
              val payload = hex(body)
              println(s"PROXY_BODY\t${request.remoteAddress.getPort}\t${request.method}\t$target\t$payload")
              System.out.flush()
              s"${request.method}\t$target\t$payload".getBytes(UTF_8)
            } else if (fuzz) {
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
      } else if (slow || fuzz || proxy) {
        val stop = Paths.get(sys.env("HTTP_CONFORMANCE_STOP_FILE"))
        while (!Files.exists(stop)) {
          if (slow) {
            println("WORKERS active=" + active.get())
            System.out.flush()
          }
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
