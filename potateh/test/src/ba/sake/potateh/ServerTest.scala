package ba.sake.potateh

import java.io.{ByteArrayOutputStream, IOException, InputStream, OutputStream}
import java.net.{ConnectException, InetSocketAddress, Socket, SocketException, SocketTimeoutException}
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, Executor, Executors, RejectedExecutionException, TimeUnit}

import scala.concurrent.duration.*

/** Real-wire adaptations of upstream HttpServerTest/HttpServerContractTest. */
class ServerTest extends munit.FunSuite {
  override val munitTimeout = 60.seconds
  private val config = ServerConfig.Default
    .withOnError(_ => ())
    .withBindAddress(new InetSocketAddress("127.0.0.1", 0))
    .withTimeouts(
      HttpTimeouts.Default
        .withIdleTimeout(500.millis)
        .withRequestHeadTimeout(500.millis)
        .withBodyReadTimeout(500.millis)
        .withWriteTimeout(500.millis)
        .withShutdownGrace(1.second)
    )

  private def response(text: String): Response = {
    Response(body = ResponseBody.bytes(text.getBytes(ISO_8859_1)))
  }

  private def withServer[A](
      settings: ServerConfig = config
  )(handle: Request => Response)(test: Server => A): A = {
    val server = Server.start(settings)(handle)
    try test(server)
    finally server.stop()
  }

  private def withSocket[A](server: Server)(test: Socket => A): A = {
    val socket = new Socket()
    try {
      socket.connect(server.address, 1000)
      socket.setSoTimeout(3000)
      test(socket)
    } finally socket.close()
  }

  private def send(socket: Socket, wire: String): Unit = {
    socket.getOutputStream.write(wire.getBytes(ISO_8859_1))
    socket.getOutputStream.flush()
  }

  private def line(input: InputStream): String = {
    val out = new StringBuilder
    var byte = input.read()
    while (byte != '\r') {
      if (byte == -1 || byte == '\n') {
        throw new IOException("Truncated or malformed response")
      }
      out.append(byte.toChar)
      byte = input.read()
    }
    if (input.read() != '\n') throw new IOException("Bad CRLF")
    out.toString
  }

  private case class Received(status: Int, headers: Headers, body: String)

  private def receive(socket: Socket, headOnly: Boolean = false): Received = {
    val input = socket.getInputStream
    val status = line(input).split(" ")(1).toInt
    val fields = Vector.newBuilder[(name: String, value: String)]
    var field = line(input)
    while (field.nonEmpty) {
      val colon = field.indexOf(':')
      fields += field.take(colon) -> field.drop(colon + 1).trim
      field = line(input)
    }
    val headers = Headers.from(fields.result())
    val out = new ByteArrayOutputStream
    def fixed(size: Int): Unit = (0 until size).foreach { _ =>
      val byte = input.read()
      if (byte == -1) throw new IOException("Truncated payload")
      out.write(byte)
    }
    if (!headOnly && status >= 200 && !Set(204, 205, 304).contains(status)) {
      if (headers.first("Transfer-Encoding").contains("chunked")) {
        var size = Integer.parseInt(line(input), 16)
        while (size > 0) {
          fixed(size)
          assertEquals(line(input), "")
          size = Integer.parseInt(line(input), 16)
        }
        assertEquals(line(input), "")
      } else {
        headers.first("Content-Length") match {
          case Some(size) => fixed(size.toInt)
          case None       =>
            var byte = input.read()
            while (byte != -1) {
              out.write(byte)
              byte = input.read()
            }
        }
      }
    }
    Received(status, headers, new String(out.toByteArray, ISO_8859_1))
  }

  private def get(
      target: String = "/",
      version: String = "HTTP/1.1",
      fields: String = ""
  ): String = {
    s"GET $target $version\r\nHost: localhost\r\n$fields\r\n"
  }

  private def closed(socket: Socket): Unit = {
    val actual = try socket.getInputStream.read()
    catch { case _: SocketException => -1 }
    assertEquals(actual, -1)
  }

  private def admissionRejected(server: Server): Unit = {
    val socket = new Socket()
    try {
      // Native's timed connect may observe the peer's immediate close as refusal.
      // Only intentional admission rejections allow this; ordinary connections must still succeed.
      val connected = try {
        socket.connect(server.address, 1000)
        true
      } catch { case _: ConnectException => false }
      if (connected) {
        socket.setSoTimeout(3000)
        closed(socket)
      }
    } finally socket.close()
  }

  private def await(latch: CountDownLatch): Unit = {
    val completed = latch.await(3, TimeUnit.SECONDS)
    assert(completed)
  }

  test("stop terminates default executor workers") {
    val worker = new AtomicReference[Thread]
    val server = Server.start(config) { _ =>
      worker.set(Thread.currentThread())
      response("ok")
    }
    try {
      withSocket(server) { socket =>
        send(socket, get(fields = "Connection: close\r\n"))
        assertEquals(receive(socket).body, "ok")
      }
      server.stop()
      worker.get().join(1000)
      assert(!worker.get().isAlive)
    } finally server.stop()
  }

