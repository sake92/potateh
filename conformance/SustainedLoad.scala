import java.io.{ByteArrayOutputStream, EOFException, IOException}
import java.net.{InetSocketAddress, SocketException, SocketTimeoutException}
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, LongAdder}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Bounded mixed traffic against the real fixture. All sockets, client threads and fixture processes are owned. */
object SustainedLoad {
  private val roles = Vector("ordinary", "slow-head", "upload", "download")
  private val pressures = Vector("body", "reader", "headers", "trailers")
  private val payload = "s" * 4096

  private final class Counts {
    val attempts = new LongAdder
    val passed = new LongAdder
    val rejected = new LongAdder
    val maxMillis = new AtomicLong
    def json: ujson.Obj = ujson.Obj(
      "attempts" -> attempts.sum().toDouble,
      "passed" -> passed.sum().toDouble,
      "transport_failures" -> rejected.sum().toDouble,
      "max_ms" -> maxMillis.get().toDouble
    )
  }

  def run(
      command: Seq[String],
      destination: Path,
      capacity: String,
      seconds: Int,
      connections: Int,
      revision: String
  ): Int = {
    val results = ujson.Arr()
    val report = ujson.Obj(
      "suite" -> "sustained",
      "revision" -> revision,
      "server" -> ujson.Arr.from(command),
      "duration_seconds" -> seconds,
      "pressure_clients" -> connections,
      "legitimate_clients" -> roles.size,
      "fixture_timeouts_ms" -> ujson.Obj("head" -> 1000, "body_read" -> 1000, "write" -> 1000),
      "results" -> results
    )
    val modes = if (capacity == "both") Vector("roomy", "limited") else Vector(capacity)
    try {
      modes.foreach { mode =>
        val directory = Files.createDirectory(destination.resolve(mode))
        val result = {
          ujson.Obj("capacity" -> mode, "connection_limit" -> (if (mode == "limited") 2 else 128), "complete" -> false)
        }
        results.value += result
        try {
          observe(command, directory, mode, seconds, connections, result)
          result("complete") = true
        } catch {
          case NonFatal(error) =>
            result("error") = error.toString
            System.err.println(s"sustained/$mode failed: $error")
        }
        Files.writeString(destination.resolve("run.json"), ujson.write(report, indent = 2) + "\n")
        System.err.println(s"sustained/$mode: complete=${result("complete")}; results in $directory")
      }
      if (results.value.forall(_("complete").bool)) 0 else 1
    } finally Files.writeString(destination.resolve("run.json"), ujson.write(report, indent = 2) + "\n")
  }

