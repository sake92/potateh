import java.io.{ByteArrayOutputStream, EOFException, IOException}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** Raw HTTP/1.1 through real proxies; backend handler observations are the oracle. */
object ProxyBoundaries {

  private case class Sample(
      name: String,
      fields: String,
      body: String,
      expected: Option[String],
      lf: Boolean = false,
      stagedPrefix: Int = 0
  )

  private case class Reply(status: Int, body: String)

  private case class Seen(connection: Int, method: String, target: String, body: Option[String])

  def run(command: Seq[String], destination: Path, revision: String): Int = {
    require(System.getProperty("os.name") == "Linux", "Proxy checks require Linux Docker host networking")
    val root = Path.of("conformance/proxy").toAbsolutePath
    require(Files.isRegularFile(root.resolve("compose.yaml")), "Run from the repository root")
    val results = ujson.Arr()
    val report = ujson.Obj(
      "suite" -> "proxy",
      "revision" -> revision,
      "server" -> ujson.Arr.from(command),
      "results" -> results,
      "complete" -> false
    )
    val project = "potateh-proxy-" + UUID.randomUUID().toString
    report("compose_project") = project
    val composeBase = Seq("docker", "compose", "-p", project, "-f", root.resolve("compose.yaml").toString)
    val cleanupLock = new Object
    var activeEnv = Map.empty[String, String]
    var cleanupFailed = false
    def docker(args: Seq[String], log: Path, env: Map[String, String], seconds: Int = 30): Unit = {
      val child = ConformanceRunner.Children.start(composeBase ++ args, log, env)
      try {
        require(child.waitFor(seconds, TimeUnit.SECONDS), s"Docker command timed out; see $log")
        require(child.exitValue() == 0, s"Docker command failed; see $log")
      } finally ConformanceRunner.Children.stop(child)
    }
    def down(): Unit = cleanupLock.synchronized {
      if (activeEnv.nonEmpty) {
        // A separate bounded process also works during the runner's global child shutdown.
        val builder = {
          new ProcessBuilder((composeBase ++ Seq("down", "--timeout", "3", "--volumes", "--remove-orphans"))*)
            .redirectErrorStream(true)
            .redirectOutput(destination.resolve("cleanup.log").toFile)
        }
        activeEnv.foreach { case (key, value) => builder.environment().put(key, value) }
        val child = builder.start()
        try {
          if (!child.waitFor(15, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(3, TimeUnit.SECONDS)
            throw new IllegalStateException("Docker cleanup timed out")
          }
          require(child.exitValue() == 0, "Docker cleanup failed; see cleanup.log")
          activeEnv = Map.empty
        } finally {
          if (child.isAlive) {
            child.destroyForcibly()
            child.waitFor(3, TimeUnit.SECONDS)
          }
        }
      }
    }
    val hook = new Thread(() => down(), "proxy-compose-cleanup")
    Runtime.getRuntime.addShutdownHook(hook)
    def save(): Unit = Files.writeString(destination.resolve("run.json"), ujson.write(report, indent = 2) + "\n")
    try {
      for (mode <- Seq("direct", "caddy", "nginx-buffered", "nginx-streaming")) {
        val directory = Files.createDirectory(destination.resolve(mode))
        val result = ujson.Obj("profile" -> mode, "complete" -> false, "cases" -> ujson.Arr())
        results.value += result
        val log = directory.resolve("server.log")
        val stop = directory.resolve("stop").toAbsolutePath
        val server = ConformanceRunner.Children.start(
          command,
          log,
          Map("HTTP_CONFORMANCE_PROFILE" -> "proxy", "HTTP_CONFORMANCE_STOP_FILE" -> stop.toString)
        )
        try {
          val backend = ConformanceRunner.awaitPort(server, log).toInt
          val port = {
            if (mode == "direct") backend
            else {
              val reservation = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
              val selected = try reservation.getLocalPort
              finally reservation.close()
              val caddy = mode == "caddy"
              val template = if (caddy) "Caddyfile" else "nginx.conf"
              val buffering = if (mode == "nginx-streaming") "off" else "on"
              val config = directory.resolve(template).toAbsolutePath
              Files.writeString(
                config,
                Files
                  .readString(root.resolve(template))
                  .replace("@PORT@", selected.toString)
                  .replace("@BACKEND@", backend.toString)
                  .replace("@BUFFERING@", buffering)
              )
              val env = Map("PROXY_CONFIG" -> config.toString)
              val service = if (caddy) "caddy" else "nginx"
              cleanupLock.synchronized {
                activeEnv = env
                docker(Seq("up", "-d", "--pull", "missing", service), directory.resolve("compose-up.log"), env, 180)
              }
              docker(Seq("images", "--format", "json"), directory.resolve("images.json"), env)
              awaitProxy(selected)
              selected
            }
          }
          result("backend_port") = backend
          result("client_port") = port
          // Same downstream connection AND separate downstream clients must reuse a backend socket.
          val reuseStart = observations(log).size
          val socket = connect(port)
          try {
            for (n <- 1 to 2) {
              send(socket, get(s"/reuse-$n", close = false))
              expect(readReply(socket), "GET", s"/reuse-$n", "")
            }
          } finally socket.close()
          if (mode != "direct") {
            for (n <- 3 to 4) {
              val client = connect(port)
              try {
                send(client, get(s"/reuse-$n", close = true))
                expect(readReply(client), "GET", s"/reuse-$n", "")
              } finally client.close()
            }
          }
          val reused = observations(log).drop(reuseStart).filter(_.body.isDefined)
          val count = if (mode == "direct") 2 else 4
          require(
            reused.size == count && reused.map(_.connection).distinct.size == 1,
            s"$mode: backend connection reuse was not established: $reused"
          )
          result("reuse_verified") = true
          for (sample <- samples) {
            val caseResult = ujson.Obj("name" -> sample.name, "complete" -> false)
            result("cases").arr += caseResult
            try {
              check(port, log, directory, sample, caseResult, mode != "direct")
              caseResult("complete") = true
            } catch {
              case NonFatal(error) => caseResult("error") = error.toString
            }
            save()
          }
          require(server.isAlive, "Fixture exited during proxy checks")
          result("complete") = result("cases").arr.forall(_("complete").bool)
        } catch {
          case NonFatal(error) =>
            result("error") = error.toString
            System.err.println(s"$mode: $error")
        } finally {
          try {
            if (mode != "direct") {
              val env = cleanupLock.synchronized(activeEnv)
              if (env.nonEmpty) {
                try docker(Seq("logs", "--no-color"), directory.resolve("proxy.log"), env)
                finally down()
              }
            }
          } catch {
            case NonFatal(error) =>
              cleanupFailed = true
              result("cleanup_error") = error.toString
          } finally {
            try {
              Files.writeString(stop, "stop\n")
              require(server.waitFor(5, TimeUnit.SECONDS), "Fixture stop timed out")
              require(server.exitValue() == 0, "Fixture stop failed")
            } catch {
              case NonFatal(error) =>
                result("complete") = false
                result("stop_error") = error.toString
            } finally ConformanceRunner.Children.stop(server)
          }
          save()
        }
        val complete = result("complete").bool
        System.err.println(s"proxy/$mode: complete=$complete; results in $directory")
      }
      report("complete") = !cleanupFailed && results.value.forall(_("complete").bool)
      if (report("complete").bool) 0 else 1
    } finally {
      try down()
      finally {
        Runtime.getRuntime.removeShutdownHook(hook)
        save()
      }
    }
  }

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
  private def encoded(text: String): String = hex(text.getBytes(ISO_8859_1))
  private def decoded(text: String): String = {
    new String(text.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray, ISO_8859_1)
  }
  private def get(target: String, close: Boolean): String = {
    val connection = if (close) "close" else "keep-alive"
    s"GET $target HTTP/1.1\r\nHost: fixture\r\nConnection: $connection\r\n\r\n"
  }
  private def samples: Seq[Sample] = {
    val decoy = get("/smuggled", close = false)
    val chunked = s"${decoy.length.toHexString}\r\n$decoy\r\n0\r\n\r\n"
    Seq(
      Sample("fixed-decoy", s"Content-Length: ${decoy.length}\r\n", decoy, Some(decoy)),
      Sample(
        "chunked-decoy",
        "Transfer-Encoding: chunked\r\n",
        chunked,
        Some(decoy),
        stagedPrefix = chunked.length - 5
      ),
      Sample("cl-te", s"Content-Length: ${chunked.length}\r\nTransfer-Encoding: chunked\r\n", chunked, Some(decoy)),
      Sample("te-cl", "Transfer-Encoding: chunked\r\nContent-Length: 1\r\n", chunked, Some(decoy)),
      Sample("duplicate-length", "Content-Length: 4\r\nContent-Length: 4\r\n", "ping", Some("ping")),
      Sample("list-length", "Content-Length: 4, 4\r\n", "ping", Some("ping")),
      Sample("conflicting-length", "Content-Length: 4\r\nContent-Length: 1\r\n", "ping", None),
      Sample("signed-length", "Content-Length: +4\r\n", "ping", None),
      Sample("field-space", "Content-Length : 4\r\n", "ping", None),
      Sample("bad-chunk", "Transfer-Encoding: chunked\r\n", "Z\r\nping\r\n0\r\n\r\n", None),
      Sample(
        "staged-bad-chunk",
        "Transfer-Encoding: chunked\r\n",
        "4\r\nping\r\nZ\r\n0\r\n\r\n",
        None,
        stagedPrefix = 9
      ),
      Sample("bare-lf", "Content-Length: 4\r\n", "ping", Some("ping"), lf = true),
      Sample("obs-fold", "X-Test: one\r\n two\r\nContent-Length: 4\r\n", "ping", Some("ping"))
    )
  }

  private def check(
      port: Int,
      log: Path,
      directory: Path,
      sample: Sample,
      result: ujson.Obj,
      requireReuse: Boolean
  ): Unit = {
    val start = observations(log).size
    val target = "/case-" + sample.name
    val marker = "/marker-" + sample.name
    val recovery = "/recovery-" + sample.name
    val head = s"POST $target HTTP/1.1\r\nHost: fixture\r\n${sample.fields}\r\n"
    val firstHead = if (sample.lf) head.replace("\r\n", "\n") else head
    val wire = firstHead + sample.body + get(marker, close = true)
    Files.write(directory.resolve(sample.name + ".request.bin"), wire.getBytes(ISO_8859_1))
    val socket = connect(port)
    val replies = scala.collection.mutable.ArrayBuffer.empty[Reply]
    val raw = new ByteArrayOutputStream
    try {
      if (sample.stagedPrefix > 0) {
        val split = firstHead.length + sample.stagedPrefix
        send(socket, wire.take(split))
        Thread.sleep(150)
        send(socket, wire.drop(split))
      } else send(socket, wire)
      val first = readReply(socket, Some(raw))
      replies += first
      if (Set("fixed-decoy", "chunked-decoy").contains(sample.name)) {
        require(first.status == 200, "Valid body control was rejected")
      }
      if (first.status == 200) {
        val body = sample.expected.getOrElse(throw new IllegalStateException("Invalid framing accepted"))
        expect(first, "POST", target, body)
        val second = readReply(socket, Some(raw))
        replies += second
        expect(second, "GET", marker, "")
      } else {
        require(first.status >= 400 && first.status <= 599, s"Unexpected status ${first.status}")
        // An error must end this connection, so an ambiguous suffix cannot survive for a later client.
        require(socket.getInputStream.read() == -1, "Error response left suffix bytes or an open connection")
      }
    } finally {
      socket.close()
      Files.write(directory.resolve(sample.name + ".response.bin"), raw.toByteArray)
      result("replies") = ujson.Arr.from(replies.map(r => ujson.Obj("status" -> r.status, "body" -> r.body)))
      result("backend") = seenJson(observations(log).drop(start))
    }
    val client = connect(port)
    try {
      send(client, get(recovery, close = true))
      expect(readReply(client), "GET", recovery, "")
    } finally client.close()
    Thread.sleep(100)
    val seen = observations(log).drop(start)
    result("backend") = seenJson(seen)
    val accepted = replies.head.status == 200
    val expectedTargets = if (accepted) Seq(target, marker, recovery) else Seq(recovery)
    val completed = seen.filter(_.body.isDefined)
    require(completed.map(_.target) == expectedTargets, s"Unexpected backend requests: $completed")
    val expectedHeads = if (accepted) Seq(target, marker, recovery) else Seq(recovery)
    val heads = seen.filter(_.body.isEmpty).map(_.target)
    // Streaming proxies may forward a head before discovering an invalid body.
    require(heads == expectedHeads || (!accepted && heads == Seq(target, recovery)), s"Unexpected parsed heads: $heads")
    if (accepted) {
      require(completed.head.method == "POST" && completed.head.body == sample.expected, "Backend body disagreement")
      require(completed(1).method == "GET" && completed(1).body.contains(""), "Marker body disagreement")
      result("recovery_reused_backend") = completed.head.connection == completed.last.connection
      if (requireReuse) {
        require(completed.map(_.connection).distinct.size == 1, "Forwarded sequence did not reuse the backend socket")
      }
    }
    require(completed.last.method == "GET" && completed.last.body.contains(""), "Recovery body disagreement")
    result("outcome") = {
      if (accepted) "forwarded-exactly"
      else if (heads.contains(target)) "rejected-after-forwarded-head"
      else "rejected-before-handler"
    }
  }

  private def seenJson(seen: Vector[Seen]): ujson.Arr = ujson.Arr.from(
    seen.map(s => {
      ujson.Obj(
        "connection" -> s.connection,
        "method" -> s.method,
        "target" -> s.target,
        "body" -> s.body.map(ujson.Str(_)).getOrElse(ujson.Null)
      )
    })
  )

  private def observations(log: Path): Vector[Seen] = Files
    .readString(log)
    .linesIterator
    .flatMap { line =>
      val fields = line.split("\t", -1)
      if (fields.length == 4 && fields(0) == "PROXY_HEAD") {
        Some(Seen(fields(1).toInt, fields(2), decoded(fields(3)), None))
      } else if (fields.length == 5 && fields(0) == "PROXY_BODY") {
        Some(Seen(fields(1).toInt, fields(2), decoded(fields(3)), Some(decoded(fields(4)))))
      } else None
    }
    .toVector

  private def connect(port: Int): Socket = {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 500)
      socket.setSoTimeout(5000)
      socket
    } catch {
      case error: Throwable =>
        socket.close()
        throw error
    }
  }
  private def send(socket: Socket, wire: String): Unit = {
    socket.getOutputStream.write(wire.getBytes(ISO_8859_1))
    socket.getOutputStream.flush()
  }
  private def awaitProxy(port: Int): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    var ready = false
    while (!ready && System.nanoTime() < deadline) {
      try {
        val socket = connect(port)
        socket.close()
        ready = true
      } catch { case _: IOException => Thread.sleep(100) }
    }
    require(ready, "Proxy did not become ready within 15 seconds")
  }
  private def expect(reply: Reply, method: String, target: String, body: String): Unit = {
    val wanted = s"$method\t${encoded(target)}\t${encoded(body)}"
    require(reply.status == 200 && reply.body == wanted, s"Wrong response for $target: $reply")
  }
  private def readReply(socket: Socket, capture: Option[ByteArrayOutputStream] = None): Reply = {
    val source = socket.getInputStream
    val in = new java.io.FilterInputStream(source) {
      override def read(): Int = {
        val value = source.read()
        if (value >= 0) capture.foreach(_.write(value))
        value
      }
      override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
        val count = source.read(bytes, offset, length)
        if (count > 0) capture.foreach(_.write(bytes, offset, count))
        count
      }
    }
    def line(): String = {
      val bytes = new ByteArrayOutputStream
      var previous = -1
      var finished = false
      while (!finished) {
        val next = in.read()
        if (next < 0) throw new EOFException("Truncated response")
        bytes.write(next)
        finished = previous == '\r' && next == '\n'
        previous = next
        require(bytes.size() <= 16384, "Response line too large")
      }
      new String(bytes.toByteArray.dropRight(2), ISO_8859_1)
    }
    val status = line().split(" ")(1).toInt
    val headers = scala.collection.mutable.Map.empty[String, String]
    var field = line()
    while (field.nonEmpty) {
      val colon = field.indexOf(':')
      require(colon > 0, "Malformed response header")
      headers(field.take(colon).toLowerCase(java.util.Locale.ROOT)) = field.drop(colon + 1).trim
      field = line()
    }
    val body = new ByteArrayOutputStream
    def take(count: Int): Unit = {
      require(count >= 0 && count <= 65536 && body.size() + count <= 65536, "Response body too large")
      val bytes = in.readNBytes(count)
      require(bytes.length == count, "Truncated response body")
      body.write(bytes)
    }
    if (headers.get("transfer-encoding").contains("chunked")) {
      var size = Integer.parseInt(line().takeWhile(_ != ';'), 16)
      while (size != 0) {
        take(size)
        require(line().isEmpty, "Invalid response chunk delimiter")
        size = Integer.parseInt(line().takeWhile(_ != ';'), 16)
      }
      require(line().isEmpty, "Unexpected response trailers")
    } else {
      headers.get("content-length") match {
        case Some(length) => take(length.toInt)
        case None         =>
          require(headers.get("connection").contains("close"), "Unframed response")
          var next = in.read()
          while (next >= 0) {
            body.write(next)
            require(body.size() <= 65536, "Response body too large")
            next = in.read()
          }
      }
    }
    Reply(status, new String(body.toByteArray, UTF_8))
  }
}