  test("supplied executor schedules callbacks and remains usable after stop") {
    val worker = new AtomicReference[Thread]
    val pool = Executors.newSingleThreadExecutor(task => {
      val thread = new Thread(task, "application-worker")
      worker.set(thread)
      thread
    })
    val server = Server.start(config, pool) { _ =>
      assert(Thread.currentThread() eq worker.get())
      response("pooled")
    }
    try {
      withSocket(server) { socket =>
        send(socket, get(fields = "Connection: close\r\n"))
        assertEquals(receive(socket).body, "pooled")
      }
      server.stop()
      val usable = new CountDownLatch(1)
      pool.execute(() => usable.countDown())
      await(usable)
      assert(!pool.isShutdown)
    } finally {
      server.stop()
      pool.shutdownNow()
      assert(pool.awaitTermination(3, TimeUnit.SECONDS))
    }
  }

  test("executor rejection closes the socket and releases admission without stopping the server") {
    val connected = new CountDownLatch(1)
    val submissions = new AtomicInteger
    val pool = Executors.newSingleThreadExecutor()
    val executor: Executor = task => {
      if (submissions.getAndIncrement() == 0) {
        await(connected)
        throw new RejectedExecutionException("saturated")
      }
      pool.execute(task)
    }
    val server = Server.start(config.withMaxConnections(1), executor)(_ => response("ok"))
    try {
      withSocket(server) { socket =>
        connected.countDown()
        closed(socket)
      }
      eventually() {
        withSocket(server) { socket =>
          send(socket, get())
          assertEquals(receive(socket).body, "ok")
        }
      }
    } finally {
      connected.countDown()
      server.stop()
      pool.shutdownNow()
      assert(pool.awaitTermination(3, TimeUnit.SECONDS))
    }
  }

  test("queued connections count toward admission and never dispatch after stop") {
    val pool = Executors.newSingleThreadExecutor()
    val occupied = new CountDownLatch(1)
    val submitted = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger
    pool.execute(() => {
      occupied.countDown()
      await(release)
    })
    await(occupied)
    val executor: Executor = task => {
      pool.execute(task)
      submitted.countDown()
    }
    val server = Server.start(config.withMaxConnections(1), executor) { _ =>
      calls.incrementAndGet()
      response("unexpected")
    }
    try {
      withSocket(server) { queued =>
        send(queued, get())
        await(submitted)
        admissionRejected(server)
        // A stopped server would close this queued connection too; refusal must not hide that failure.
        queued.setSoTimeout(100)
        intercept[SocketTimeoutException](queued.getInputStream.read())
        queued.setSoTimeout(3000)
        server.stop()
        closed(queued)
        release.countDown()
        val drained = new CountDownLatch(1)
        pool.execute(() => drained.countDown())
        await(drained)
        assertEquals(calls.get(), 0)
        assert(!pool.isShutdown)
      }
    } finally {
      release.countDown()
      server.stop()
      pool.shutdownNow()
      assert(pool.awaitTermination(3, TimeUnit.SECONDS))
    }
  }

  test("shared executor shutdown waits for running tasks without joining pool threads") {
    val pool = Executors.newSingleThreadExecutor()
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val server = Server.start(config, pool) { _ =>
      entered.countDown()
      await(release)
      response("ok")
    }
    val stopped = new CountDownLatch(1)
    val stopper = new Thread(() => {
      server.stop()
      stopped.countDown()
    })
    try {
      withSocket(server) { socket =>
        send(socket, get() + get())
        await(entered)
        stopper.start()
        assert(!stopped.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertEquals(receive(socket).body, "ok")
        closed(socket)
        await(stopped)
        val usable = new CountDownLatch(1)
        pool.execute(() => usable.countDown())
        await(usable)
      }
    } finally {
      release.countDown()
      server.stop()
      stopper.join(3000)
      assert(!stopper.isAlive)
      pool.shutdownNow()
      assert(pool.awaitTermination(3, TimeUnit.SECONDS))
    }
  }

  test("callback stop with a supplied executor does not wait for itself") {
    for (external <- List(false, true)) {
      val pool = Executors.newSingleThreadExecutor()
      val entered = new CountDownLatch(1)
      val proceed = new CountDownLatch(1)
      val returned = new CountDownLatch(1)
      val ref = new AtomicReference[Server]
      val server = Server.start(config, pool) { _ =>
        entered.countDown()
        await(proceed)
        ref.get().stop()
        returned.countDown()
        response("ok")
      }
      ref.set(server)
      val stopper = new Thread(() => server.stop())
      try {
        withSocket(server) { socket =>
          send(socket, get())
          await(entered)
          if (external) {
            withSocket(server) { idle =>
              stopper.start()
              closed(idle)
            }
          }
          proceed.countDown()
          await(returned)
          if (external) assertEquals(receive(socket).body, "ok")
          closed(socket)
        }
      } finally {
        proceed.countDown()
        server.stop()
        stopper.join(3000)
        assert(!stopper.isAlive)
        pool.shutdownNow()
        assert(pool.awaitTermination(3, TimeUnit.SECONDS))
      }
    }
  }

  test("direct executor does not hold the lifecycle lock while serving a connection") {
    val executor: Executor = task => task.run()
    val entered = new CountDownLatch(1)
    val ended = new CountDownLatch(1)
    val settings = config.withTimeouts(
      config.timeouts.withBodyReadTimeout(10.seconds).withShutdownGrace(100.millis)
    )
    val server = Server.start(settings, executor) { request =>
      entered.countDown()
      try request.body.read()
      finally ended.countDown()
      response("ok")
    }
    try {
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n\r\n")
        await(entered)
        server.stop()
        await(ended)
        closed(socket)
      }
    } finally server.stop()
  }

