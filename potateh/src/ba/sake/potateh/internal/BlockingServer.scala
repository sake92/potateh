package ba.sake.potateh.internal

import ba.sake.potateh.*
import java.io.{
  BufferedInputStream,
  BufferedOutputStream,
  IOException,
  InputStream,
  PushbackInputStream
}
import java.net.{
  InetSocketAddress,
  ServerSocket,
  Socket,
  SocketTimeoutException
}
import scala.collection.mutable
import scala.util.control.NonFatal

/** One platform worker per admitted connection; no accepted-socket work queue.
  */
private[potateh] final class BlockingServer(
    listener: ServerSocket,
    config: ServerConfig,
    handle: Request => Response
) {
  private final class Connection(val socket: Socket) {
    var active =
      false // guarded by lifecycle, including idle/head -> exchange handover
    val thread = new Thread(() => serve(this), "potateh-connection")
  }
  private enum State { case Running, Stopping, Stopped }
  private val lifecycle = new Object
  private var state = State.Running
  private var stopOwner: Option[Thread] = None
  private val connections = mutable.Set.empty[Connection]
  // Retain handles until thread termination, including final cleanup outside serve.
  private val workers = mutable.Set.empty[Thread]
  private val writes = new WriteTimeouts(config.timeouts.write.toNanos)
  private val acceptThread = new Thread(() => accept(), "potateh-accept")
  val address: InetSocketAddress =
    listener.getLocalSocketAddress().asInstanceOf[InetSocketAddress]

  def start(): Unit = {
    try {
      writes.thread.start()
      acceptThread.start()
    } catch { case error: Throwable => stop(); throw error }
  }

  def stop(): Unit = {
    val self = Thread.currentThread()
    var interrupted = Thread.interrupted()
    val deadline = lifecycle.synchronized {
      if (state == State.Stopped) { if (interrupted) self.interrupt(); return }
      if (state == State.Stopping) {
        // A worker must return to let an existing stop caller finish its exchange.
        if (workers.contains(self) || stopOwner.contains(self)) {
          if (interrupted) self.interrupt(); return
        }
        while (state != State.Stopped) {
          try lifecycle.wait()
          catch { case _: InterruptedException => interrupted = true }
        }
        if (interrupted) self.interrupt()
        return
      }
      state = State.Stopping
      stopOwner = Some(self)
      closeListener()
      connections.filterNot(_.active).foreach(c => close(c.socket))
      lifecycle.notifyAll()
      System.nanoTime() + config.timeouts.shutdownGrace.toNanos
    }
    try {
      lifecycle.synchronized {
        while (
          connections.exists(c =>
            c.active && (c.thread ne self)
          ) && deadline - System.nanoTime() > 0
        ) {
          try lifecycle.wait(millis(deadline - System.nanoTime()))
          catch { case _: InterruptedException => interrupted = true }
        }
        connections.foreach(c => close(c.socket))
      }
      writes.stop()
      val owned = lifecycle.synchronized {
        workers.toVector ++ Vector(acceptThread, writes.thread)
      }
      owned.filterNot(_ eq self).foreach { thread =>
        while (thread.isAlive && deadline - System.nanoTime() > 0) {
          try thread.join(millis(deadline - System.nanoTime()))
          catch { case _: InterruptedException => interrupted = true }
        }
      }
    } finally {
      lifecycle.synchronized {
        state = State.Stopped; stopOwner = None; lifecycle.notifyAll()
      }
      if (interrupted) self.interrupt()
    }
  }

  private def running: Boolean = lifecycle.synchronized(state == State.Running)
  private def accept(): Unit = {
    try {
      while (running) {
        val socket = listener.accept()
        lifecycle.synchronized {
          workers.filterInPlace(_.isAlive)
          if (
            state != State.Running || connections.size >= config.maxConnections
          ) close(socket)
          else {
            val connection = new Connection(socket)
            connections += connection
            workers += connection.thread
            try connection.thread.start()
            catch {
              case error: Throwable =>
                connections -= connection; workers -= connection.thread;
                close(socket); throw error
            }
          }
        }
      }
    } catch {
      case _: IOException if !running => ()
      case NonFatal(_)                => stop()
    } finally { closeListener(); if (running) stop() }
  }

  private def serve(connection: Connection): Unit = {
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
        if (first)
          socket.setSoTimeout(millis(config.timeouts.head.toNanos).toInt)
        val next = input.read()
        if (next == -1) return
        input.unread(next)
        val deadline =
          if (first) headStart + config.timeouts.head.toNanos
          else System.nanoTime() + config.timeouts.head.toNanos
        first = false
        val headInput = new InputStream {
          override def read(): Int = {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0)
              throw new SocketTimeoutException("Request head deadline exceeded")
            socket.setSoTimeout(millis(remaining).toInt)
            val byte = input.read()
            if (deadline - System.nanoTime() <= 0)
              throw new SocketTimeoutException("Request head deadline exceeded")
            byte
          }
        }
        val limits = config.limits
        val head = try
          RequestHeadParser.read(
            headInput,
            RequestHeadParser.Limits(
              limits.requestLineBytes,
              limits.headerLineBytes,
              limits.headerSectionBytes,
              limits.headerCount
            )
          )
        catch {
          case error: RequestHeadParser.ParseException =>
            reject(socket, output, error.responseCode); return
          case _: SocketTimeoutException => reject(socket, output, 408); return
        }
        head match {
          case None        => return
          case Some(value) =>
            val admitted = lifecycle.synchronized {
              if (state != State.Running) false
              else { connection.active = true; true }
            }
            if (!admitted) return
            try
              reuse =
                new Exchange(socket, input, output, value, config, handle).run()
            finally
              lifecycle.synchronized {
                connection.active = false; lifecycle.notifyAll()
              }
        }
      }
    } catch { case NonFatal(_) => () }
    finally {
      close(socket)
      lifecycle.synchronized {
        connections -= connection; lifecycle.notifyAll()
      }
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
  private def millis(nanos: Long): Long =
    math.max(1L, (nanos + 999999L) / 1000000L)
}
