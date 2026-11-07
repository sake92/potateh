import java.io.{ByteArrayOutputStream, EOFException, IOException}
import java.net.{InetSocketAddress, SocketException}
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

/** Seeded grammar mutations and connection sequences; no external suite or library dependency. */
object Fuzzing {

  private case class Expected(status: Int, body: String = "")

  private case class Step(
      wire: String,
      responses: Vector[Expected],
      writes: Vector[Int],
      terminal: Boolean,
      halfClose: Boolean
  )

  private case class Sample(name: String, steps: Vector[Step])

  private val families = Vector(
    "pipeline",
    "staged",
    "body-limit",
    "header-line-limit",
    "header-count-limit",
    "trailer-count-limit",
    "header-section-limit",
    "header-section-over-limit",
    "chunk-line-limit",
    "chunk-line-over-limit",
    "extension-budget",
    "extension-over-budget",
    "trailer-section-limit",
    "trailer-section-over-limit",
    "http10-keepalive",
    "request-line-limit",
    "conflicting-framing",
    "duplicate-length",
    "overflow-length",
    "signed-length",
    "list-length",
    "missing-host",
    "duplicate-host",
    "bare-lf",
    "obs-fold",
    "invalid-field",
    "invalid-target",
    "unsupported-version",
    "duplicate-chunked",
    "chunked-not-last",
    "unsupported-coding",
    "invalid-chunk-size",
    "overflow-chunk-size",
    "chunk-delimiter",
    "chunk-extension",
    "invalid-trailer",
    "fixed-over-limit",
    "chunk-over-limit",
    "request-line-over-limit",
    "header-line-over-limit",
    "header-count-over-limit",
    "trailer-count-over-limit",
    "trailer-line-over-limit",
    "truncated-head",
    "truncated-fixed",
    "truncated-chunk",
    "truncated-trailer",
    "close-with-suffix"
  )

  def run(
      command: Seq[String],
      destination: Path,
      seed: Long,
      start: Int,
      iterations: Int,
      timeout: Double,
      revision: String
  ): Int = {
    val counts = mutable.LinkedHashMap.empty[String, Int]
    val report = ujson.Obj(
      "suite" -> "fuzz",
      "generator_version" -> 1,
      "revision" -> revision,
      "server" -> ujson.Arr.from(command),
      "seed" -> seed.toString,
      "start_case" -> start,
      "requested" -> iterations,
      "passed" -> 0,
      "complete" -> false
    )
    val stop = destination.resolve("stop").toAbsolutePath
    val log = destination.resolve("server.log")
    val process = ConformanceRunner.Children.start(
      command,
      log,
      Map(
        "HTTP_CONFORMANCE_PROFILE" -> "fuzz",
        "HTTP_CONFORMANCE_STOP_FILE" -> stop.toString
      )
    )
    var failed = false
    try {
      val port = ConformanceRunner.awaitPort(process, log).toInt
      val deadline = System.nanoTime() + (timeout * 1e9).toLong
      val end = start + iterations
      var index = start
      while (index < end && !failed) {
        val sample = generate(seed, index)
        val details = describe(sample, seed, index)
        val recovery = request(new Random(0), s"/recovery-$index", close = true, framing = 0)
        details("recovery_wire") = recovery._1
        details("phase") = "sequence"
        val observed = new ByteArrayOutputStream
        // Persist before any I/O so interruption or a hung case retains its reproducer.
        Files.writeString(destination.resolve("current.json"), ujson.write(details, indent = 2) + "\n")
        try {
          require(process.isAlive, "Fixture exited during fuzzing")
          require(System.nanoTime() < deadline, "Fuzz campaign exceeded --timeout")
          exercise(port, sample, observed, deadline)
          // A fresh connection must still work after every malformed or valid sequence.
          details("phase") = "recovery"
          exercise(
            port,
            Sample("recovery", Vector(step(new Random(0), recovery._1, Vector(recovery._2), true))),
            observed,
            deadline
          )
          require(process.isAlive, "Fixture exited after a fuzz case")
          counts(sample.name) = counts.getOrElse(sample.name, 0) + 1
          report("passed") = index - start + 1
        } catch {
          case NonFatal(error) =>
            failed = true
            details("error") = error.toString
            Files.writeString(destination.resolve("failure.json"), ujson.write(details, indent = 2) + "\n")
            Files.write(destination.resolve("response.bin"), observed.toByteArray)
            val wire = sample.steps.map(_.wire).mkString.getBytes(ISO_8859_1)
            Files.write(destination.resolve("request.bin"), wire)
            report("failure_case") = index
            System.err.println(s"fuzz failed: seed=$seed case=$index family=${sample.name}: $error")
            System.err.println(s"Replay: --suite fuzz --seed $seed --start-case $index --iterations 1")
        }
        index += 1
        if ((index - start) % 100 == 0) System.err.println(s"fuzz: ${index - start}/$iterations cases")
      }
      report("complete") = !failed
      if (failed) 1 else 0
    } finally {
      try {
        Files.writeString(stop, "stop\n")
        require(process.waitFor(5, TimeUnit.SECONDS), "Fuzz fixture did not stop within five seconds")
        require(
          process.exitValue() == 0 && Files.readString(log).contains("STOPPED millis="),
          "Fuzz fixture did not report a successful completed stop"
        )
      } catch {
        case error: Throwable =>
          report("complete") = false
          report("shutdown_error") = error.toString
          throw error
      } finally {
        try ConformanceRunner.Children.stop(process)
        finally {
          report("families") = ujson.Obj.from(counts.iterator.map { case (name, count) => name -> ujson.Num(count) })
          Files.writeString(destination.resolve("run.json"), ujson.write(report, indent = 2) + "\n")
          if (report("complete").bool) Files.deleteIfExists(destination.resolve("current.json"))
          System.err.println(s"fuzz: passed=${report("passed")}; results in $destination")
        }
      }
    }
  }

