import java.io.IOException
import java.net.{InetSocketAddress, Socket, SocketException, SocketTimeoutException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.util.control.NonFatal

/** Bounded loopback observations. Pressure is measured; recovery and cleanup are asserted.
  */
object SlowClients {
  val scenarios = Seq(
    "silent",
    "request-line",
    "headers",
    "idle-drip",
    "fixed-body",
    "chunk-data",
    "trailers",
    "response-reader",
    "shutdown-read",
    "shutdown-write"
  )

  def run(
      suite: String,
      source: Path,
      command: Seq[String],
      destination: Path,
      capacity: String,
      duration: Int,
      selected: Seq[String],
      timeout: Double,
      revision: String,
      idleDripExpectation: String
  ): Int = {
    val modes = {
      if (capacity == "both") Seq("roomy", "limited") else Seq(capacity)
    }
    val names = {
      if (suite == "slowhttptest") Seq("H", "B", "X")
      else if (selected.isEmpty) scenarios
      else selected.distinct
    }
    val results = ujson.Arr()
    val report = ujson.Obj(
      "suite" -> suite,
      "revision" -> revision,
      "server" -> ujson.Arr.from(command),
      "duration_seconds" -> duration,
      "connections" -> 4,
      "admitted_limit" -> "roomy=128,limited=2",
      "queue_capacity" -> 0,
      "probe_deadline_ms" -> 500,
      "idle_drip_expectation" -> idleDripExpectation,
      "results" -> results
    )
    try {
      for (mode <- modes; name <- names) {
        val directory = {
          Files.createDirectory(destination.resolve(s"$mode-$name"))
        }
        val result = {
          ujson.Obj("capacity" -> mode, "scenario" -> name, "complete" -> false)
        }
        results.value += result
        try {
          observe(
            suite,
            source,
            command,
            directory,
            mode,
            name,
            duration,
            timeout,
            result,
            idleDripExpectation
          )
          result("complete") = true
        } catch {
          case NonFatal(error) =>
            result("error") = error.toString
            System.err.println(s"$mode/$name failed: $error")
        }
        Files.writeString(
          destination.resolve("run.json"),
          ujson.write(report, indent = 2) + "\n"
        )
        System.err.println(
          s"$mode/$name: complete=${result("complete")}; results in $directory"
        )
      }
      if (results.value.forall(_("complete").bool)) 0 else 1
    } finally {
      Files.writeString(
        destination.resolve("run.json"),
        ujson.write(report, indent = 2) + "\n"
      )
    }
  }

  private def observe(
      suite: String,
      source: Path,
      command: Seq[String],
      directory: Path,
      mode: String,
      name: String,
      duration: Int,
      timeout: Double,
      result: ujson.Obj,
      idleDripExpectation: String
  ): Unit = {
    val stopFile = directory.resolve("stop").toAbsolutePath
    val log = directory.resolve("server.log")
    val server = ConformanceRunner.Children.start(
      command,
      log,
      Map(
        "HTTP_CONFORMANCE_PROFILE" -> suite,
        "HTTP_CONFORMANCE_CAPACITY" -> mode,
        "HTTP_CONFORMANCE_STOP_FILE" -> stopFile.toString
      )
    )
    val sockets = mutable.ArrayBuffer.empty[Socket]
    var external: Option[Process] = None
    val samples = ujson.Arr()
    val probes = ujson.Arr()
    val writeCase = Set("response-reader", "shutdown-write", "X").contains(name)
    result("samples") = samples
    result("probes") = probes
    result("pid") = server.pid().toDouble
    try {
      val port = ConformanceRunner.awaitPort(server, log).toInt
      result("port") = port
      require(probe(port)("ok").bool, "Ordinary request failed before pressure")
      samples.value += resources(server.pid(), "before")
      if (suite == "slowhttptest") {
        val path = if (name == "X") "/large" else "/body"
        val options = {
          if (name == "X") Seq("-w", "512", "-y", "1024", "-n", "1", "-z", "32")
          else Seq("-i", "1", "-x", "1")
        }
        val probeCommand = Seq(
          source.resolve("src/slowhttptest").toAbsolutePath.toString,
          s"-$name",
          "-c",
          "4",
          "-r",
          "4",
          "-l",
          duration.toString,
          "-p",
          "1",
          "-s",
          "1048576",
          "-t",
          (if (name == "B") "POST" else "GET"),
          "-u",
          s"http://127.0.0.1:$port$path",
          "-g",
          "-o",
          directory.resolve("tool").toString
        ) ++ options
        result("command") = ujson.Arr.from(probeCommand)
        external = Some(
          ConformanceRunner.Children
            .start(probeCommand, directory.resolve("tool.log"))
        )
      } else {
        val count = if (name == "idle-drip") 1 else 4
        for (_ <- 0 until count) {
          val socket = connect(
            port,
            smallReceiveBuffer = name.contains("write") || name == "response-reader"
          )
          sockets += socket
          if (name == "idle-drip") {
            send(socket, "GET /probe HTTP/1.1\r\nHost: localhost\r\n\r\n")
            require(
              readResponse(socket, close = false),
              "Idle preflight connection closed without a response"
            )
          }
          val initial = name match {
            case "silent"                => ""
            case "request-line"          => "GET /"
            case "headers" | "idle-drip" =>
              "GET / HTTP/1.1\r\nHost: localhost\r\nX-Drip: "
            case "fixed-body" | "shutdown-read" =>
              "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\n\r\nx"
            case "chunk-data" =>
              "POST /body HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n100000\r\nx"
            case "trailers" =>
              "POST /body HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer: "
            case "response-reader" | "shutdown-write" =>
              "GET /large HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
          }
          send(socket, initial)
        }
        result("opened_connections") = count
      }
      val started = System.nanoTime()
      val initialState = if (writeCase) "unread" else "waiting"
      val states = sockets.map(_ => ujson.Obj("state" -> initialState))
      result("pressure_connections") = ujson.Arr.from(states)
      def sampleConnections(): Unit = if (!writeCase) {
        sockets.zip(states).foreach { case (socket, state) =>
          if (state("state").str == "waiting") {
            socket.setSoTimeout(1)
            val closed = try {
              val first = socket.getInputStream.read()
              if (first == -1) true
              else {
                state("state") = "data"
                state("first_byte") = first
                false
              }
            } catch {
              case _: SocketTimeoutException => false
              case error: SocketException    =>
                state("close_error") = error.toString
                true
            }
            if (closed) {
              state("state") = "closed"
              state("closed_observed_ms") = (System.nanoTime() - started) / 1e6
            }
          }
        }
      }
      val seconds = if (name == "idle-drip") 32 else duration
      val deadline = started + TimeUnit.SECONDS.toNanos(seconds.toLong)
      val toolDeadline = {
        started + (math.min(timeout, duration + 5.0) * 1000000000).toLong
      }
      var nextDrip = started
      var dripFailures = 0
      var pressure = true
      while (pressure) {
        require(server.isAlive, "Server exited during pressure")
        if (
          Set("headers", "idle-drip", "request-line")
            .contains(name) && System.nanoTime() >= nextDrip
        ) {
          sockets.zip(states).foreach { case (socket, state) =>
            if (state("state").str == "waiting") {
              try send(socket, "x")
              catch { case _: IOException => dripFailures += 1 }
            }
          }
          nextDrip = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        }
        probes.value += probe(port)
        sampleConnections()
        samples.value += resources(server.pid(), "pressure")
        Thread.sleep(250)
        pressure = external match {
          case Some(process) =>
            require(
              System.nanoTime() < toolDeadline || !process.isAlive,
              "slowhttptest exceeded deadline"
            )
            process.isAlive
          case None => System.nanoTime() < deadline
        }
      }
      result("observed_seconds") = (System.nanoTime() - started) / 1e9
      result("drip_write_failures") = dripFailures
      sampleConnections()
      external.foreach { process =>
        result("tool_exit_code") = process.exitValue()
        require(process.exitValue() == 0, "slowhttptest failed; see tool.log")
        require(
          Files.isRegularFile(directory.resolve("tool.csv")),
          "slowhttptest produced no CSV"
        )
        ConformanceRunner.Children.stop(process)
      }
      val text = Files.readString(log)
      val bodyCase = {
        Set("fixed-body", "chunk-data", "trailers", "shutdown-read", "B")
          .contains(name)
      }
      if (bodyCase) {
        require(text.contains("HANDLING /body"), "Body handler was not reached")
      }
      if (writeCase) {
        val begun = "LARGE_STARTED".r.findAllIn(text).length
        val finished = "LARGE_FINISHED".r.findAllIn(text).length
        result("large_started") = begun
        result("large_finished") = finished
        require(begun > finished, "No unfinished large response observed")
      }
      result("admission_saturation_observed") = text.contains("WORKERS active=2")
      if (mode == "limited" && suite == "slowclients" && (bodyCase || writeCase)) {
        require(
          result("admission_saturation_observed").bool,
          "Admitted worker saturation was not reached"
        )
      }
      if (name == "idle-drip") {
        val state = states.head
        if (state("state").str == "data") {
          val rejection = {
            readTimeoutRejection(sockets.head, state("first_byte").num.toInt)
          }
          state("timeout_response") = rejection
          state("state") = "closed"
        }
        val survived = {
          if (state("state").str == "closed") false
          else {
            try {
              send(sockets.head, "\r\nConnection: close\r\n\r\n")
              readResponse(sockets.head, close = true)
            } catch {
              case error: SocketException =>
                state("close_error") = error.toString
                false
            }
          }
        }
        val outcome = if (survived) "survived" else "closed"
        if (!survived && state("state").str != "closed") {
          state("state") = "closed"
          state("closed_observed_ms") = (System.nanoTime() - started) / 1e6
        }
        result("idle_drip_outcome") = outcome
        result("dripped_connection_survived") = survived
        require(
          idleDripExpectation != "close" || !survived,
          "Expected dripped connection to close within the observation window"
        )
        require(
          idleDripExpectation != "survive" || survived,
          "Expected dripped connection to survive the observation window"
        )
      }
      if (name.startsWith("shutdown-")) {
        gracefulStop(server, stopFile, log, port, result)
      } else {
        sockets.foreach(_.close())
        val recoveryStart = System.nanoTime()
        val recovery = ujson.Arr()
        var recovered = false
        while (
          !recovered && System.nanoTime() - recoveryStart < TimeUnit.SECONDS
            .toNanos(5)
        ) {
          val attempt = probe(port)
          recovery.value += attempt
          recovered = attempt("ok").bool
          if (!recovered) Thread.sleep(100)
        }
        result("recovery_probes") = recovery
        result("recovery_ms") = (System.nanoTime() - recoveryStart) / 1e6
        require(recovered, "Ordinary requests did not recover after disconnect")
        samples.value += resources(server.pid(), "recovered")
        gracefulStop(server, stopFile, log, port, result)
      }
    } finally {
      sockets.foreach(socket => {
        try socket.close()
        catch { case _: IOException => () }
      })
      external.foreach(ConformanceRunner.Children.stop)
      ConformanceRunner.Children.stop(server)
    }
  }

  def gracefulStop(
      server: Process,
      stop: Path,
      log: Path,
      port: Int,
      result: ujson.Obj
  ): Unit = {
    val started = System.nanoTime()
    Files.writeString(stop, "stop\n")
    require(
      server.waitFor(5, TimeUnit.SECONDS),
      "Fixture did not stop within five seconds"
    )
    result("shutdown_ms") = (System.nanoTime() - started) / 1e6
    result("server_exit_code") = server.exitValue()
    require(server.exitValue() == 0, "Fixture exited abnormally")
    require(
      Files.readString(log).contains("STOPPED millis="),
      "Fixture did not report completed stop"
    )
    require(
      !probe(port)("ok").bool,
      "Listener still accepts requests after stop"
    )
  }

  private def connect(
      port: Int,
      smallReceiveBuffer: Boolean = false
  ): Socket = {
    val socket = new Socket()
    try {
      if (smallReceiveBuffer) socket.setReceiveBufferSize(1024)
      socket.connect(new InetSocketAddress("127.0.0.1", port), 500)
      socket.setSoTimeout(500)
      socket
    } catch {
      case error: Throwable => socket.close(); throw error
    }
  }

  private def send(socket: Socket, text: String): Unit = {
    socket.getOutputStream.write(text.getBytes(US_ASCII))
    socket.getOutputStream.flush()
  }

  /** A deadline rejection is valid before a complete request head. Accept only an exact bodyless 408 followed by EOF;
    * premature success still fails.
    */
  private def readTimeoutRejection(socket: Socket, first: Int): String = {
    val bytes = new java.io.ByteArrayOutputStream
    bytes.write(first)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
    var ended = false
    while (!ended && bytes.size() < 8192) {
      val left = deadline - System.nanoTime()
      require(left > 0, "Timeout rejection did not close within probe deadline")
      socket.setSoTimeout(
        math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)).toInt
      )
      val byte = socket.getInputStream.read()
      if (byte == -1) ended = true else bytes.write(byte)
    }
    val text = bytes.toString("US-ASCII")
    val lines = text.split("\r\n", -1).toVector
    val fields = lines.drop(1).filter(_.nonEmpty).map { line =>
      val colon = line.indexOf(':')
      require(colon > 0, "Malformed timeout response header")
      line.take(colon).toLowerCase -> line.drop(colon + 1).trim
    }
    require(
      ended && text.startsWith("HTTP/1.1 408 ") && text.endsWith("\r\n\r\n") &&
        fields.count(_._1 == "content-length") == 1 && fields.contains(
          "content-length" -> "0"
        ) &&
        !fields.exists(_._1 == "transfer-encoding") && fields.contains(
          "connection" -> "close"
        ),
      "Expected complete bodyless 408 and connection closure before request completion"
    )
    text
  }

  private def readResponse(
      socket: Socket,
      close: Boolean,
      deadline: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
  ): Boolean = {
    val input = socket.getInputStream
    val bytes = new java.io.ByteArrayOutputStream()
    var done = false
    while (!done && bytes.size() < 8192) {
      val left = deadline - System.nanoTime()
      require(left > 0, "Response exceeded absolute probe deadline")
      socket.setSoTimeout(
        math.max(1, TimeUnit.NANOSECONDS.toMillis(left).toInt)
      )
      val value = try input.read()
      catch { case _: SocketException if bytes.size() == 0 => -1 }
      if (value == -1) done = true
      else bytes.write(value)
      val text = bytes.toString("US-ASCII")
      if (!close && text.endsWith("\r\n\r\nOK")) done = true
    }
    val text = bytes.toString("US-ASCII")
    if (done && bytes.size() == 0) return false
    require(
      text.startsWith("HTTP/1.1 200 ") && text.endsWith("\r\n\r\nOK"),
      "Expected complete 200 response with body OK"
    )
    true
  }

  private def probe(port: Int): ujson.Obj = {
    val started = System.nanoTime()
    val result = ujson.Obj("ok" -> false)
    try {
      val socket = connect(port)
      try {
        send(
          socket,
          "GET /probe HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        val responded = readResponse(
          socket,
          close = true,
          deadline = started + TimeUnit.MILLISECONDS.toNanos(500)
        )
        require(responded, "Connection closed without a response")
        result("ok") = true
      } finally socket.close()
    } catch { case NonFatal(error) => result("error") = error.toString }
    result("latency_ms") = (System.nanoTime() - started) / 1e6
    result
  }

  def resources(pid: Long, phase: String): ujson.Obj = {
    val root = Path.of(s"/proc/$pid")
    val status = Files.readString(root.resolve("status"))
    val result = ujson.Obj(
      "phase" -> phase,
      "time_ms" -> System.currentTimeMillis().toDouble
    )
    Seq("VmRSS" -> "rss_kib", "Threads" -> "threads").foreach { case (field, key) =>
      val pattern = ("(?m)^" + field + ":\\s+(\\d+)").r
      result(key) = pattern
        .findFirstMatchIn(status)
        .getOrElse(
          throw new IllegalStateException(
            "Missing process resource field: " + field
          )
        )
        .group(1)
        .toDouble
    }
    val files = Files.list(root.resolve("fd"))
    try result("fds") = files.count().toDouble
    finally files.close()
    result
  }
}