  private def observe(
      command: Seq[String],
      directory: Path,
      mode: String,
      seconds: Int,
      connections: Int,
      result: ujson.Obj
  ): Unit = {
    val stopFile = directory.resolve("stop").toAbsolutePath
    val log = directory.resolve("server.log")
    val server = ConformanceRunner.Children.start(
      command,
      log,
      Map(
        "HTTP_CONFORMANCE_PROFILE" -> "sustained",
        "HTTP_CONFORMANCE_CAPACITY" -> mode,
        "HTTP_CONFORMANCE_STOP_FILE" -> stopFile.toString
      )
    )
    val open = ConcurrentHashMap.newKeySet[SocketChannel]()
    val stopped = new AtomicBoolean
    val threads = mutable.ArrayBuffer.empty[Thread]
    val errors = new ConcurrentLinkedQueue[String]
    val healthy = roles.map(_ -> new Counts).toMap
    val pressure = pressures.map(_ -> new Counts).toMap
    val samples = ujson.Arr()
    val spareCapacity = mode == "roomy" && connections + roles.size <= 128
    var clientsClosed = false
    result("samples") = samples
    result("progress_required") = spareCapacity
    def finishClients(): Unit = if (!clientsClosed) {
      clientsClosed = true
      stopClients(stopped, open, threads.toVector)
      result("client_threads_stopped") = true
    }
    def snapshot(): Unit = {
      val resources = SlowClients.resources(server.pid(), "pressure")
      resources("legitimate") = ujson.Obj.from(roles.map(name => name -> healthy(name).json))
      resources("pressure") = ujson.Obj.from(pressures.map(name => name -> pressure(name).json))
      samples.value += resources
    }
    def launch(name: String)(operation: => Unit): Unit = {
      val thread = Thread
        .ofVirtual()
        .name("sustained-" + name)
        .unstarted(() => {
          try operation
          catch {
            case _: InterruptedException if stopped.get() => ()
            case _: IOException if stopped.get()          => ()
            case NonFatal(error)                          => errors.add(name + ": " + error.toString)
          }
        })
      threads += thread
      thread.start()
    }
    try {
      val port = ConformanceRunner.awaitPort(server, log).toInt
      result("port") = port
      // Prove every path works before saturation can legitimately deny admission.
      roles.foreach { role =>
        for (variant <- 0 until 2) transaction(port, role, variant, open)
      }
      result("preflight") = true
      samples.value += SlowClients.resources(server.pid(), "before")
      val started = System.nanoTime()
      val deadline = started + TimeUnit.SECONDS.toNanos(seconds.toLong)
      for (index <- 0 until connections) {
        if (index == 2) {
          // Admit a body reader and blocked response writer before competing clients can fill the limit.
          val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
          var admitted = false
          while (!admitted && System.nanoTime() < admissionDeadline) {
            val text = Files.readString(log)
            admitted = text.contains("WORKERS active=2") && text.contains("LARGE_STARTED")
            if (!admitted) Thread.sleep(25)
          }
          require(admitted, "Initial body/write pressure was not admitted")
        }
        val name = pressures(index % pressures.size)
        launch(s"$name-$index") {
          while (!stopped.get()) {
            val counts = pressure(name)
            counts.attempts.increment()
            try {
              press(port, name, open)
              counts.passed.increment()
            } catch { case _: IOException if !stopped.get() => counts.rejected.increment() }
            Thread.sleep(100)
          }
        }
      }
      roles.foreach { role =>
        launch(role) {
          var iteration = 0
          while (!stopped.get()) {
            val counts = healthy(role)
            val before = System.nanoTime()
            counts.attempts.increment()
            try {
              transaction(port, role, iteration, open)
              counts.passed.increment()
              val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
              counts.maxMillis.accumulateAndGet(millis, (a, b) => math.max(a, b))
            } catch { case _: IOException if !stopped.get() => counts.rejected.increment() }
            iteration += 1
            Thread.sleep(50)
          }
        }
      }
      var nextSample = started
      var nextProgress = started + TimeUnit.SECONDS.toNanos(10)
      var previous = roles.map(_ -> 0L).toMap
      while (System.nanoTime() < deadline) {
        require(server.isAlive, "Fixture exited during sustained load")
        require(errors.isEmpty, errors.asScala.mkString("; "))
        if (System.nanoTime() >= nextSample) {
          snapshot()
          nextSample += TimeUnit.SECONDS.toNanos(1)
        }
        if (System.nanoTime() >= nextProgress && nextProgress < deadline) {
          val current = roles.map(name => name -> healthy(name).passed.sum()).toMap
          if (spareCapacity) {
            require(
              roles.forall(name => current(name) > previous(name)),
              "Legitimate traffic stopped progressing with spare capacity"
            )
          }
          previous = current
          nextProgress += TimeUnit.SECONDS.toNanos(10)
          System.err.println(
            s"sustained/$mode: ${TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)}/$seconds seconds"
          )
        }
        Thread.sleep(100)
      }
      result("observed_seconds") = (System.nanoTime() - started) / 1e9
      finishClients()
      require(errors.isEmpty, errors.asScala.mkString("; "))
      result("legitimate") = ujson.Obj.from(roles.map(name => name -> healthy(name).json))
      result("pressure") = ujson.Obj.from(pressures.map(name => name -> pressure(name).json))
      pressures.foreach { name =>
        val initialClients = (0 until connections).count(index => pressures(index % pressures.size) == name)
        require(pressure(name).attempts.sum() > initialClients, s"Pressure clients did not renew: $name")
      }
      if (spareCapacity) {
        require(roles.forall(name => healthy(name).passed.sum() > 0), "A legitimate role never succeeded")
        if (seconds % 10 == 0) {
          require(
            roles.forall(name => healthy(name).passed.sum() > previous(name)),
            "Legitimate traffic stopped in the final progress window"
          )
        }
      }
      val fixtureLog = Files.readString(log)
      require(
        fixtureLog.contains("HANDLING /body") && fixtureLog.contains("LARGE_STARTED"),
        "Pressure never reached body/write workers"
      )
      val saturation = fixtureLog.contains("WORKERS active=2")
      result("two_active_workers_observed") = saturation
      if (mode == "limited") require(saturation, "Limited-capacity worker saturation was not observed")
      // Retry only transport/admission failures while old connections finish closing.
      val recoveryStart = System.nanoTime()
      val recoveryDeadline = recoveryStart + TimeUnit.SECONDS.toNanos(5)
      var recovered = false
      while (!recovered && System.nanoTime() < recoveryDeadline) {
        try {
          transaction(port, "ordinary", 0, open)
          recovered = true
        } catch { case _: IOException => Thread.sleep(100) }
      }
      require(
        recovered && System.nanoTime() <= recoveryDeadline,
        "Ordinary traffic did not recover within five seconds"
      )
      result("ordinary_recovery_ms") = (System.nanoTime() - recoveryStart) / 1e6
      roles.filterNot(_ == "ordinary").foreach { role =>
        for (variant <- 0 until 2) transaction(port, role, variant, open)
      }
      result("mixed_recovery") = true
      samples.value += SlowClients.resources(server.pid(), "recovered")
      SlowClients.gracefulStop(server, stopFile, log, port, result)
    } finally {
      try finishClients()
      finally {
        var interrupted = Thread.interrupted()
        try {
          // Failed/interrupted runs use file shutdown too; TERM remains a bounded fallback.
          if (server.isAlive) {
            Files.writeString(stopFile, "stop\n")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (server.isAlive && System.nanoTime() < deadline) {
              val left = math.max(1L, deadline - System.nanoTime())
              try server.waitFor(left, TimeUnit.NANOSECONDS)
              catch { case _: InterruptedException => interrupted = true }
            }
          }
          result("fixture_stopped") = !server.isAlive
        } finally {
          try ConformanceRunner.Children.stop(server)
          finally if (interrupted) Thread.currentThread().interrupt()
        }
      }
    }
  }