  private def describe(sample: Sample, seed: Long, index: Int): ujson.Obj = ujson.Obj(
    "generator_version" -> 1,
    "seed" -> seed.toString,
    "case" -> index,
    "family" -> sample.name,
    "steps" -> ujson.Arr.from(sample.steps.map { part =>
      ujson.Obj(
        "wire" -> part.wire,
        "write_sizes" -> ujson.Arr.from(part.writes),
        "terminal" -> part.terminal,
        "half_close" -> part.halfClose,
        "responses" -> ujson.Arr.from(part.responses.map(r => ujson.Obj("status" -> r.status, "body" -> r.body)))
      )
    })
  )

  private def step(
      random: Random,
      wire: String,
      responses: Vector[Expected],
      terminal: Boolean,
      halfClose: Boolean = false
  ): Step = {
    val writes = Vector.newBuilder[Int]
    var left = wire.length
    val mode = random.nextInt(3)
    while (left > 0) {
      val size = mode match {
        case 0 => left
        case 1 => 1
        case _ => math.min(left, 1 + random.nextInt(97))
      }
      writes += size
      left -= size
    }
    Step(wire, responses, writes.result(), terminal, halfClose)
  }

  private def request(random: Random, target: String, close: Boolean, framing: Int): (String, Expected) = {
    val sizes = Vector(0, 1, 15, 16, 31, 127, 255, 256)
    val size = if (framing == 0) 0 else sizes(random.nextInt(sizes.size))
    val decoy = "\r\nGET /decoy HTTP/1.1\r\nHost: x\r\n\r\n"
    val payload = (decoy + random.alphanumeric.take(256).mkString).take(size)
    val connection = if (close) "Connection: close\r\n" else ""
    val host = if (random.nextBoolean()) "hOsT:\tx\t\r\n" else "Host: x\r\n"
    val (headers, body, trailer) = framing match {
      case 0 => ("", "", "")
      case 1 =>
        val padding = "0" * random.nextInt(3)
        (s"cOnTeNt-LeNgTh: $padding$size\r\n", payload, "")
      case 2 =>
        val chunks = new StringBuilder
        var offset = 0
        while (offset < payload.length) {
          val length = math.min(payload.length - offset, 1 + random.nextInt(80))
          val hex = Integer.toHexString(length)
          val extension = if (offset == 0) ";a=\"b\\\"c\"" else ""
          chunks
            .append(hex)
            .append(extension)
            .append("\r\n")
            .append(payload.substring(offset, offset + length))
            .append("\r\n")
          offset += length
        }
        val marker = "end-" + random.nextInt(10000)
        ("Transfer-Encoding: chunked\r\n", chunks.toString + s"0;end=yes\r\nX-End: $marker\r\n\r\n", marker)
    }
    val method = if (framing == 0) "GET" else "POST"
    (
      s"$method $target HTTP/1.1\r\n$host$headers$connection\r\n$body",
      Expected(200, target + "\n" + trailer + "\n" + payload)
    )
  }

