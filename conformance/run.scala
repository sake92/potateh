//> using scala 3.9.0
//> using dep com.lihaoyi::mainargs:0.7.8
//> using dep com.lihaoyi::ujson:4.4.1
//> using dep org.apache.ant:ant:1.10.15
//> using file SlowClients.scala

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import mainargs.{arg, main, ParserForMethods}
import org.apache.tools.ant.types.Commandline
import scala.collection.mutable
import scala.util.control.NonFatal

object ConformanceRunner {
  private val pins = Map(
    "h1spec" -> "f0a5650a20c575fbea0f7179a3a9cfa50f20ba6e",
    "http11probe" -> "d50d818a0e008e9feb3bb9860ad5d9cc40342bc3",
    "slowhttptest" -> "17e8bd195e8c7fd3e0e925686c051e4d591c838f"
  )
  private val categories = Seq(
    "Compliance",
    "Smuggling",
    "MalformedInput",
    "Normalization",
    "Capabilities"
  )

  @main
  def run(
      @arg(doc =
        "h1spec, http11probe, slowclients or slowhttptest"
      ) suite: String,
      @arg(doc =
        "External suite checkout at the pinned commit; not needed for slowclients"
      ) checkout: String = "",
      @arg(doc =
        "Server command; single/double quotes group arguments, no shell expansion"
      ) server: String,
      @arg(doc = "New results directory; must not exist") output: String,
      deno: String = "deno",
      dotnet: String = "dotnet",
      @arg(
        name = "probe-dll",
        doc = "Built Http11Probe.Cli.dll"
      ) probeDll: Option[String] = None,
      @arg(doc =
        "Repeatable Http11Probe category; default: all core categories"
      ) category: Seq[String] = Seq.empty,
      @arg(doc =
        "Maximum seconds per external probe invocation"
      ) timeout: Double = 900,
      @arg(doc =
        "Slow-client admission: both, roomy or limited"
      ) capacity: String = "both",
      @arg(doc =
        "Slow-client observation seconds, 3–30; idle-drip always runs 32 seconds"
      ) duration: Int = 3,
      @arg(
        name = "case",
        doc = "Repeatable slow-client scenario; omit to run all"
      ) cases: Seq[String] = Seq.empty,
      @arg(
        name = "idle-drip-expectation",
        doc =
          "32-second drip result: observe, close or survive; slowclients only"
      ) idleDripExpectation: String = "observe"
  ): Int = {
    try {
      require(
        pins.contains(suite) || suite == "slowclients",
        "Unknown --suite; see --help"
      )
      val slow = suite == "slowclients" || suite == "slowhttptest"
      require(
        Set("both", "roomy", "limited").contains(capacity),
        "Unknown --capacity"
      )
      require(
        duration >= 3 && duration <= 30,
        "--duration must be between 3 and 30 seconds"
      )
      require(
        cases.forall(SlowClients.scenarios.contains),
        "Unknown --case; see conformance/README.md"
      )
      require(
        cases.isEmpty || suite == "slowclients",
        "--case requires --suite slowclients"
      )
      require(
        Set("observe", "close", "survive").contains(idleDripExpectation),
        "--idle-drip-expectation must be observe, close or survive"
      )
      require(
        idleDripExpectation == "observe" ||
          (suite == "slowclients" && (cases.isEmpty || cases
            .contains("idle-drip"))),
        "--idle-drip-expectation requires a slowclients run containing idle-drip"
      )
      require(
        slow || (capacity == "both" && duration == 3),
        "--capacity and --duration require a slow-client suite"
      )
      require(
        timeout.isFinite && timeout > 0,
        "--timeout must be finite and positive"
      )
      require(
        category.forall(categories.contains),
        "Unknown --category; see conformance/README.md"
      )
      require(
        category.isEmpty || suite == "http11probe",
        "--category requires --suite http11probe"
      )
      require(
        suite != "http11probe" || probeDll.exists(p =>
          Files.isRegularFile(Path.of(p))
        ),
        "--probe-dll must name a built Http11Probe.Cli.dll"
      )
      val command = Commandline.translateCommandline(server).toSeq
      require(command.nonEmpty, "--server must contain a command")
      val source = Path.of(checkout)
      val destination = Path.of(output)
      require(
        !Files.exists(destination),
        s"Results directory already exists: $destination; choose a new --output"
      )
      val revision =
        if (suite == "slowclients")
          capture(Seq("git", "rev-parse", "HEAD")).trim
        else {
          require(
            checkout.nonEmpty,
            "--checkout is required for external suites"
          )
          val found = capture(
            Seq("git", "-C", source.toString, "rev-parse", "HEAD")
          ).trim
          require(
            found == pins(suite),
            s"Expected $suite commit ${pins(suite)}, found $found"
          )
          val dirty = capture(
            Seq(
              "git",
              "-C",
              source.toString,
              "status",
              "--porcelain",
              "--untracked-files=no"
            )
          )
          require(
            dirty.isEmpty,
            s"External suite has tracked modifications: $source; use an unchanged checkout"
          )
          if (suite == "slowhttptest")
            require(
              Files.isExecutable(source.resolve("src/slowhttptest")),
              "Build --checkout/src/slowhttptest first; see conformance/README.md"
            )
          found
        }
      Option(destination.getParent).foreach(Files.createDirectories(_))
      Files.createDirectory(destination)
      if (slow)
        SlowClients.run(
          suite,
          source,
          command,
          destination,
          capacity,
          duration,
          cases,
          timeout,
          revision,
          idleDripExpectation
        )
      else
        execute(
          suite,
          source,
          command,
          destination,
          deno,
          dotnet,
          probeDll,
          category,
          timeout,
          revision
        )
    } catch {
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        System.err.println("Conformance run interrupted")
        130
      case NonFatal(error) =>
        System.err.println(s"Conformance run failed: ${error.getMessage}")
        1
    }
  }

