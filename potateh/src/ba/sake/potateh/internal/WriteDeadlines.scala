package ba.sake.potateh.internal

import java.io.{IOException, OutputStream}
import java.net.{Socket, SocketTimeoutException}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{RejectedExecutionException, ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}

/** One server-owned scheduler; deadlines cover individual socket writes, never handler computation.
  */
private[potateh] final class WriteDeadlines(timeoutNanos: Long) {

  private val scheduler = new ScheduledThreadPoolExecutor(
    1,
    task => {
      val thread = new Thread(task, "potateh-write-deadlines")
      thread.setDaemon(true)
      thread
    }
  )
  scheduler.setRemoveOnCancelPolicy(true)

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
    val completed = new AtomicBoolean(false)
    val task: Runnable = () => {
      if (completed.compareAndSet(false, true)) close(socket)
    }
    val deadline = try {
      val future = scheduler.schedule(task, timeoutNanos, TimeUnit.NANOSECONDS)
      new WriteDeadlines.Deadline(completed, future)
    } catch {
      case _: RejectedExecutionException =>
        throw new IOException("Write deadline scheduler stopped")
    }
    try operation
    finally {
      val expired = !deadline.finish()
      if (expired) throw new SocketTimeoutException("Socket write deadline exceeded")
    }
  }

  def stop(): Unit = scheduler.shutdownNow()

  private def close(socket: Socket): Unit = try socket.close()
  catch { case _: IOException => () }

}

private[potateh] object WriteDeadlines {

  final class Deadline private[WriteDeadlines] (
      completed: AtomicBoolean,
      future: ScheduledFuture[?]
  ) {

    def finish(): Boolean = {
      val finished = completed.compareAndSet(false, true)
      if (finished) future.cancel(false)
      finished
    }
  }

}