  private def generate(seed: Long, index: Int): Sample = {
    // Each index has its own PRNG; replay does not need to run earlier cases.
    val random = new Random(seed ^ (index.toLong * -7046029254386353131L))
    val name = families(index % families.size)
    val target = s"/case-$index-${random.nextInt(10000)}%2F?q=+"
    val prefix = Vector.tabulate(random.nextInt(3)) { i =>
      val (wire, expected) = request(random, s"$target/p$i", close = false, framing = random.nextInt(3))
      step(random, wire, Vector(expected), false)
    }
    val sentinel = "GET /sentinel HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
    def head(fields: String): String = s"POST $target HTTP/1.1\r\nHost: x\r\n$fields\r\n"
    def chunks(body: String): String = head("Transfer-Encoding: chunked\r\n") + body
    val (wire, expected, suffix) = name match {
      case "pipeline" | "staged" =>
        val count = 2 + random.nextInt(4)
        val requests = Vector.tabulate(count) { i =>
          request(random, s"$target/r$i", close = i == count - 1, framing = random.nextInt(3))
        }
        val steps = if (name == "staged") {
          requests.zipWithIndex.map { case ((text, response), i) =>
            step(random, text, Vector(response), i == count - 1)
          }
        } else Vector(step(random, requests.map(_._1).mkString, requests.map(_._2), true))
        return Sample(name, prefix ++ steps)
      case "body-limit" =>
        val body = "x" * 256
        (head("Content-Length: 256\r\n") + body, Expected(200, target + "\n\n" + body), true)
      case "header-line-limit" =>
        (head("X: " + "x" * 125 + "\r\n"), Expected(200, target + "\n\n"), true)
      case "header-count-limit" =>
        (head("X: x\r\n" * 7), Expected(200, target + "\n\n"), true)
      case "trailer-count-limit" =>
        (chunks("0\r\n" + "X: x\r\n" * 4 + "\r\n"), Expected(200, target + "\n\n"), true)
      case "header-section-limit" | "header-section-over-limit" =>
        val extra = if (name == "header-section-limit") 0 else 1
        val fields = ("X: " + "x" * 125 + "\r\n") * 3 + "X: " + "x" * (106 + extra) + "\r\n"
        val response = if (extra == 0) Expected(200, target + "\n\n") else Expected(431)
        (head(fields), response, true)
      case "chunk-line-limit" | "chunk-line-over-limit" =>
        val extra = if (name == "chunk-line-limit") 0 else 1
        val response = if (extra == 0) Expected(200, target + "\n\n") else Expected(400)
        (chunks("0;" + "x" * (62 + extra) + "\r\n\r\n"), response, true)
      case "extension-budget" | "extension-over-budget" =>
        val extra = if (name == "extension-budget") 0 else 1
        val first = "1;" + "x" * 47 + "\r\nx\r\n"
        val second = "1;" + "x" * (47 + extra) + "\r\nx\r\n0\r\n\r\n"
        val response = if (extra == 0) Expected(200, target + "\n\nxx") else Expected(400)
        (chunks(first + second), response, true)
      case "trailer-section-limit" | "trailer-section-over-limit" =>
        val extra = if (name == "trailer-section-limit") 0 else 1
        val fields = "X: " + "x" * 58 + "\r\nX: " + "x" * (58 + extra) + "\r\n\r\n"
        val response = if (extra == 0) Expected(200, target + "\n\n") else Expected(400)
        (chunks("0\r\n" + fields), response, true)
      case "http10-keepalive" =>
        val text = s"GET $target HTTP/1.0\r\nConnection: keep-alive\r\n\r\n"
        (text, Expected(200, target + "\n\n"), true)
      case "request-line-limit" =>
        val path = "/" + "x" * 114
        (s"GET $path HTTP/1.1\r\nHost: x\r\n\r\n", Expected(200, path + "\n\n"), true)
      case "conflicting-framing" => (head("Content-Length: 0\r\nTransfer-Encoding: chunked\r\n"), Expected(400), true)
      case "duplicate-length"    => (head("Content-Length: 0\r\nContent-Length: 0\r\n"), Expected(400), true)
      case "overflow-length"     => (head("Content-Length: 9223372036854775808\r\n"), Expected(400), true)
      case "signed-length"       =>
        val sign = if (random.nextBoolean()) "+" else "-"
        (head(s"Content-Length: $sign${random.nextInt(256)}\r\n"), Expected(400), true)
      case "list-length"    => (head("Content-Length: 0, 0\r\n"), Expected(400), true)
      case "missing-host"   => (s"GET $target HTTP/1.1\r\n\r\n", Expected(400), true)
      case "duplicate-host" => (head("Host: x\r\n"), Expected(400), true)
      case "bare-lf"        => (s"GET $target HTTP/1.1\nHost: x\n\n", Expected(400), true)
      case "obs-fold"       => (head("X: x\r\n folded\r\n"), Expected(400), true)
      case "invalid-field"  =>
        val invalid = Vector(' ', '\t', '(', ')', '@', '\u0000')(random.nextInt(6))
        (head(s"Bad${invalid}Name: x\r\n"), Expected(400), true)
      case "invalid-target"           => ("GET /bad%zz HTTP/1.1\r\nHost: x\r\n\r\n", Expected(400), true)
      case "unsupported-version"      => ("GET / HTTP/1.2\r\nHost: x\r\n\r\n", Expected(505), true)
      case "duplicate-chunked"        => (head("Transfer-Encoding: chunked, chunked\r\n"), Expected(400), true)
      case "chunked-not-last"         => (head("Transfer-Encoding: chunked, gzip\r\n"), Expected(400), true)
      case "unsupported-coding"       => (head("Transfer-Encoding: gzip, chunked\r\n"), Expected(501), true)
      case "invalid-chunk-size"       => (chunks("Z\r\n"), Expected(400), true)
      case "overflow-chunk-size"      => (chunks("8000000000000000\r\n"), Expected(400), true)
      case "chunk-delimiter"          => (chunks("1\r\nxXX"), Expected(400), true)
      case "chunk-extension"          => (chunks("0;=bad\r\n\r\n"), Expected(400), true)
      case "invalid-trailer"          => (chunks("0\r\nBad Name: x\r\n\r\n"), Expected(400), true)
      case "fixed-over-limit"         => (head("Content-Length: 257\r\n"), Expected(413), true)
      case "chunk-over-limit"         => (chunks("101\r\n"), Expected(413), true)
      case "request-line-over-limit"  => ("GET /" + "x" * 129 + " HTTP/1.1\r\nHost: x\r\n\r\n", Expected(414), true)
      case "header-line-over-limit"   => (head("X: " + "x" * 126 + "\r\n"), Expected(431), true)
      case "header-count-over-limit"  => (head("X: x\r\n" * 8), Expected(431), true)
      case "trailer-count-over-limit" => (chunks("0\r\n" + "X: x\r\n" * 5 + "\r\n"), Expected(400), true)
      case "trailer-line-over-limit"  => (chunks("0\r\nX: " + "x" * 62 + "\r\n\r\n"), Expected(400), true)
      case "truncated-head"           => (head("X: unfinished").dropRight(2), Expected(400), false)
      case "truncated-fixed"          => (head("Content-Length: 10\r\n") + "short", Expected(400), false)
      case "truncated-chunk"          => (chunks("5\r\nxx"), Expected(400), false)
      case "truncated-trailer"        => (chunks("0\r\nX: unfinished"), Expected(400), false)
      case "close-with-suffix"        =>
        val (text, response) = request(random, target, close = true, framing = random.nextInt(3))
        return Sample(name, prefix :+ step(random, text + sentinel, Vector(response), true))
    }
    val tail = if (suffix) wire + sentinel else wire
    val responses = if (expected.status == 200 && suffix) {
      Vector(expected, Expected(200, "/sentinel\n\n"))
    } else Vector(expected)
    Sample(name, prefix :+ step(random, tail, responses, true, halfClose = name.startsWith("truncated-")))
  }

