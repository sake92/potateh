package ba.sake.potateh.internal

import java.io.{IOException, OutputStream}
import java.nio.charset.StandardCharsets.US_ASCII

/** Emits one chunk per nonempty write on a caller-owned connection stream.
  * Close terminates the body and flushes without closing the sink.
  */
private[potateh] final class ChunkedOutputStream(
    private val output: OutputStream
) extends OutputStream {
  if (output == null) throw new NullPointerException("output")

  private val delimiter = "\r\n".getBytes(US_ASCII)
  private val singleHeader = "1\r\n".getBytes(US_ASCII)
  private val ending = "0\r\n\r\n".getBytes(US_ASCII)
  private var closed = false
  private var complete = false
  private var failure: Option[IOException] = None

  def isComplete: Boolean = complete

  override def write(value: Int): Unit = {
    checkWritable()
    try {
      output.write(singleHeader)
      output.write(value)
      output.write(delimiter)
    } catch { case error: IOException => fail(error) }
  }

  override def write(buffer: Array[Byte], offset: Int, length: Int): Unit = {
    if (buffer == null) throw new NullPointerException("buffer")
    if (
      offset < 0 || length < 0 || offset > buffer.length || length > buffer.length - offset
    )
      throw new IndexOutOfBoundsException()
    checkWritable()
    if (length == 0) return
    val header = (Integer.toHexString(length) + "\r\n").getBytes(US_ASCII)
    try {
      output.write(header)
      output.write(buffer, offset, length)
      output.write(delimiter)
    } catch { case error: IOException => fail(error) }
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
    try {
      output.write(ending)
      output.flush()
      complete = true
    } catch { case error: IOException => fail(error) }
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
