package ba.sake.potateh.internal

import java.io.{IOException, OutputStream}
import java.net.{Socket, SocketTimeoutException}
import scala.collection.mutable

/** One server-owned watcher; deadlines cover socket writes, never handler computation.
  */
private[potateh] final class WriteTimeouts(timeoutNanos: Long) {

  private final class Pending(val socket: Socket, val deadline: Long)

  private val lock = new Object
  private val pending = mutable.Set.empty[Pending]
  private var stopped = false
  val thread: Thread = new Thread(() => watch(), "potateh-write-timeouts")

  def stop(): Unit = lock.synchronized { stopped = true; lock.notifyAll() }
  def output(socket: Socket): OutputStream = {
    val raw = socket.getOutputStream()
    new OutputStream {
      override def write(value: Int): Unit = timed(socket)(raw.write(value))
      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
        timed(socket)(raw.write(bytes, offset, length))
      }
      override def flush(): Unit = timed(socket)(raw.flush())
    }
  }
  private def timed(socket: Socket)(operation: => Unit): Unit = {
    val write = new Pending(socket, System.nanoTime() + timeoutNanos)
    lock.synchronized {
      if (stopped) throw new IOException("Write watcher stopped")
      pending += write
      lock.notifyAll()
    }
    try operation
    finally {
      val expired = lock.synchronized {
        pending -= write
        lock.notifyAll()
        write.deadline - System.nanoTime() <= 0
      }
      if (expired) {
        close(socket)
        throw new SocketTimeoutException("Socket write deadline exceeded")
      }
    }
  }
  private def watch(): Unit = {
    try {
      lock.synchronized {
        while (!stopped) {
          val now = System.nanoTime()
          val expired = pending.filter(_.deadline - now <= 0).toVector
          expired.foreach { write => pending -= write; close(write.socket) }
          val budgets = pending.iterator.map(_.deadline - now).toVector
          if (budgets.isEmpty) lock.wait()
          else lock.wait(math.max(1L, (budgets.min + 999999L) / 1000000L))
        }
      }
    } catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    finally {
      lock.synchronized {
        stopped = true
        pending.foreach(write => close(write.socket))
        pending.clear()
      }
    }
  }
  private def close(socket: Socket): Unit = try socket.close()
  catch { case _: IOException => () }
}