  test(
    "raw targets, duplicate headers and socket metadata reach the callback unchanged"
  ) {
    withServer() { request =>
      assertEquals(request.headers.all("X-Value"), Vector("a", "b"))
      assert(
        request.localAddress.getPort > 0 && request.remoteAddress.getPort > 0
      )
      response(request.method + " " + request.target)
    } { server =>
      for (
        (method, target) <- List(
          "GET" -> "/a%2Fb/../c?q=%2f+X",
          "GET" -> "//double/path",
          "GET" -> "http://example.org/a%2F",
          "OPTIONS" -> "*"
        )
      ) {
        withSocket(server) { socket =>
          send(
            socket,
            s"$method $target HTTP/1.1\r\nHost: x\r\nX-Value: a\r\nx-value: b\r\nConnection: close\r\n\r\n"
          )
          assertEquals(receive(socket).body, s"$method $target")
          closed(socket)
        }
      }
    }
  }
  test(
    "persistent mixed framing pipelines execute in wire order and close tokens win"
  ) {
    val calls = new AtomicInteger
    withServer() { request =>
      request.body.transferTo(new ByteArrayOutputStream)
      val n = calls.incrementAndGet()
      if (n == 2) {
        Response(body = ResponseBody.Writer()(out => out.write("two".getBytes(ISO_8859_1))))
      } else response(n.toString)
    } { server =>
      withSocket(server) { socket =>
        send(
          socket,
          "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nabc" +
            "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n" + get(
              fields = "Connection: keep-alive, ClOsE\r\n"
            )
        )
        assertEquals(receive(socket).body, "1")
        assertEquals(receive(socket).body, "two")
        assertEquals(receive(socket).body, "3")
        closed(socket)
        assertEquals(calls.get(), 3)
      }
    }
  }
  test(
    "HTTP10 persistence is explicit and unknown lengths are close delimited"
  ) {
    withServer()(_ => response("ok")) { server =>
      withSocket(server) { socket =>
        send(socket, get(version = "HTTP/1.0"))
        assertEquals(receive(socket).body, "ok")
        closed(socket)
      }
      withSocket(server) { socket =>
        send(
          socket,
          get(
            version = "HTTP/1.0",
            fields = "Connection: keep-alive\r\n"
          ) + get(version = "HTTP/1.0")
        )
        assertEquals(
          receive(socket).headers.first("Connection"),
          Some("keep-alive")
        )
        assertEquals(receive(socket).body, "ok")
        closed(socket)
      }
    }
    withServer()(_ => Response(body = ResponseBody.Writer()(out => out.write('x')))) { server =>
      withSocket(server) { socket =>
        send(socket, get(version = "HTTP/1.0"))
        val actual = receive(socket)
        assertEquals(actual.body, "x")
        assertEquals(actual.headers.first("Transfer-Encoding"), None)
      }
    }
  }
  test("lazy continuation and trailers remain separate from initial headers") {
    val entered = new CountDownLatch(1)
    val read = new CountDownLatch(1)
    withServer() { request =>
      entered.countDown()
      await(read)
      assertEquals(request.body.trailers, None)
      val out = new ByteArrayOutputStream
      request.body.transferTo(out)
      assertEquals(request.headers.all("X-End"), Vector.empty)
      assertEquals(
        request.body.trailers.map(_.all("X-End")),
        Some(Vector("one", "two"))
      )
      response(new String(out.toByteArray, ISO_8859_1))
    } { server =>
      withSocket(server) { socket =>
        send(
          socket,
          "POST / HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\nTransfer-Encoding: chunked\r\n\r\n"
        )
        await(entered)
        socket.setSoTimeout(100)
        intercept[SocketTimeoutException](socket.getInputStream.read())
        read.countDown()
        socket.setSoTimeout(3000)
        assertEquals(receive(socket).status, 100)
        send(socket, "3\r\nabc\r\n0\r\nX-End: one\r\nx-end: two\r\n\r\n")
        assertEquals(receive(socket).body, "abc")
      }
    }
  }
  test("handles empty expectation list elements") {
    val calls = new AtomicInteger
    withServer() { request =>
      calls.incrementAndGet()
      val out = new ByteArrayOutputStream
      request.body.transferTo(out)
      response(new String(out.toByteArray, ISO_8859_1))
    } { server =>
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nExpect: ,\r\nExpect: 100-continue,\r\n\r\n")
        assertEquals(receive(socket).status, 100)
        send(socket, "abc")
        assertEquals(receive(socket).body, "abc")
      }
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nExpect: ,,,,,,,\r\n\r\nabc")
        val actual = receive(socket)
        assertEquals(actual.status, 200)
        assertEquals(actual.body, "abc")
      }
      for (value <- List(",,,,,,,,,100-continue", ",100-continue,,fancy")) {
        withSocket(server) { socket =>
          send(socket, "POST / HTTP/1.1\r\nHost: x\r\nExpect: " + value + "\r\nContent-Length: 0\r\n\r\n")
          assertEquals(receive(socket).status, 417)
        }
      }
      withSocket(server) { socket =>
        send(
          socket,
          "POST / HTTP/1.1\r\nHost: x\r\nExpect: ,,,,\r\nExpect: ,,,,100-continue\r\nContent-Length: 0\r\n\r\n"
        )
        assertEquals(receive(socket).status, 417)
      }
      assertEquals(calls.get(), 2)
    }
  }
  test("HTTP/1.0 ignores 100-continue and upgrade while reading the body") {
    val calls = new AtomicInteger
    withServer() { request =>
      calls.incrementAndGet()
      val out = new ByteArrayOutputStream
      request.body.transferTo(out)
      response(new String(out.toByteArray, ISO_8859_1))
    } { server =>
      for (
        fields <- List(
          "Expect: 100-continue\r\n",
          "Upgrade: websocket\r\n",
          "Connection: upgrade\r\n",
          "Expect: 100-continue\r\nUpgrade: websocket\r\nConnection: upgrade\r\n"
        )
      ) {
        withSocket(server) { socket =>
          send(socket, "POST / HTTP/1.0\r\nContent-Length: 3\r\n" + fields + "\r\nabc")
          val actual = receive(socket)
          assertEquals(actual.status, 200)
          assertEquals(actual.body, "abc")
          closed(socket)
        }
      }
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.0\r\nExpect: 100-continue, fancy\r\nContent-Length: 0\r\n\r\n")
        assertEquals(receive(socket).status, 417)
      }
      assertEquals(calls.get(), 4)
    }
  }
  test(
    "oversized declared bodies and unsupported expectations skip dispatch and continuation"
  ) {
    val calls = new AtomicInteger
    withServer(config.withLimits(HttpLimits.Default.withMaxBodyBytes(Some(3))))(_ => {
      calls.incrementAndGet()
      response("bad")
    }) { server =>
      for (
        (fields, code) <- List(
          "Content-Length: 4\r\nExpect: 100-continue\r\n" -> 413,
          "Expect: fancy\r\n" -> 417
        )
      ) {
        withSocket(server) { socket =>
          send(socket, "POST / HTTP/1.1\r\nHost: x\r\n" + fields + "\r\n")
          assertEquals(receive(socket).status, code)
          closed(socket)
        }
      }
      assertEquals(calls.get(), 0)
    }
  }
  test(
    "caught chunk body errors remain terminal for single bulk and skip consumption"
  ) {
    for (mode <- List("single", "bulk", "skip")) {
      val calls = new AtomicInteger
      withServer(config.withLimits(HttpLimits.Default.withMaxBodyBytes(Some(3)))) { request =>
        calls.incrementAndGet()
        try {
          mode match {
            case "single" => while (request.body.read() != -1) ()
            case "bulk"   => request.body.transferTo(new ByteArrayOutputStream)
            case _        => request.body.skip(100)
          }
        } catch { case _: IOException => () }
        response("wrong")
      } { server =>
        withSocket(server) { socket =>
          send(
            socket,
            "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n" + get()
          )
          assertEquals(receive(socket).status, 413)
          closed(socket)
          assertEquals(calls.get(), 1)
        }
      }
    }
  }
  test(
    "streaming response writers can consume requests and streams expire with the exchange"
  ) {
    val escaped = new AtomicReference[RequestBody]
    val ended = new CountDownLatch(1)
    withServer() { request =>
      escaped.set(request.body)
      Response(body = ResponseBody.Writer() { out =>
        try request.body.transferTo(out)
        finally ended.countDown()
      })
    } { server =>
      withSocket(server) { socket =>
        send(
          socket,
          "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nabc"
        )
        val actual = receive(socket)
        assertEquals(actual.body, "abc")
        assertEquals(actual.headers.first("Connection"), Some("close"))
        await(ended)
        closed(socket)
        intercept[IOException](escaped.get().read())
      }
    }
  }
  test(
    "unread request bodies close without draining or parsing pipelined bytes"
  ) {
    val calls = new AtomicInteger
    withServer()(_ => {
      calls.incrementAndGet()
      response("ok")
    }) { server =>
      withSocket(server) { socket =>
        send(
          socket,
          "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 999\r\n\r\n" + get()
        )
        val actual = receive(socket)
        assertEquals(actual.body, "ok")
        assertEquals(actual.headers.first("Connection"), Some("close"))
        closed(socket)
        assertEquals(calls.get(), 1)
      }
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nExpect: 100-continue\r\n\r\n")
        val actual = receive(socket)
        assertEquals(actual.status, 200)
        assertEquals(actual.headers.first("Connection"), Some("close"))
        closed(socket)
        assertEquals(calls.get(), 2)
      }
    }
  }
  test(
    "malformed heads, upgrades and tunnels are rejected and server recovers"
  ) {
    withServer()(_ => response("ok")) { server =>
      for (
        (wire, code) <- List(
          "GET / HTTP/1.1\r\n\r\n" -> 400,
          get(fields = "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n") -> 400,
          "GET / HTTP/1.1\nHost: x\r\n\r\n" -> 400,
          get(fields = "Upgrade: websocket\r\nConnection: upgrade\r\n") -> 501,
          "CONNECT example.org:443 HTTP/1.1\r\nHost: x\r\n\r\n" -> 501
        )
      ) {
        withSocket(server) { socket =>
          send(socket, wire)
          assertEquals(receive(socket).status, code)
          closed(socket)
        }
      }
      withSocket(server) { socket =>
        send(socket, get())
        assertEquals(receive(socket).body, "ok")
      }
    }
  }

  test("HEAD and bodyless status writers are never invoked over real sockets") {
    val invoked = new AtomicInteger
    withServer() { request =>
      val status = {
        if (request.target == "/") 200 else request.target.drop(1).toInt
      }
      Response(
        status,
        body = ResponseBody.Writer(Some(3))(_ => {
          invoked.incrementAndGet()
          ()
        })
      )
    } { server =>
      for (
        (method, target, code) <- List(
          ("HEAD", "/", 200),
          ("GET", "/204", 204),
          ("GET", "/205", 205),
          ("GET", "/304", 304)
        )
      ) {
        withSocket(server) { socket =>
          send(
            socket,
            s"$method $target HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
          )
          assertEquals(receive(socket, headOnly = true).status, code)
          closed(socket)
        }
      }
      assertEquals(invoked.get(), 0)
    }
  }
  test("a custom listener backlog serves requests with independent admission limits") {
    withServer(config.withBacklog(257).withMaxConnections(1))(_ => response("ok")) { server =>
      withSocket(server) { socket =>
        send(socket, get())
        assertEquals(receive(socket).body, "ok")
        admissionRejected(server)
        send(socket, get())
        assertEquals(receive(socket).body, "ok")
      }
    }
  }
  for (mode <- List("handler", "framing", "writer")) {
    test(s"$mode failures are reported once with unchanged wire behavior") {
      val error = new IllegalStateException(mode)
      val reported = new AtomicReference[Throwable]()
      val reports = new AtomicInteger
      val settings = config.withOnError { failure =>
        reported.set(failure)
        reports.incrementAndGet()
        ()
      }
      withServer(settings) { _ =>
        mode match {
          case "handler" => throw error
          case "framing" => Response(headers = Headers("Content-Length" -> "999"))
          case _         =>
            Response(body = ResponseBody.Writer() { out =>
              out.write('x')
              out.flush()
              throw error
            })
        }
      } { server =>
        withSocket(server) { socket =>
          send(socket, get())
          if (mode == "writer") {
            val raw = new ByteArrayOutputStream
            socket.getInputStream.transferTo(raw)
            val wire = new String(raw.toByteArray, ISO_8859_1)
            assert(wire.startsWith("HTTP/1.1 200 "))
            assertEquals(wire.sliding(5).count(_ == "HTTP/"), 1)
            assert(!wire.endsWith("0\r\n\r\n"))
          } else {
            assertEquals(receive(socket).status, 500)
            closed(socket)
          }
          assertEquals(reports.get(), 1)
          if (mode == "framing") assert(reported.get().isInstanceOf[IllegalArgumentException])
          else assert(reported.get() eq error)
        }
      }
    }
  }
  test("a failing error reporter preserves the 500 response and server recovery") {
    val reports = new AtomicInteger
    val settings = config.withOnError { _ =>
      reports.incrementAndGet()
      throw new IllegalStateException("reporter")
    }
    withServer(settings) { request =>
      if (request.target == "/fail") throw new IllegalStateException("handler")
      response("recovered")
    } { server =>
      withSocket(server) { socket =>
        send(socket, get("/fail"))
        assertEquals(receive(socket).status, 500)
        closed(socket)
      }
      assertEquals(reports.get(), 1)
      withSocket(server) { socket =>
        send(socket, get())
        assertEquals(receive(socket).body, "recovered")
      }
      assertEquals(reports.get(), 1)
    }
  }
  test(
    "callback failures and invalid response framing produce 500 before commitment"
  ) {
    withServer() { request =>
      if (request.target == "/throw") {
        throw new IllegalStateException("callback")
      }
      Response(headers = Headers("Content-Length" -> "999"))
    } { server =>
      for (target <- List("/throw", "/conflict")) withSocket(server) { socket =>
        send(socket, get(target))
        assertEquals(receive(socket).status, 500)
        closed(socket)
      }
    }
  }
  test(
    "response failures after commitment abort without a second response or connection reuse"
  ) {
    for (mode <- List("underflow", "overflow", "throw", "body-failure")) {
      val calls = new AtomicInteger
      withServer(config.withLimits(HttpLimits.Default.withMaxBodyBytes(Some(1)))) { request =>
        calls.incrementAndGet()
        Response(body = ResponseBody.Writer(Some(2)) { out =>
          out.write('x')
          out.flush()
          mode match {
            case "underflow" => ()
            case "overflow"  =>
              try out.write(Array[Byte](1, 2))
              catch { case _: IOException => () }
            case "throw" => throw new IllegalStateException("writer")
            case _       =>
              try request.body.transferTo(out)
              catch { case _: IOException => () }
          }
        })
      } { server =>
        withSocket(server) { socket =>
          send(
            socket,
            "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n0\r\n\r\n" + get()
          )
          val raw = new ByteArrayOutputStream
          try socket.getInputStream.transferTo(raw)
          catch { case _: SocketException => () }
          val wire = new String(raw.toByteArray, ISO_8859_1)
          assert(wire.startsWith("HTTP/1.1 200 "))
          assertEquals(wire.sliding(5).count(_ == "HTTP/"), 1)
          assertEquals(calls.get(), 1)
        }
      }
    }
  }
  test(
    "stalled fixed bodies, chunk data and trailers time out and release admission"
  ) {
    for (
      (fields, payload) <- List(
        "Content-Length: 1\r\n" -> "",
        "Transfer-Encoding: chunked\r\n" -> "1\r\n",
        "Transfer-Encoding: chunked\r\n" -> "0\r\nX-Slow: "
      )
    ) {
      withServer(config.withMaxConnections(1)) { request =>
        request.body.transferTo(new ByteArrayOutputStream)
        response("ok")
      } { server =>
        withSocket(server) { socket =>
          send(
            socket,
            "POST / HTTP/1.1\r\nHost: x\r\n" + fields + "\r\n" + payload
          )
          assertEquals(receive(socket).status, 408)
          closed(socket)
        }
        eventually() {
          withSocket(server) { socket =>
            send(socket, get())
            assertEquals(receive(socket).body, "ok")
          }
        }
      }
    }
  }
  test(
    "initial and subsequent heads have absolute deadlines despite byte trickling"
  ) {
    for (subsequent <- List(false, true)) withServer()(_ => response("ok")) { server =>
      withSocket(server) { socket =>
        if (subsequent) {
          send(socket, get())
          assertEquals(receive(socket).body, "ok")
        }
        send(socket, "G")
        Thread.sleep(150)
        send(socket, "E")
        Thread.sleep(150)
        send(socket, "T")
        Thread.sleep(250)
        assertEquals(receive(socket).status, 408)
        closed(socket)
      }
    }
  }
  test(
    "complete slow heads within deadline succeed and handler think time is excluded"
  ) {
    withServer()(_ => {
      Thread.sleep(600)
      response("ok")
    }) { server =>
      withSocket(server) { socket =>
        send(socket, "GET / HTTP/1.1\r\nHost:")
        Thread.sleep(100)
        send(socket, " x\r\n\r\n")
        assertEquals(receive(socket).body, "ok")
        send(socket, get())
        assertEquals(receive(socket).body, "ok")
      }
    }
  }
  test("request head deadline is reapplied after a persistent body read") {
    val settings = config.withTimeouts(
      config.timeouts.withIdleTimeout(3.seconds).withRequestHeadTimeout(200.millis).withBodyReadTimeout(3.seconds)
    )
    withServer(settings) { request =>
      request.body.transferTo(new ByteArrayOutputStream)
      response("ok")
    } { server =>
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n\r\nx")
        assertEquals(receive(socket).body, "ok")
        val started = System.nanoTime()
        send(socket, "GET / HTTP/1.1\r\nHost:")
        assertEquals(receive(socket).status, 408)
        assert((System.nanoTime() - started).nanos < 1500.millis)
        closed(socket)
      }
    }
  }
  test("silent and idle persistent connections close and release admission") {
    withServer(config.withMaxConnections(1))(_ => response("ok")) { server =>
      withSocket(server)(closed)
      eventually() {
        withSocket(server) { socket =>
          send(socket, get())
          assertEquals(receive(socket).body, "ok")
          closed(socket)
        }
      }
    }
  }
  test(
    "active connections consume admission; rejection and disconnect recover"
  ) {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    withServer(config.withMaxConnections(1)) { request =>
      if (request.target == "/hold") {
        entered.countDown()
        await(release)
      }
      response("ok")
    } { server =>
      try {
        withSocket(server) { first =>
          send(first, get("/hold"))
          await(entered)
          admissionRejected(server)
          release.countDown()
          assertEquals(receive(first).body, "ok")
        }
      } finally release.countDown()
      eventually() {
        withSocket(server) { socket =>
          send(socket, get())
          assertEquals(receive(socket).body, "ok")
        }
      }
    }
  }
  test("streaming can outlast per-I/O timeouts while making progress") {
    withServer() { request =>
      Response(body = ResponseBody.Writer(Some(3)) { out =>
        var i = 0
        while (i < 3) {
          out.write(request.body.read())
          out.flush()
          i += 1
        }
      })
    } { server =>
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\na")
        Thread.sleep(300)
        send(socket, "b")
        Thread.sleep(300)
        send(socket, "c")
        assertEquals(receive(socket).body, "abc")
      }
    }
  }
  test("blocked socket writes expire and the server recovers") {
    val started = new CountDownLatch(1)
    val finished = new CountDownLatch(1)
    withServer(config.withMaxConnections(1)) { request =>
      if (request.target != "/large") response("ok")
      else {
        Response(body = ResponseBody.Writer() { out =>
          started.countDown()
          try {
            val block = new Array[Byte](65536)
            (0 until 4096).foreach(_ => out.write(block))
          } finally finished.countDown()
        })
      }
    } { server =>
      withSocket(server) { socket =>
        socket.setReceiveBufferSize(1024)
        send(socket, get("/large"))
        await(started)
        await(finished)
      }
      eventually() {
        withSocket(server) { socket =>
          send(socket, get())
          assertEquals(receive(socket).body, "ok")
        }
      }
    }
  }
  test(
    "stop waits for an active response within grace and suppresses the next pipelined request"
  ) {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger
    val server = Server.start(config) { _ =>
      calls.incrementAndGet()
      entered.countDown()
      await(release)
      response("ok")
    }
    try {
      withSocket(server) { socket =>
        send(socket, get() + get())
        await(entered)
        val stopped = new CountDownLatch(1)
        val stop = new Thread(() => {
          server.stop()
          stopped.countDown()
        })
        stop.start()
        val done = stopped.await(100, TimeUnit.MILLISECONDS)
        assert(!done)
        release.countDown()
        assertEquals(receive(socket).body, "ok")
        closed(socket)
        await(stopped)
        stop.join(1000)
        assert(!stop.isAlive)
        assertEquals(calls.get(), 1)
      }
    } finally {
      release.countDown()
      server.stop()
    }
  }
  test(
    "forced shutdown unblocks body reads within one grace budget and concurrent stop is idempotent"
  ) {
    val entered = new CountDownLatch(1)
    val ended = new CountDownLatch(1)
    val settings = config.withTimeouts(
      config.timeouts.withBodyReadTimeout(10.seconds).withShutdownGrace(200.millis)
    )
    val server = Server.start(settings) { request =>
      entered.countDown()
      try request.body.read()
      finally ended.countDown()
      response("ok")
    }
    try {
      withSocket(server) { socket =>
        send(socket, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n\r\n")
        await(entered)
        val start = System.nanoTime()
        val stoppers = Vector.fill(4)(new Thread(() => server.stop()))
        stoppers.foreach(_.start())
        stoppers.foreach(_.join(2000))
        assert(stoppers.forall(!_.isAlive))
        assert((System.nanoTime() - start).nanos < 1500.millis)
        await(ended)
        closed(socket)
        server.stop()
      }
    } finally server.stop()
  }
  test(
    "callback stop and callback stop racing an external stop do not self-join"
  ) {
    for (external <- List(false, true)) {
      val entered = new CountDownLatch(1)
      val proceed = new CountDownLatch(1)
      val returned = new CountDownLatch(1)
      val ref = new AtomicReference[Server]
      val server = Server.start(config) { _ =>
        entered.countDown()
        await(proceed)
        ref.get().stop()
        returned.countDown()
        response("ok")
      }
      ref.set(server)
      try {
        withSocket(server) { socket =>
          send(socket, get())
          await(entered)
          val stopper = new Thread(() => server.stop())
          if (external) {
            stopper.start()
            Thread.sleep(50)
          }
          proceed.countDown()
          await(returned)
          if (external) {
            stopper.join(2000)
            assert(!stopper.isAlive)
          }
          if (external) assertEquals(receive(socket).body, "ok")
          closed(socket)
        }
      } finally {
        proceed.countDown()
        server.stop()
      }
    }
  }
  test("stop closes idle sockets and releases the bound port") {
    val server = Server.start(config)(_ => response("ok"))
    val address = server.address
    try {
      withSocket(server) { socket =>
        server.stop()
        closed(socket)
      }
    } finally server.stop()
    val rebound = {
      Server.start(config.withBindAddress(address))(_ => response("new"))
    }
    rebound.stop()
  }

  private def eventually()(assertion: => Unit): Unit = {
    val deadline = System.nanoTime() + 2.seconds.toNanos
    var done = false
    while (!done) {
      try {
        assertion
        done = true
      } catch {
        case _: IOException if System.nanoTime() < deadline => Thread.sleep(10)
      }
    }
  }

  test(
    "exact decoded limits preserve pipeline and None permits large fixed and chunked uploads"
  ) {
    for (limit <- List(Some(3L), None)) {
      val payload = if (limit.nonEmpty) "abc" else "x" * (1024 * 1024 + 1)
      withServer(config.withLimits(HttpLimits.Default.withMaxBodyBytes(limit))) { request =>
        val sink = new ByteArrayOutputStream
        request.body.transferTo(sink)
        response(sink.size().toString)
      } { server =>
        for (chunked <- List(false, true)) withSocket(server) { socket =>
          val fields = {
            if (chunked) "Transfer-Encoding: chunked\r\n"
            else s"Content-Length: ${payload.length}\r\n"
          }
          val wire = {
            if (chunked) {
              Integer.toHexString(
                payload.length
              ) + "\r\n" + payload + "\r\n0\r\n\r\n"
            } else payload
          }
          send(
            socket,
            "POST / HTTP/1.1\r\nHost: x\r\n" + fields + "\r\n" + wire + get(
              fields = "Connection: close\r\n"
            )
          )
          assertEquals(receive(socket).body, payload.length.toString)
          assertEquals(receive(socket).body, "0")
          closed(socket)
        }
      }
    }
  }
  test("Some zero permits empty bodies and rejects chunked payloads") {
    withServer(config.withLimits(HttpLimits.Default.withMaxBodyBytes(Some(0)))) { request =>
      request.body.transferTo(new ByteArrayOutputStream)
      response("ok")
    } { server =>
      for ((wire, code) <- List("0\r\n\r\n" -> 200, "1\r\nx\r\n0\r\n\r\n" -> 413)) withSocket(server) { socket =>
        send(
          socket,
          "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" + wire
        )
        assertEquals(receive(socket).status, code)
      }
    }
  }
  test(
    "malformed and truncated bodies cannot be converted to success by a callback"
  ) {
    for (
      (fields, payload) <- List(
        "Content-Length: 3\r\n" -> "ab",
        "Transfer-Encoding: chunked\r\n" -> "1\r\nxBAD\r\n"
      )
    ) {
      withServer() { request =>
        try request.body.transferTo(new ByteArrayOutputStream)
        catch { case _: IOException => () }
        response("wrong")
      } { server =>
        withSocket(server) { socket =>
          send(
            socket,
            "POST / HTTP/1.1\r\nHost: x\r\n" + fields + "\r\n" + payload
          )
          socket.shutdownOutput()
          assertEquals(receive(socket).status, 400)
          closed(socket)
        }
      }
    }
  }
  test(
    "stop unblocks blocked writes and owns its watcher through termination"
  ) {
    val started = new CountDownLatch(1)
    val ended = new CountDownLatch(1)
    val server = Server.start(
      config.withTimeouts(
        config.timeouts.withWriteTimeout(10.seconds).withShutdownGrace(200.millis)
      )
    ) { _ =>
      Response(body = ResponseBody.Writer() { out =>
        started.countDown()
        try {
          val block = new Array[Byte](65536)
          (0 until 4096).foreach(_ => out.write(block))
        } finally ended.countDown()
      })
    }
    try {
      withSocket(server) { socket =>
        socket.setReceiveBufferSize(1024)
        send(socket, get())
        await(started)
        server.stop()
        await(ended)
      }
    } finally server.stop()
  }
  test("failed bind cleans up without affecting the existing server") {
    withServer()(_ => response("ok")) { server =>
      intercept[IOException](
        Server.start(config.withBindAddress(server.address))(_ => response("wrong"))
      )
      withSocket(server) { socket =>
        send(socket, get())
        assertEquals(receive(socket).body, "ok")
      }
    }
  }

  test(
    "admission saturation rejects a connection burst without queueing callbacks"
  ) {
    val entered = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger
    withServer(config.withMaxConnections(2)) { _ =>
      calls.incrementAndGet()
      entered.countDown()
      await(release)
      response("ok")
    } { server =>
      try {
        withSocket(server) { first =>
          withSocket(server) { second =>
            send(first, get())
            send(second, get())
            await(entered)
            (0 until 8).foreach(_ => admissionRejected(server))
            assertEquals(calls.get(), 2)
            release.countDown()
            assertEquals(receive(first).body, "ok")
            assertEquals(receive(second).body, "ok")
          }
        }
        eventually() {
          withSocket(server) { socket =>
            send(socket, get())
            assertEquals(receive(socket).body, "ok")
          }
        }
      } finally release.countDown()
    }
  }
}
