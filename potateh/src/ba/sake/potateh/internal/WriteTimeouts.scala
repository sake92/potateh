package ba.sake.potateh.internal

import java.io.{IOException, OutputStream}
import java.net.{Socket, SocketTimeoutException}
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable

/** One server-owned watcher; deadlines cover socket writes, never handler computation.
  */
private[potateh] final class WriteTimeouts(timeoutNanos: Long) {

  private final class Pending(val socket: Socket, val deadline: Long)

  private val lock = new ReentrantLock
  private val changed = lock.newCondition()
  private val pending = mutable.Set.empty[Pending]
  private var stopped = false
  val thread: Thread = new Thread(() => watch(), "potateh-write-timeouts")

  def stop(): Unit = withLock {
    stopped = true
    changed.signalAll()
  }
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
    withLock {
      if (stopped) throw new IOException("Write watcher stopped")
      pending += write
      changed.signalAll()
    }
    try operation
    finally {
      val expired = withLock {
        pending -= write
        changed.signalAll()
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
      withLock {
        while (!stopped) {
          val now = System.nanoTime()
          val expired = pending.filter(_.deadline - now <= 0).toVector
          expired.foreach { write =>
            pending -= write
            close(write.socket)
          }
          val budgets = pending.iterator.map(_.deadline - now).toVector
          if (budgets.isEmpty) changed.await()
          else changed.awaitNanos(budgets.min)
        }
      }
    } catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    finally {
      withLock {
        stopped = true
        pending.foreach(write => close(write.socket))
        pending.clear()
      }
    }
  }
  private inline def withLock[A](inline operation: A): A = {
    lock.lock()
    try operation
    finally lock.unlock()
  }

  private def close(socket: Socket): Unit = try socket.close()
  catch { case _: IOException => () }
}
