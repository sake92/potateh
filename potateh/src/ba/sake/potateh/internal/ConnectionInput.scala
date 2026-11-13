package ba.sake.potateh.internal

import java.io.{IOException, InputStream}
import java.net.{Socket, SocketTimeoutException}

/** Buffers connection reads so request-head parsing does not perform one socket read and two clock reads per byte.
  * Bytes read ahead remain available to the request body and subsequent pipelined requests.
  */
private[potateh] final class ConnectionInput(socket: Socket, source: InputStream, capacity: Int = 8192)
    extends InputStream {
  require(capacity > 0)

  private val buffer = new Array[Byte](capacity)
  private var position = 0
  private var limit = 0
  private var deadlineNanos = 0L

  def peek(timeoutMillis: Int): Int = {
    socket.setSoTimeout(timeoutMillis)
    deadlineNanos = 0L
    if (ensureBuffered()) buffer(position) & 0xff else -1
  }

  def beginHeadDeadline(deadlineNanos: Long): Unit = {
    this.deadlineNanos = deadlineNanos
  }

  def clearHeadDeadline(): Unit = {
    deadlineNanos = 0L
  }

  override def read(): Int = {
    if (!ensureBuffered()) -1
    else {
      val value = buffer(position) & 0xff
      position += 1
      value
    }
  }

  override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
    if (offset < 0 || length < 0 || offset > bytes.length || length > bytes.length - offset) {
      throw new IndexOutOfBoundsException()
    }
    if (length == 0) 0
    else if (position < limit) {
      val count = math.min(length, limit - position)
      System.arraycopy(buffer, position, bytes, offset, count)
      position += count
      count
    } else source.read(bytes, offset, length)
  }

  override def skip(count: Long): Long = {
    if (count <= 0) 0L
    else if (position < limit) {
      val skipped = math.min(count, (limit - position).toLong)
      position += skipped.toInt
      skipped
    } else source.skip(count)
  }

  override def available(): Int = {
    val buffered = limit - position
    math.min(Int.MaxValue, buffered + source.available())
  }

  override def close(): Unit = source.close()

  private def ensureBuffered(): Boolean = {
    if (position < limit) true
    else {
      checkDeadline()
      val count = source.read(buffer, 0, buffer.length)
      if (count < 0) false
      else if (count == 0) ensureBuffered()
      else {
        position = 0
        limit = count
        true
      }
    }
  }

  private def checkDeadline(): Unit = {
    if (deadlineNanos != 0L) {
      val remaining = deadlineNanos - System.nanoTime()
      if (remaining <= 0) {
        throw new SocketTimeoutException("Request head deadline exceeded")
      }
      socket.setSoTimeout(math.max(1L, (remaining + 999999L) / 1000000L).toInt)
    }
  }
}