  private def exercise(port: Int, sample: Sample, observed: ByteArrayOutputStream, campaignDeadline: Long): Unit = {
    val channel = SocketChannel.open()
    val socket = channel.socket()
    val deadline = math.min(campaignDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
    try {
      socket.setSendBufferSize(65536)
      socket.connect(new InetSocketAddress("127.0.0.1", port), 1000)
      socket.setTcpNoDelay(true)
      val input = socket.getInputStream
      var readBytes = 0
      def read(): Int = {
        val remaining = deadline - System.nanoTime()
        require(remaining > 0, "Exchange exceeded absolute deadline")
        require(readBytes < 65536, "Response exceeded 64 KiB budget")
        socket.setSoTimeout(math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)).toInt)
        val byte = input.read()
        if (byte >= 0) { observed.write(byte); readBytes += 1 }
        byte
      }
      def line(): String = {
        val text = new StringBuilder
        var byte = read()
        while (byte != '\r') {
          require(byte >= 0 && byte != '\n' && text.length < 2048, "Malformed or oversized response line")
          text.append(byte.toChar)
          byte = read()
        }
        require(read() == '\n', "Malformed response CRLF")
        text.toString
      }
      sample.steps.foreach { part =>
        val bytes = part.wire.getBytes(ISO_8859_1)
        require(bytes.length <= 16384, "Generated request exceeded write budget")
        try {
          send(channel, bytes, part.writes, deadline)
          if (part.halfClose) socket.shutdownOutput()
        } catch {
          // Early rejection may close while later fragments are still being sent.
          // The response and closure assertions below remain mandatory.
          case _: IOException if part.terminal && !Thread.currentThread().isInterrupted => ()
        }
        part.responses.zipWithIndex.foreach { case (expected, responseIndex) =>
          val status = line().split(" ", 3)
          require(status.length == 3 && Set("HTTP/1.0", "HTTP/1.1").contains(status(0)), "Malformed status line")
          require(
            status(1) == expected.status.toString,
            s"Expected ${expected.status}, received ${status.mkString(" ")}"
          )
          val fields = Vector.newBuilder[(String, String)]
          var field = line()
          var count = 0
          while (field.nonEmpty) {
            require(count < 32, "Too many response fields")
            val colon = field.indexOf(':')
            require(colon > 0, "Malformed response field")
            fields += field.take(colon).toLowerCase(java.util.Locale.ROOT) -> field.drop(colon + 1).trim
            count += 1
            field = line()
          }
          val headers = fields.result()
          val lengths = headers.collect { case ("content-length", value) => value }
          require(
            lengths.size == 1 && lengths.head.nonEmpty && lengths.head.forall(_.isDigit),
            "Expected one Content-Length"
          )
          require(!headers.exists(_._1 == "transfer-encoding"), "Unexpected response transfer coding")
          val closes = headers.exists { case (key, value) => key == "connection" && value.equalsIgnoreCase("close") }
          val terminalResponse = part.terminal && responseIndex == part.responses.size - 1
          require(closes == terminalResponse, "Unexpected response persistence policy")
          val length = lengths.head.toLong
          require(length == expected.body.length, s"Unexpected response body length: $length")
          val body = new StringBuilder
          for (_ <- 0 until length.toInt) {
            val byte = read()
            if (byte < 0) throw new EOFException("Truncated response body")
            body.append(byte.toChar)
          }
          require(body.toString == expected.body, "Response body, target, trailers or pipeline order differed")
        }
        if (part.terminal) {
          // Require immediate closure, rather than passing on the fixture's two-second idle timeout.
          val remaining = deadline - System.nanoTime()
          require(remaining > 0, "Exchange exceeded absolute deadline before closure")
          socket.setSoTimeout(math.max(1L, math.min(500L, TimeUnit.NANOSECONDS.toMillis(remaining))).toInt)
          val extra = try input.read()
          catch { case _: SocketException => -1 }
          if (extra >= 0) observed.write(extra)
          require(extra == -1, "Unexpected response/bytes after terminal exchange")
        }
      }
    } finally socket.close()
  }

  /** Socket SO_TIMEOUT only bounds reads; nonblocking writes also need the exchange deadline. */
  private def send(channel: SocketChannel, bytes: Array[Byte], writes: Vector[Int], deadline: Long): Unit = {
    channel.configureBlocking(false)
    val selector = Selector.open()
    try {
      channel.register(selector, SelectionKey.OP_WRITE)
      var offset = 0
      writes.foreach { size =>
        val buffer = ByteBuffer.wrap(bytes, offset, size)
        while (buffer.hasRemaining) {
          if (Thread.currentThread().isInterrupted) throw new InterruptedException("Fuzz write interrupted")
          val remaining = deadline - System.nanoTime()
          require(remaining > 0, "Write exceeded exchange deadline")
          if (channel.write(buffer) == 0) {
            selector.select(math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
            selector.selectedKeys().clear()
          }
        }
        offset += size
      }
    } finally {
      selector.close()
      channel.configureBlocking(true)
    }
  }
}
