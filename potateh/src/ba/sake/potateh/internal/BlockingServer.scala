package ba.sake.potateh.internal

import scala.util.boundary, boundary.break
import ba.sake.potateh.*
import java.io.{BufferedInputStream, BufferedOutputStream, IOException, InputStream, PushbackInputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket, SocketTimeoutException}
import scala.collection.mutable
import scala.util.control.NonFatal
import java.util.concurrent.{Executor, Executors, RejectedExecutionException, TimeUnit}
import java.util.concurrent.locks.ReentrantLock

/** One executor task per admitted connection, including queued connections. */
private[potateh] final class BlockingServer(
    listener: ServerSocket,
    config: ServerConfig,
    suppliedExecutor: Option[Executor],
    handle: Request => Response
) {

  private final class Connection(val socket: Socket) extends Runnable {
    var active = {
      false // guarded by lifecycle, including idle/head -> exchange handover
    }
    var thread: Option[Thread] = None // guarded by lifecycle

    override def run(): Unit = {
      val admitted = withLifecycle {
        if (state != State.Running || !connections.contains(this)) false
        else {
          thread = Some(Thread.currentThread())
          true
        }
      }
      if (admitted) {
        try serve(this)
        finally {
          withLifecycle {
            thread = None
            connections -= this
            lifecycleChanged.signalAll()
          }
        }
      }
    }
  }

  private enum State { case Running, Stopping, Stopped }

  private val lifecycle = new ReentrantLock
  private val lifecycleChanged = lifecycle.newCondition()
  private var state = State.Running
  private var stopOwner: Option[Thread] = None
  private val connections = mutable.Set.empty[Connection]
  private val (executor, ownedExecutor) = suppliedExecutor match {
    case Some(value) => (executor = value, ownedExecutor = None)
    case None        =>
      val pool = Executors.newCachedThreadPool(task => new Thread(task, "potateh-connection"))
      (executor = pool: Executor, ownedExecutor = Some(pool))
  }
  private val writes = new WriteTimeouts(config.timeouts.write.toNanos)
  private val acceptThread = new Thread(() => accept(), "potateh-accept")
  val address: InetSocketAddress = {
    listener.getLocalSocketAddress().asInstanceOf[InetSocketAddress]
  }

  def start(): Unit = {
    try {
      writes.thread.start()
      acceptThread.start()
    } catch {
      case error: Throwable =>
        stop()
        throw error
    }
  }

  def stop(): Unit = boundary {
    val self = Thread.currentThread()
    var interrupted = Thread.interrupted()
    val (deadline, fromConnection) = withLifecycle {
      if (state == State.Stopped) {
        if (interrupted) self.interrupt()
        break()
      }
      if (state == State.Stopping) {
        // A worker must return to let an existing stop caller finish its exchange.
        if (connections.exists(_.thread.contains(self)) || stopOwner.contains(self)) {
          if (interrupted) self.interrupt()
          break()
        }
        while (state != State.Stopped) {
          try lifecycleChanged.await()
          catch { case _: InterruptedException => interrupted = true }
        }
        if (interrupted) self.interrupt()
        break()
      }
      state = State.Stopping
      stopOwner = Some(self)
      closeListener()
      connections.filterNot(_.active).foreach(c => close(c.socket))
      // Disable queued tasks even if they remain in a caller's executor.
      connections.filterInPlace(_.thread.nonEmpty)
      lifecycleChanged.signalAll()
      (
        deadline = System.nanoTime() + config.timeouts.shutdownGrace.toNanos,
        fromConnection = connections.exists(_.thread.contains(self))
      )
    }
    try {
      ownedExecutor.foreach(_.shutdown())
      withLifecycle {
        while (connections.exists(c => c.active && !c.thread.contains(self)) && deadline - System.nanoTime() > 0) {
          try lifecycleChanged.awaitNanos(deadline - System.nanoTime())
          catch { case _: InterruptedException => interrupted = true }
        }
        connections.foreach(c => close(c.socket))
      }
      writes.stop()
      ownedExecutor match {
        case Some(pool) if !fromConnection =>
          while (!pool.isTerminated && deadline - System.nanoTime() > 0) {
            try {
              pool.awaitTermination(
                math.max(0L, deadline - System.nanoTime()),
                TimeUnit.NANOSECONDS
              )
            } catch { case _: InterruptedException => interrupted = true }
          }
        case _ =>
          // Shared executors stay open; callback stop must exclude its own task.
          withLifecycle {
            while (
              connections.exists(c => c.thread.exists(_ ne self)) &&
              deadline - System.nanoTime() > 0
            ) {
              try lifecycleChanged.awaitNanos(deadline - System.nanoTime())
              catch { case _: InterruptedException => interrupted = true }
            }
          }
      }
      Vector(acceptThread, writes.thread).filterNot(_ eq self).foreach { thread =>
        while (thread.isAlive && deadline - System.nanoTime() > 0) {
          try thread.join(millis(deadline - System.nanoTime()))
          catch { case _: InterruptedException => interrupted = true }
        }
      }
    } finally {
      withLifecycle {
        state = State.Stopped
        stopOwner = None
        lifecycleChanged.signalAll()
      }
      if (interrupted) self.interrupt()
    }
  }

  private inline def withLifecycle[A](inline operation: A): A = {
    lifecycle.lock()
    try operation
    finally lifecycle.unlock()
  }

  private def running: Boolean = withLifecycle(state == State.Running)
  private def accept(): Unit = {
    try {
      while (running) {
        val socket = listener.accept()
        val admitted = withLifecycle {
          if (state != State.Running || connections.size >= config.maxConnections) None
          else {
            val connection = new Connection(socket)
            connections += connection
            Some(connection)
          }
        }
        admitted match {
          case None             => close(socket)
          case Some(connection) =>
            // Executors may run inline; never hold lifecycle while submitting.
            try executor.execute(connection)
            catch {
              case error: Throwable =>
                close(socket)
                withLifecycle {
                  connections -= connection
                  lifecycleChanged.signalAll()
                }
                error match {
                  case _: RejectedExecutionException => ()
                  case _                             => throw error
                }
            }
        }
      }
    } catch {
      case _: IOException if !running => ()
      case NonFatal(_)                => stop()
    } finally {
      closeListener()
      if (running) stop()
    }
  }

  private def serve(connection: Connection): Unit = boundary {
    val socket = connection.socket
    try {
      socket.setTcpNoDelay(true)
      val input = new PushbackInputStream(
        new BufferedInputStream(socket.getInputStream()),
        1
      )
      val output = new BufferedOutputStream(writes.output(socket))
      var first = true
      var reuse = true
      while (reuse && running) {
        val headStart = System.nanoTime()
        socket.setSoTimeout(millis(config.timeouts.idle.toNanos).toInt)
        // Initial silent sockets are bounded by the absolute head deadline.
        if (first) {
          socket.setSoTimeout(millis(config.timeouts.head.toNanos).toInt)
        }
        val next = input.read()
        if (next == -1) break()
        input.unread(next)
        val deadline = {
          if (first) headStart + config.timeouts.head.toNanos
          else System.nanoTime() + config.timeouts.head.toNanos
        }
        first = false
        val headInput = new InputStream {
          private var readTimeoutMillis = 0

          override def read(): Int = {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
              throw new SocketTimeoutException("Request head deadline exceeded")
            }
            val timeoutMillis = millis(remaining).toInt
            // Native sets SO_RCVTIMEO on every call, even when the value is unchanged.
            if (timeoutMillis != readTimeoutMillis) {
              socket.setSoTimeout(timeoutMillis)
              readTimeoutMillis = timeoutMillis
            }
            val byte = input.read()
            if (deadline - System.nanoTime() <= 0) {
              throw new SocketTimeoutException("Request head deadline exceeded")
            }
            byte
          }
        }
        val limits = config.limits
        val head = try {
          RequestHeadParser.read(
            headInput,
            RequestHeadParser.Limits(
              limits.requestLineBytes,
              limits.headerLineBytes,
              limits.headerSectionBytes,
              limits.headerCount
            )
          )
        } catch {
          case error: RequestHeadParser.ParseException =>
            reject(socket, output, error.responseCode)
            break()
          case _: SocketTimeoutException =>
            reject(socket, output, 408)
            break()
        }
        head match {
          case None        => break()
          case Some(value) =>
            val admitted = withLifecycle {
              if (state != State.Running) false
              else {
                connection.active = true
                true
              }
            }
            if (!admitted) break()
            try {
              reuse = new Exchange(socket, input, output, value, config, handle).run()
            } finally {
              withLifecycle {
                connection.active = false
                lifecycleChanged.signalAll()
              }
            }
        }
      }
    } catch { case NonFatal(_) => () }
    finally {
      close(socket)
    }
  }

  private def reject(
      socket: Socket,
      output: java.io.OutputStream,
      code: Int
  ): Unit = {
    try {
      output.write(
        ResponseHeadWriter
          .prepare("GET", "HTTP/1.1", Response(code), true)
          .bytes
      )
      output.flush()
    } finally close(socket)
  }
  private def closeListener(): Unit = try listener.close()
  catch { case _: IOException => () }
  private def close(socket: Socket): Unit = try socket.close()
  catch { case _: IOException => () }
  private def millis(nanos: Long): Long = {
    math.max(1L, (nanos + 999999L) / 1000000L)
  }
}
