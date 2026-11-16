package ba.sake.potateh.internal

import java.io.{BufferedOutputStream, IOException}
import java.net.{InetSocketAddress, ServerSocket, Socket, SocketTimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ConcurrentHashMap, Executor, Executors, RejectedExecutionException, TimeUnit}

import scala.jdk.CollectionConverters.*
import scala.util.boundary
import scala.util.control.NonFatal

import boundary.break
import ba.sake.potateh.*

/** One executor task per admitted connection, including queued connections. */
private[potateh] final class BlockingServer(
    listener: ServerSocket,
    config: ServerConfig,
    suppliedExecutor: Option[Executor],
    handle: Request => Response
) {

  private final class Connection(val socket: Socket) extends Runnable {
    lazy val localAddress: InetSocketAddress = {
      socket.getLocalSocketAddress().asInstanceOf[InetSocketAddress]
    }
    lazy val remoteAddress: InetSocketAddress = {
      socket.getRemoteSocketAddress().asInstanceOf[InetSocketAddress]
    }
    @volatile var phase: ConnectionPhase = ConnectionPhase.Queued
    @volatile var owner: Thread = null

    override def run(): Unit = {
      owner = Thread.currentThread()
      val admitted = phase == ConnectionPhase.Queued && running
      if (admitted) {
        phase = ConnectionPhase.Idle
        try serve(this)
        finally {
          phase = ConnectionPhase.Closed
          removeConnection(this)
          owner = null
          signalLifecycle()
        }
      } else {
        phase = ConnectionPhase.Closed
        removeConnection(this)
        close(socket)
        owner = null
        signalLifecycle()
      }
    }

  }

  private enum State { case Running, Stopping, Stopped }

  private enum ConnectionPhase { case Queued, Idle, Active, Closed }

  private val lifecycle = new ReentrantLock
  private val lifecycleChanged = lifecycle.newCondition()
  @volatile private var state = State.Running
  private var stopOwner: Thread = null
  private val connections = ConcurrentHashMap.newKeySet[Connection]()
  private val connectionCount = new AtomicInteger

  private val (executor, ownedExecutor) = suppliedExecutor match {
    case Some(value) => (executor = value, ownedExecutor = None)
    case None        =>
      val pool = Executors.newCachedThreadPool(task => new Thread(task, "potateh-connection"))
      (executor = pool: Executor, ownedExecutor = Some(pool))
  }

  private val writeDeadlines = Option.when(config.timeouts.writeTimeout.toNanos > 0) {
    new WriteDeadlines(config.timeouts.writeTimeout.toNanos)
  }
  private val acceptThread = new Thread(() => accept(), "potateh-accept")

  val address: InetSocketAddress = {
    listener.getLocalSocketAddress().asInstanceOf[InetSocketAddress]
  }

  def start(): Unit = {
    try {
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
        if (anyConnection(_.owner == self) || stopOwner == self) {
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
      stopOwner = self
      closeListener()
      connections.asScala.foreach { connection =>
        val phase = connection.phase
        if (phase == ConnectionPhase.Queued || phase == ConnectionPhase.Idle) {
          connection.phase = ConnectionPhase.Closed
          close(connection.socket)
          if (phase == ConnectionPhase.Queued) removeConnection(connection)
        }
      }
      lifecycleChanged.signalAll()
      (
        deadline = System.nanoTime() + config.timeouts.shutdownGrace.toNanos,
        fromConnection = anyConnection(_.owner == self)
      )
    }
    try {
      ownedExecutor.foreach(_.shutdown())
      withLifecycle {
        while (
          anyConnection(c => c.phase == ConnectionPhase.Active && c.owner != self) &&
          deadline - System.nanoTime() > 0
        ) {
          try lifecycleChanged.awaitNanos(deadline - System.nanoTime())
          catch { case _: InterruptedException => interrupted = true }
        }
        connections.asScala.foreach(c => close(c.socket))
      }
      writeDeadlines.foreach(_.stop())
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
              anyConnection(c => c.owner != null && c.owner != self) &&
              deadline - System.nanoTime() > 0
            ) {
              try lifecycleChanged.awaitNanos(deadline - System.nanoTime())
              catch { case _: InterruptedException => interrupted = true }
            }
          }
      }
      val ownedThreads = Vector(acceptThread)
      ownedThreads.filterNot(_ eq self).foreach { thread =>
        while (thread.isAlive && deadline - System.nanoTime() > 0) {
          try thread.join(millis(deadline - System.nanoTime()))
          catch { case _: InterruptedException => interrupted = true }
        }
      }
    } finally {
      withLifecycle {
        state = State.Stopped
        stopOwner = null
        lifecycleChanged.signalAll()
      }
      if (interrupted) self.interrupt()
    }
  }

  // TODO withLock
  private inline def withLifecycle[A](inline operation: A): A = {
    lifecycle.lock()
    try operation
    finally lifecycle.unlock()
  }

  private def signalLifecycle(): Unit = withLifecycle(lifecycleChanged.signalAll())

  private def running: Boolean = state == State.Running

  private def accept(): Unit = {
    try {
      while (running) {
        val socket = listener.accept()
        val admitted = withLifecycle {
          if (state != State.Running || connectionCount.get() >= config.maxConnections) None
          else {
            val connection = new Connection(socket)
            connections.add(connection)
            connectionCount.incrementAndGet()
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
                  connection.phase = ConnectionPhase.Closed
                  removeConnection(connection)
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

  private def serve(connection: Connection): Unit = {
    val socket = connection.socket
    try {
      boundary {
        socket.setTcpNoDelay(true)
        val input = new ConnectionInput(socket, socket.getInputStream())
        val socketOutput = writeDeadlines match {
          case Some(deadlines) => deadlines.output(socket)
          case None            => socket.getOutputStream()
        }
        val output = new BufferedOutputStream(socketOutput)
        var first = true
        var reuse = true
        while (reuse && running) {
          socket.setSoTimeout(millis(config.timeouts.idleTimeout.toNanos).toInt)
          // Initial silent sockets are bounded by the absolute head deadline.
          val headStart = System.nanoTime()
          val initialTimeout = {
            if (first) millis(config.timeouts.requestHeadTimeout.toNanos).toInt
            else millis(config.timeouts.idleTimeout.toNanos).toInt
          }
          val next = input.peek(initialTimeout)
          if (next == -1) break()
          val deadline = {
            if (first) headStart + config.timeouts.requestHeadTimeout.toNanos
            else System.nanoTime() + config.timeouts.requestHeadTimeout.toNanos
          }
          first = false
          input.beginHeadDeadline(deadline)
          val limits = config.limits
          val head = try {
            RequestHeadParser.read(
              input,
              RequestHeadParser.Limits(
                limits.maxRequestLineBytes,
                limits.maxHeaderLineBytes,
                limits.maxHeaderSectionBytes,
                limits.maxHeaderCount
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
          input.clearHeadDeadline()
          head match {
            case None        => break()
            case Some(value) =>
              connection.phase = ConnectionPhase.Active
              if (!running) {
                connection.phase = ConnectionPhase.Closed
                signalLifecycle()
                break()
              }
              try {
                reuse = new Exchange(
                  socket,
                  connection.localAddress,
                  connection.remoteAddress,
                  input,
                  output,
                  value,
                  config,
                  handle
                ).run()
              } finally {
                connection.phase = ConnectionPhase.Idle
                if (!running) {
                  connection.phase = ConnectionPhase.Closed
                  signalLifecycle()
                }
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

  private def removeConnection(connection: Connection): Unit = {
    val removed = connections.remove(connection)
    if (removed) connectionCount.decrementAndGet()
  }

  private def anyConnection(predicate: Connection => Boolean): Boolean = connections.asScala.exists(predicate)

  private def millis(nanos: Long): Long = {
    math.max(1L, (nanos + 999999L) / 1000000L)
  }

}
