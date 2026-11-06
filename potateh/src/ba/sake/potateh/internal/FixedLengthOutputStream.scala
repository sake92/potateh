package ba.sake.potateh.internal

import java.io.{IOException, OutputStream}

/** Writes an exact response body to a caller-owned connection stream.
  * Length zero also serves as strict no-body output: nonempty writes fail,
  * and close emits no payload or framing bytes.
  * Successful close flushes the sink without closing it. A failure requires
  * the connection owner to abort the exchange and close the connection.
  */
private[potateh] final class FixedLengthOutputStream(private val output: OutputStream, length: Long)
    extends OutputStream {
  if (output == null) throw new NullPointerException("output")
  require(length >= 0, "Negative body length")

  private var remaining = length
  private var closed = false
  private var failure: Option[IOException] = None

  def isComplete: Boolean = closed && remaining == 0 && failure.isEmpty

  override def write(value: Int): Unit = {
    checkWritable()
    if (remaining == 0) fail(new IOException("Too many response body bytes"))
    try output.write(value)
    catch { case error: IOException => fail(error) }
    remaining -= 1
  }

  override def write(buffer: Array[Byte], offset: Int, length: Int): Unit = {
    if (buffer == null) throw new NullPointerException("buffer")
    if (offset < 0 || length < 0 || offset > buffer.length || length > buffer.length - offset)
      throw new IndexOutOfBoundsException()
    checkWritable()
    if (length == 0) return
    if (length.toLong > remaining) fail(new IOException("Too many response body bytes"))
    try output.write(buffer, offset, length)
    catch { case error: IOException => fail(error) }
    remaining -= length
  }

  override def flush(): Unit = {
    checkWritable()
    try output.flush()
    catch { case error: IOException => fail(error) }
  }

  override def close(): Unit = {
    if (closed) return
    closed = true
    failure.foreach(throw _)
    if (remaining != 0) fail(new IOException("Too few response body bytes"))
    try output.flush()
    catch { case error: IOException => fail(error) }
  }

  private def checkWritable(): Unit = {
    failure.foreach(throw _)
    if (closed) throw new IOException("Response body stream is closed")
  }

  private def fail(error: IOException): Nothing = {
    failure = Some(error)
    throw error
  }
}