  def main(args: Array[String]): Unit = {
    val hook = new Thread(() => Children.shutdown(), "conformance-cleanup")
    Runtime.getRuntime.addShutdownHook(hook)
    val code = try {
      ParserForMethods(this).runEither(args.toSeq) match {
        case Right(code: Int) => code
        case Right(_)         => 0
        case Left(message)    =>
          System.err.println(message)
          2
      }
    } finally Children.shutdown()
    System.exit(code)
  }

  private def execute(
      suite: String,
      source: Path,
      command: Seq[String],
      destination: Path,
      deno: String,
      dotnet: String,
      probeDll: Option[String],
      category: Seq[String],
      timeout: Double,
      revision: String
  ): Int = {
    val runs = ujson.Arr()
    val metadata = ujson.Obj(
      "suite" -> suite,
      "revision" -> revision,
      "server" -> ujson.Arr.from(command),
      "runs" -> runs
    )
    val serverLog = destination.resolve("server.log")
    val process = Children.start(
      command,
      serverLog,
      Map("HTTP_CONFORMANCE_PROFILE" -> suite)
    )
    try {
      val port = awaitPort(process, serverLog)
      metadata("port") = port.toInt
      val probes = if (suite == "h1spec") {
        Seq(
          "h1spec" -> Seq(
            deno,
            "run",
            "--allow-net=127.0.0.1",
            source.resolve("http_test.ts").toString,
            "127.0.0.1",
            port
          )
        )
      } else {
        val selected = if (category.isEmpty) categories else category.distinct
        selected.map { name =>
          name -> Seq(
            dotnet,
            probeDll.getOrElse(
              throw new IllegalArgumentException("Missing --probe-dll")
            ),
            "--host",
            "127.0.0.1",
            "--port",
            port,
            "--category",
            name,
            "--output",
            destination.resolve(s"$name.json").toString
          )
        }
      }
      probes.foreach { case (name, probe) =>
        val code = runProbe(probe, destination.resolve(s"$name.log"), timeout)
        val result = ujson.Obj(
          "name" -> name,
          "command" -> ujson.Arr.from(probe),
          "exit_code" -> code
        )
        // This upstream CLI exits zero for failed probes; inspect the original JSON verdicts too.
        if (suite == "http11probe" && code == 0) {
          val report =
            ujson.read(Files.readString(destination.resolve(s"$name.json")))
          val failed = report("results").arr.count { result =>
            Set("Fail", "Error").contains(result("verdict").str)
          }
          result("failed") = failed
        }
        runs.value += result
        val failures = result.obj
          .get("failed")
          .map(_.num.toInt.toString)
          .getOrElse("see log")
        System.err.println(
          s"$name: exit $code, failed probes $failures; results in $destination"
        )
        require(process.isAlive, s"Server exited during probes; see $serverLog")
      }
      val failed = runs.value.exists { run =>
        run("exit_code").num != 0 || run.obj.get("failed").exists(_.num > 0)
      }
      if (failed) 1 else 0
    } finally {
      try Children.stop(process)
      finally
        Files.writeString(
          destination.resolve("run.json"),
          ujson.write(metadata, indent = 2) + "\n"
        )
    }
  }

