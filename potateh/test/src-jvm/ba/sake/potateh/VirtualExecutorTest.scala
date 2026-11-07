package ba.sake.potateh

import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

class VirtualExecutorTest extends munit.FunSuite {
  test("JVM virtual executor serves connections and remains caller-owned") {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val virtual = new AtomicBoolean
    val config = ServerConfig(bindAddress = new InetSocketAddress("127.0.0.1", 0))
    val server = Server.start(config, executor) { _ =>
      virtual.set(Thread.currentThread().isVirtual)
      Response(body = ResponseBody.bytes("virtual".getBytes(US_ASCII)))
    }
    val socket = new Socket()
    try {
      socket.connect(server.address, 1000)
      socket.setSoTimeout(3000)
      socket.getOutputStream.write(
        "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(US_ASCII)
      )
      val wire = new String(socket.getInputStream.readAllBytes(), US_ASCII)
      assert(wire.startsWith("HTTP/1.1 200 "))
      assert(wire.endsWith("\r\n\r\nvirtual"))
      assert(virtual.get())
      server.stop()
      val usable = new CountDownLatch(1)
      executor.execute(() => usable.countDown())
      assert(usable.await(3, TimeUnit.SECONDS))
      assert(!executor.isShutdown)
    } finally {
      socket.close()
      server.stop()
      executor.shutdownNow()
      assert(executor.awaitTermination(3, TimeUnit.SECONDS))
    }
  }
}