  private def stopClients(stopped: AtomicBoolean, open: java.util.Set[SocketChannel], threads: Vector[Thread]): Unit = {
    stopped.set(true)
    var interrupted = Thread.interrupted()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    threads.foreach(_.interrupt())
    open.asScala.foreach(channel => {
      try channel.close()
      catch { case _: IOException => () }
    })
    threads.foreach { thread =>
      while (thread.isAlive && System.nanoTime() < deadline) {
        try thread.join(math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
        catch { case _: InterruptedException => interrupted = true }
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
    require(threads.forall(!_.isAlive), "Client threads did not stop within five seconds")
  }

  private def withPeer[A](port: Int, open: java.util.Set[SocketChannel])(operation: Peer => A): A = {
    val channel = SocketChannel.open()
    open.add(channel)
    try {
      channel.socket().connect(new InetSocketAddress("127.0.0.1", port), 500)
      channel.socket().setTcpNoDelay(true)
      operation(new Peer(channel))
    } finally {
      try channel.close()
      finally open.remove(channel)
    }
  }

  private def transaction(port: Int, role: String, iteration: Int, open: java.util.Set[SocketChannel]): Unit = {
    withPeer(port, open) { peer =>
      role match {
        case "ordinary" =>
          // Eight exchanges share a connection, exercising reuse throughout the campaign.
          for (i <- 0 until 8) {
            val connection = if (i == 7) "Connection: close\r\n" else ""
            peer.send(s"GET /probe HTTP/1.1\r\nHost: x\r\n$connection\r\n")
            peer.response("OK")
          }
        case "slow-head" =>
          peer.send("GET /probe HTTP/1.1\r\n")
          Thread.sleep(200)
          peer.send("Host: x\r\n")
          Thread.sleep(200)
          peer.send("Connection: close\r\n\r\n")
          peer.response("OK")
        case "upload" =>
          val chunked = iteration % 2 == 1
          val framing = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: 4096"
          peer.send(s"POST /echo HTTP/1.1\r\nHost: x\r\n$framing\r\nConnection: close\r\n\r\n")
          for (_ <- 0 until 16) {
            val block = "s" * 256
            val wire = if (chunked) "100\r\n" + block + "\r\n" else block
            peer.send(wire)
            Thread.sleep(100)
          }
          if (chunked) peer.send("0\r\n\r\n")
          peer.response(payload)
        case "download" =>
          val path = if (iteration % 2 == 0) "/stream-fixed" else "/stream-chunked"
          peer.send(s"GET $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
          peer.response(payload, slow = true)
      }
      peer.closed()
    }
  }

  private def press(port: Int, name: String, open: java.util.Set[SocketChannel]): Unit = withPeer(port, open) { peer =>
    val initial = name match {
      case "headers"  => "GET /probe HTTP/1.1\r\nHost: x\r\nX-Drip: "
      case "body"     => "POST /body HTTP/1.1\r\nHost: x\r\nContent-Length: 4096\r\n\r\nx"
      case "trailers" => "POST /body HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Trailer: "
      case "reader"   => "GET /large HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
    }
    peer.send(initial)
    for (_ <- 0 until 12) {
      Thread.sleep(250)
      if (name == "headers" || name == "body") peer.send("x")
    }
  }

  private final class Peer(channel: SocketChannel) {
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    private val socket = channel.socket()
    private val input = socket.getInputStream
    private var received = 0
    def send(text: String): Unit = {
      channel.configureBlocking(false)
      val selector = Selector.open()
      try {
        channel.register(selector, SelectionKey.OP_WRITE)
        val bytes = ByteBuffer.wrap(text.getBytes(US_ASCII))
        while (bytes.hasRemaining) {
          val left = remaining()
          if (channel.write(bytes) == 0) {
            selector.select(math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)))
            selector.selectedKeys().clear()
          }
        }
      } finally {
        selector.close()
        channel.configureBlocking(true)
      }
    }
    private def remaining(): Long = {
      if (Thread.currentThread().isInterrupted) throw new InterruptedException("Client cancelled")
      val left = deadline - System.nanoTime()
      if (left <= 0) throw new SocketTimeoutException("Client exchange deadline exceeded")
      left
    }
    private def read(): Int = {
      val left = remaining()
      require(received < 65536, "Response exceeded 64 KiB budget")
      socket.setSoTimeout(math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)).toInt)
      val byte = input.read()
      if (byte < 0) throw new EOFException("Connection closed before the response was complete")
      received += 1
      byte
    }
    private def line(): String = {
      val text = new StringBuilder
      var byte = read()
      while (byte != '\r') {
        require(byte != '\n' && text.length < 2048, "Malformed response line")
        text.append(byte.toChar)
        byte = read()
      }
      require(read() == '\n', "Malformed response CRLF")
      text.toString
    }
    def response(expected: String, slow: Boolean = false): Unit = {
      val status = line().split(" ", 3)
      require(status.length == 3 && status(0) == "HTTP/1.1" && status(1) == "200", "Unexpected response status")
      val fields = Vector.newBuilder[(String, String)]
      var field = line()
      var count = 0
      while (field.nonEmpty) {
        val colon = field.indexOf(':')
        require(colon > 0 && count < 32, "Malformed response headers")
        fields += field.take(colon).toLowerCase(java.util.Locale.ROOT) -> field.drop(colon + 1).trim
        count += 1
        field = line()
      }
      val headers = fields.result()
      val body = new ByteArrayOutputStream
      def fixed(length: Int): Unit = {
        require(length >= 0 && length <= expected.length - body.size(), "Unexpected response body length")
        for (_ <- 0 until length) {
          body.write(read())
          if (slow && body.size() % 256 == 0) Thread.sleep(100)
        }
      }
      val encodings = headers.collect { case ("transfer-encoding", value) => value }
      val lengths = headers.collect { case ("content-length", value) => value }
      if (encodings.nonEmpty) {
        require(encodings == Vector("chunked") && lengths.isEmpty, "Ambiguous response framing")
        var size = Integer.parseInt(line(), 16)
        while (size != 0) {
          fixed(size)
          require(line().isEmpty, "Malformed chunk delimiter")
          size = Integer.parseInt(line(), 16)
        }
        require(line().isEmpty, "Unexpected response trailers")
      } else {
        require(lengths.size == 1 && lengths.head == expected.length.toString, "Unexpected Content-Length")
        fixed(expected.length)
      }
      require(body.toString("US-ASCII") == expected, "Response body was corrupted")
    }
    def closed(): Unit = {
      socket.setSoTimeout(math.max(1L, math.min(500L, TimeUnit.NANOSECONDS.toMillis(remaining()))).toInt)
      val byte = try input.read()
      catch { case _: SocketException => -1 }
      require(byte == -1, "Unexpected response after connection close")
    }
  }

}
