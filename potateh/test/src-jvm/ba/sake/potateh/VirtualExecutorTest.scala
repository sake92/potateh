package ba.sake.potateh

import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

class VirtualExecutorTest extends munit.FunSuite {
  test("JVM virtual executor serves connections and remains caller-owned") {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val virtual = new AtomicBoolean
    val config = ServerConfig.Default.withBindAddress(new InetSocketAddress("127.0.0.1", 0))
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

  test("concurrent virtual-thread stop callers wait for an active response") {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val stopping = new CountDownLatch(2)
    val stopped = new CountDownLatch(2)
    val config = ServerConfig.Default.withBindAddress(new InetSocketAddress("127.0.0.1", 0))
    val server = Server.start(config, executor) { _ =>
      entered.countDown()
      val released = release.await(3, TimeUnit.SECONDS)
      assert(released)
      Response(body = ResponseBody.bytes("done".getBytes(US_ASCII)))
    }
    val socket = new Socket()
    try {
      socket.connect(server.address, 1000)
      socket.setSoTimeout(3000)
      socket.getOutputStream.write(
        "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(US_ASCII)
      )
      val handling = entered.await(3, TimeUnit.SECONDS)
      assert(handling)
      val callers = Vector.fill(2) {
        executor.submit(new Runnable {
          override def run(): Unit = {
            assert(Thread.currentThread().isVirtual)
            stopping.countDown()
            server.stop()
            stopped.countDown()
          }
        })
      }
      val bothStopping = stopping.await(3, TimeUnit.SECONDS)
      assert(bothStopping)
      val completedEarly = stopped.await(100, TimeUnit.MILLISECONDS)
      assert(!completedEarly)
      release.countDown()
      val wire = new String(socket.getInputStream.readAllBytes(), US_ASCII)
      assert(wire.startsWith("HTTP/1.1 200 "))
      assert(wire.endsWith("\r\n\r\ndone"))
      callers.foreach(_.get(3, TimeUnit.SECONDS))
      assertEquals(stopped.getCount, 0L)
      assert(!executor.isShutdown)
    } finally {
      release.countDown()
      socket.close()
      server.stop()
      executor.shutdownNow()
      assert(executor.awaitTermination(3, TimeUnit.SECONDS))
    }
  }

}
