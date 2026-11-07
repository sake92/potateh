package ba.sake.potateh.internal

import java.io.{EOFException, IOException, InputStream}
import java.net.ProtocolException

private[potateh] object ChunkedInputStream {

  final class BodyTooLargeException extends IOException("Request body exceeds configured limit")

  final case class Limits(
      chunkLineBytes: Int = 8192,
      extensionBytes: Int = 65536,
      trailerLineBytes: Int = 8192,
      trailerSectionBytes: Int = 65536,
      trailerCount: Int = 100
  ) {
    require(chunkLineBytes >= 1, "chunkLineBytes must be at least 1")
    require(extensionBytes >= 0, "extensionBytes must be at least 0")
    require(trailerLineBytes >= 0, "trailerLineBytes must be at least 0")
    require(trailerSectionBytes >= 2, "trailerSectionBytes must be at least 2")
    require(trailerCount >= 0, "trailerCount must be at least 0")
  }

}

/** Decodes a body on a caller-owned connection stream. Extensions and trailers are validated; trailers remain separate
  * from initial headers. Close does not drain or close the source.
  */
private[potateh] final class ChunkedInputStream(
    private val input: InputStream,
    private val limits: ChunkedInputStream.Limits = ChunkedInputStream.Limits(),
    private val maxBodyBytes: Option[Long] = None
) extends InputStream {
  if (input == null) throw new NullPointerException("input")
  require(maxBodyBytes.forall(_ >= 0), "maxBodyBytes must be nonnegative")

  private var bodyBudget = maxBodyBytes.getOrElse(Long.MaxValue)
  private var remaining = 0L
  private var needsDelimiter = false
  private var complete = false
  private var extensionBytes = 0
  private var closed = false
  private var failure: Option[IOException] = None

  private var trailerFields = ba.sake.potateh.Headers.empty
  def terminalFailure: Option[IOException] = failure
  def trailers: Option[ba.sake.potateh.Headers] = {
    Option.when(isComplete)(trailerFields)
  }

  def isComplete: Boolean = complete && failure.isEmpty

  override def read(): Int = {
    checkReadable()
    try {
      if (!prepareChunk()) return -1
      val next = readByte()
      remaining -= 1
      next
    } catch { case error: IOException => fail(error) }
  }

  override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
    if (buffer == null) throw new NullPointerException("buffer")
    if (offset < 0 || length < 0 || offset > buffer.length || length > buffer.length - offset) {
      throw new IndexOutOfBoundsException()
    }
    checkReadable()
    if (length == 0) return 0
    try {
      if (!prepareChunk()) return -1
      val maximum = math.min(remaining, length.toLong).toInt
      val count = input.read(buffer, offset, maximum)
      if (count == -1) throw new EOFException("Truncated chunk data")
      remaining -= count
      count
    } catch { case error: IOException => fail(error) }
  }

  override def skip(count: Long): Long = {
    checkReadable()
    if (count <= 0 || complete) return 0L
    val buffer = new Array[Byte](math.min(count, 4096L).toInt)
    var skipped = 0L
    var ended = false
    while (skipped < count && !ended) {
      val requested = math.min(count - skipped, buffer.length.toLong).toInt
      val actual = read(buffer, 0, requested)
      if (actual == -1) ended = true else skipped += actual
    }
    skipped
  }

  override def available(): Int = {
    checkReadable()
    if (remaining == 0) return 0
    try math.min(remaining, input.available().toLong).toInt
    catch { case error: IOException => fail(error) }
  }

  override def close(): Unit = closed = true

  private def prepareChunk(): Boolean = {
    if (complete) return false
    if (remaining > 0) return true
    if (needsDelimiter) expectCrlf()
    val line = readLine(limits.chunkLineBytes)
    var index = 0
    var size = 0L
    while (index < line.length && hexDigit(line.charAt(index)) >= 0) {
      val digit = hexDigit(line.charAt(index))
      if (size > (Long.MaxValue - digit) / 16) invalid("Chunk size overflow")
      size = size * 16 + digit
      index += 1
    }
    if (index == 0) invalid("Missing chunk size")
    val extra = line.length - index
    if (extra > limits.extensionBytes - extensionBytes) {
      invalid("Too much chunk extension text")
    }
    extensionBytes += extra
    validateExtensions(line, index)
    if (maxBodyBytes.nonEmpty) {
      if (size > bodyBudget) {
        throw new ChunkedInputStream.BodyTooLargeException()
      }
      bodyBudget -= size
    }
    remaining = size
    if (size == 0) {
      readTrailers()
      complete = true
      false
    } else {
      needsDelimiter = true
      true
    }
  }

  private def validateExtensions(line: String, start: Int): Unit = {
    var index = start
    while (index < line.length) {
      index = skipOws(line, index)
      if (index == line.length || line.charAt(index) != ';') {
        invalid("Invalid chunk extension")
      }
      index = skipOws(line, index + 1)
      val nameStart = index
      while (index < line.length && HttpSyntax.isToken(line.charAt(index))) {
        index += 1
      }
      if (index == nameStart) invalid("Missing chunk extension name")
      val equals = skipOws(line, index)
      if (equals < line.length && line.charAt(equals) == '=') {
        index = skipOws(line, equals + 1)
        if (index == line.length) invalid("Missing chunk extension value")
        if (line.charAt(index) == '"') {
          index += 1
          var ended = false
          while (index < line.length && !ended) {
            val character = line.charAt(index)
            index += 1
            if (character == '"') ended = true
            else if (character == '\\') {
              if (index == line.length || !quotedCharacter(line.charAt(index))) {
                invalid("Invalid quoted escape")
              }
              index += 1
            } else if (!quotedCharacter(character)) {
              invalid("Invalid quoted extension")
            }
          }
          if (!ended) invalid("Unterminated quoted extension")
        } else {
          val valueStart = index
          while (index < line.length && HttpSyntax.isToken(line.charAt(index))) {
            index += 1
          }
          if (index == valueStart) invalid("Invalid chunk extension value")
        }
      }
    }
  }

  private def readTrailers(): Unit = {
    val fields = Vector.newBuilder[(String, String)]
    var bytes = 0
    var count = 0
    var ended = false
    while (!ended) {
      val remainingBytes = limits.trailerSectionBytes - bytes
      if (remainingBytes < 2) invalid("Trailer section too large")
      val maximum = math.min(limits.trailerLineBytes, remainingBytes - 2)
      val line = readLine(maximum)
      bytes += line.length + 2
      if (line.isEmpty) ended = true
      else {
        if (count == limits.trailerCount) invalid("Too many trailer fields")
        val field = {
          HttpSyntax.field(line).getOrElse(invalid("Invalid trailer field"))
        }
        fields += field
        count += 1
      }
    }
    trailerFields = ba.sake.potateh.Headers.from(fields.result())
  }

  private def readLine(maximum: Int): String = {
    val line = new java.lang.StringBuilder()
    var next = readByte()
    while (next != '\r') {
      if (next == '\n') invalid("Bare LF in chunk framing")
      if (line.length() == maximum) invalid("Chunk metadata line too long")
      line.append(next.toChar)
      next = readByte()
    }
    if (readByte() != '\n') invalid("CR must be followed by LF")
    line.toString()
  }

  private def expectCrlf(): Unit = {
    if (readByte() != '\r' || readByte() != '\n') {
      invalid("Missing chunk data CRLF")
    }
  }

  private def readByte(): Int = {
    val next = input.read()
    if (next == -1) throw new EOFException("Truncated chunked request body")
    next
  }

  private def skipOws(line: String, start: Int): Int = {
    var index = start
    while (
      index < line.length && (line
        .charAt(index) == ' ' || line.charAt(index) == '\t')
    ) index += 1
    index
  }

  private def hexDigit(c: Char): Int = {
    if (c >= '0' && c <= '9') c - '0'
    else if (c >= 'a' && c <= 'f') c - 'a' + 10
    else if (c >= 'A' && c <= 'F') c - 'A' + 10
    else -1
  }

  private def quotedCharacter(c: Char): Boolean = {
    c == '\t' || (c >= ' ' && c != 127)
  }

  private def invalid(message: String): Nothing = throw new ProtocolException(
    message
  )

  private def checkReadable(): Unit = {
    if (closed) throw new IOException("Request body stream is closed")
    failure.foreach(throw _)
  }

  private def fail(error: IOException): Nothing = {
    failure = Some(error)
    throw error
  }
}