  def awaitPort(process: Process, log: Path): String = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    val listening = "(?m)^LISTENING (\\d+)$".r
    while (System.nanoTime() < deadline) {
      require(process.isAlive, s"Server exited before readiness; see $log")
      listening.findFirstMatchIn(Files.readString(log)) match {
        case Some(found) => return found.group(1)
        case None        => Thread.sleep(50)
      }
    }
    throw new IllegalStateException(
      s"Server did not become ready within 30 seconds; see $log"
    )
  }

  private def runProbe(
      command: Seq[String],
      log: Path,
      timeout: Double
  ): Int = {
    val process = Children.start(command, log)
    try {
      val milliseconds = math.max(1L, (timeout * 1000).toLong)
      if (process.waitFor(milliseconds, TimeUnit.MILLISECONDS))
        process.exitValue()
      else 124
    } finally Children.stop(process)
  }

  private def capture(command: Seq[String]): String = {
    val log = Files.createTempFile("http-conformance-", ".log")
    try {
      val code = runProbe(command, log, 10)
      val text = Files.readString(log)
      require(code == 0, s"${command.mkString(" ")} exited $code: $text")
      text
    } finally Files.deleteIfExists(log)
  }

  object Children {
    private val lock = new Object()
    private val owned = mutable.LinkedHashSet.empty[Process]
    private var closing = false

    def start(
        command: Seq[String],
        log: Path,
        env: Map[String, String] = Map.empty
    ): Process = lock.synchronized {
      require(!closing, "Runner is shutting down")
      // A new session contains descendants, so cleanup also reaches children of a crashed leader.
      val builder = new ProcessBuilder((Seq("setsid") ++ command)*)
        .redirectInput(Path.of("/dev/null").toFile)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile)
      env.foreach { case (key, value) => builder.environment().put(key, value) }
      val process = builder.start()
      owned += process
      process
    }

    def shutdown(): Unit = {
      val processes = lock.synchronized {
        closing = true
        owned.toVector
      }
      processes.reverseIterator.foreach(stop)
    }

    def stop(process: Process): Unit = process.synchronized {
      if (!lock.synchronized(owned.contains(process))) return
      var interrupted = Thread.interrupted()
      def waitBounded(child: Process, milliseconds: Long): Boolean = {
        val deadline =
          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
        while (child.isAlive && System.nanoTime() < deadline) {
          val remaining = math.max(1L, deadline - System.nanoTime())
          try child.waitFor(remaining, TimeUnit.NANOSECONDS)
          catch { case _: InterruptedException => interrupted = true }
        }
        !child.isAlive
      }
      def signal(name: String): Unit = {
        val sender =
          new ProcessBuilder("kill", s"-$name", "--", s"-${process.pid()}")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!waitBounded(sender, 1000)) {
          sender.destroyForcibly()
          require(
            waitBounded(sender, 1000),
            "Could not reap process-group signal command"
          )
        }
      }
      try {
        signal("TERM")
        waitBounded(process, 3000)
        signal("KILL")
        require(
          waitBounded(process, 3000),
          s"Could not reap child process ${process.pid()}"
        )
      } finally {
        lock.synchronized { owned -= process }
        if (interrupted) Thread.currentThread().interrupt()
      }
    }
  }
}
