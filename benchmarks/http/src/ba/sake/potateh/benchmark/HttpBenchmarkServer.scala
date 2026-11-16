package ba.sake.potateh.benchmark

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Executors, TimeUnit}

import ba.sake.potateh.*

object HttpBenchmarkServer {

  def main(args: Array[String]): Unit = {
    val port = intEnv("POTATEH_PORT", 18080, minimum = 0)
    val workers = intEnv("POTATEH_THREADS", 16)
    val maxConnections = intEnv("POTATEH_MAX_CONNECTIONS", workers)
    val backlog = intEnv("POTATEH_BACKLOG", maxConnections)
    val stopFile = Paths.get(sys.env("POTATEH_STOP_FILE"))
    require(!Files.exists(stopFile), s"Stop file already exists: $stopFile")

    val config = ServerConfig.Default
      .withBindAddress(new InetSocketAddress("127.0.0.1", port))
      .withMaxConnections(maxConnections)
      .withBacklog(backlog)
    val executor = Executors.newFixedThreadPool(
      workers,
      task => new Thread(task, "potateh-benchmark-worker")
    )
    val ok = Response(
      headers = Headers("Content-Type" -> "text/plain"),
      body = ResponseBody.bytes("ok".getBytes(US_ASCII))
    )
    val server = try {
      Server.start(config, executor)(_ => ok)
    } catch {
      case error: Throwable =>
        executor.shutdownNow()
        throw error
    }

    val stopped = new AtomicBoolean(false)
    def cleanup(): Unit = {
      if (stopped.compareAndSet(false, true)) {
        try server.stop()
        finally {
          executor.shutdownNow()
          executor.awaitTermination(5, TimeUnit.SECONDS)
        }
      }
    }

    val hook = new Thread(() => cleanup(), "benchmark-shutdown")
    Runtime.getRuntime.addShutdownHook(hook)
    try {
      println(
        s"LISTENING ${server.address.getPort} workers=$workers " +
          s"maxConnections=$maxConnections backlog=$backlog"
      )
      System.out.flush()
      while (!Files.exists(stopFile)) {
        Thread.sleep(100)
      }
    } finally {
      cleanup()
      try Runtime.getRuntime.removeShutdownHook(hook)
      catch { case _: IllegalStateException => () }
    }
  }

  private def intEnv(name: String, default: Int, minimum: Int = 1): Int = {
    val value = sys.env.get(name).map(_.toInt).getOrElse(default)
    require(value >= minimum, s"$name must be at least $minimum")
    value
  }
}
