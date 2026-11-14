package ba.sake.potateh.internal

import java.io.OutputStream

import scala.util.boundary

import boundary.break
import ba.sake.potateh.*

private[potateh] object ResponseHeadWriter {

  enum Framing {
    case NoBody, Chunked, CloseDelimited

    case FixedLength(length: Long)

  }

  import Framing.*

  final case class Prepared(
      bytes: Array[Byte],
      framing: Framing,
      closes: Boolean
  )

  @volatile private var cachedDateSecond = Long.MinValue
  @volatile private var cachedDate = ""

  def length(body: ResponseBody): Option[Long] = body match {
    case ResponseBody.Empty          => Some(0L)
    case bytes: ResponseBody.Bytes   => Some(bytes.value.length.toLong)
    case writer: ResponseBody.Writer => writer.length
  }

  /** Complete validation before the connection owner commits a single byte. */
  def prepare(
      method: String,
      version: String,
      response: Response,
      close: Boolean
  ): Prepared = {
    val status = response.status
    require(
      status >= 200 && status <= 599,
      "Callback must return a final response"
    )
    require(
      method != "CONNECT" || status < 200 || status >= 300,
      "Tunnels are unsupported"
    )
    var hasDate = false
    response.headers.fields.foreach { case (key, _) =>
      val managed = isEngineManagedHeader(key)
      require(
        !managed || (status == 426 && key.length == 7 && HttpSyntax.equalsIgnoreCaseAscii(key, "Upgrade")),
        "Engine owns framing and connection headers"
      )
      if (!hasDate && key.length == 4 && HttpSyntax.equalsIgnoreCaseAscii(key, "Date")) hasDate = true
    }
    if (status == 426) {
      val protocols = HttpSyntax.listElements(response.headers.all("Upgrade")).getOrElse(Vector.empty)
      require(
        protocols.nonEmpty && protocols.forall { protocol =>
          val slash = protocol.indexOf('/')
          val name = if (slash < 0) protocol else protocol.substring(0, slash)
          val version = if (slash < 0) None else Some(protocol.substring(slash + 1))
          HttpSyntax.isTokenName(name) &&
          version.forall(HttpSyntax.isTokenName)
        },
        "426 requires valid Upgrade protocols"
      )
    }
    val forbidden = status == 204
    val metadata = method == "HEAD" || status == 304
    val size = {
      if (metadata && response.body == ResponseBody.Empty) None
      else length(response.body)
    }
    val framing = {
      if (forbidden || metadata || status == 205) NoBody
      else {
        size match {
          case Some(value)                   => FixedLength(value)
          case None if version == "HTTP/1.1" => Chunked
          case None                          => CloseDelimited
        }
      }
    }
    val closes = close || framing == CloseDelimited
    val head = new HeadBytes()
    head.appendAscii(version)
    head.append(' ')
    head.appendAscii(status.toString)
    head.append(' ')
    head.appendAscii(reason(status))
    head.appendAscii("\r\n")
    response.headers.fields.foreach { case (name, value) =>
      head.appendHttp(name)
      head.appendAscii(": ")
      head.appendHttp(value)
      head.appendAscii("\r\n")
    }
    if (!hasDate) {
      head.appendAscii("Date: ")
      head.appendAscii(currentHttpDate())
      head.appendAscii("\r\n")
    }
    if (!forbidden) {
      if (status == 205) head.appendAscii("Content-Length: 0\r\n")
      else {
        size.foreach { value =>
          head.appendAscii("Content-Length: ")
          head.appendAscii(value.toString)
          head.appendAscii("\r\n")
        }
      }
    }
    if (framing == Chunked) {
      head.appendAscii("Transfer-Encoding: chunked\r\n")
    }
    if (closes && status == 426) head.appendAscii("Connection: close, upgrade\r\n")
    else if (status == 426 && version == "HTTP/1.0") head.appendAscii("Connection: keep-alive, upgrade\r\n")
    else if (status == 426) head.appendAscii("Connection: upgrade\r\n")
    else if (closes) head.appendAscii("Connection: close\r\n")
    else if (version == "HTTP/1.0") {
      head.appendAscii("Connection: keep-alive\r\n")
    }
    head.appendAscii("\r\n")
    Prepared(head.result(), framing, closes)
  }

  def writeBody(
      output: OutputStream,
      body: ResponseBody,
      framing: Framing
  ): Unit = boundary {
    if (framing == NoBody) break()
    body match {
      case ResponseBody.Empty        => break()
      case bytes: ResponseBody.Bytes =>
        framing match {
          case FixedLength(size) if size == bytes.value.length =>
            output.write(bytes.value)
            break()
          case _ => ()
        }
      case _: ResponseBody.Writer => ()
    }
    val stream = framing match {
      case FixedLength(size) => new FixedLengthOutputStream(output, size)
      case Chunked           => new ChunkedOutputStream(output)
      case CloseDelimited    => new CloseDelimitedOutputStream(output)
      case NoBody            => throw new IllegalStateException()
    }
    val scoped = new ScopedResponseBodyOutput(stream)
    // Only successful writers finalize framing. A failing chunked writer must
    // not emit a terminating chunk that would make a partial response complete.
    try {
      body match {
        case ResponseBody.Empty          => ()
        case bytes: ResponseBody.Bytes   => scoped.write(bytes.value)
        case writer: ResponseBody.Writer => writer.write(scoped)
      }
      stream.close()
    } finally scoped.deactivate()
  }

  private final class CloseDelimitedOutputStream(output: OutputStream) extends OutputStream {
    private var closed = false

    override def write(value: Int): Unit = {
      checkWritable()
      output.write(value)
    }

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
      checkWritable()
      output.write(bytes, offset, length)
    }

    override def flush(): Unit = {
      checkWritable()
      output.flush()
    }

    override def close(): Unit = {
      closed = true
      output.flush()
    }

    private def checkWritable(): Unit = {
      if (closed) throw new java.io.IOException("Response body is closed")
    }
  }

  private final class ScopedResponseBodyOutput(stream: OutputStream) extends OutputStream {
    private var live = true

    private inline def checkLive(): Unit = {
      if (!live) throw new java.io.IOException("Response body escaped its exchange")
    }

    inline def deactivate(): Unit = {
      live = false
    }

    override def write(value: Int): Unit = {
      checkLive()
      stream.write(value)
    }

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
      checkLive()
      stream.write(bytes, offset, length)
    }

    override def flush(): Unit = {
      checkLive()
      stream.flush()
    }

    override def close(): Unit = {
      checkLive()
      stream.close()
    }
  }

  private def currentHttpDate(): String = {
    val now = System.currentTimeMillis()
    val second = Math.floorDiv(now, 1000L)
    val cached = cachedDate
    if (cachedDateSecond == second && cached.nonEmpty) cached
    else {
      val formatted = httpDate(now)
      cachedDate = formatted
      cachedDateSecond = second
      formatted
    }
  }

  // Native 0.5.12 has no java.text date formatter. Bound the conversion to the
  // four-digit HTTP year range; this calendar walk is at most 8030 years.
  private[internal] def httpDate(milliseconds: Long): String = {
    require(
      milliseconds >= -62135596800000L && milliseconds <= 253402300799999L,
      "HTTP date year out of range"
    )
    val seconds = Math.floorDiv(milliseconds, 1000L)
    val epochDay = Math.floorDiv(seconds, 86400L)
    var days = epochDay
    var year = 1970
    while (days < 0) {
      year -= 1
      days += daysInYear(year)
    }
    while (days >= daysInYear(year)) {
      days -= daysInYear(year)
      year += 1
    }
    val monthLengths = Array(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    if (isLeapYear(year)) monthLengths(1) = 29
    var month = 0
    while (days >= monthLengths(month)) {
      days -= monthLengths(month)
      month += 1
    }
    val weekdays = Array("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
    val months = Array(
      "Jan",
      "Feb",
      "Mar",
      "Apr",
      "May",
      "Jun",
      "Jul",
      "Aug",
      "Sep",
      "Oct",
      "Nov",
      "Dec"
    )
    val weekday = weekdays(Math.floorMod(epochDay, 7L).toInt)
    val monthName = months(month)
    val day = twoDigits(days.toInt + 1)
    val rawYear = year.toString
    val yearText = "0000".substring(rawYear.length) + rawYear
    val time = Math.floorMod(seconds, 86400L)
    val hour = twoDigits((time / 3600).toInt)
    val minute = twoDigits((time / 60 % 60).toInt)
    val second = twoDigits((time % 60).toInt)
    s"$weekday, $day $monthName $yearText $hour:$minute:$second GMT"
  }

  private def isLeapYear(year: Int): Boolean = {
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
  }

  private def daysInYear(year: Int): Int = if (isLeapYear(year)) 366 else 365

  private def isEngineManagedHeader(value: String): Boolean = value.length match {
    case 7  => HttpSyntax.equalsIgnoreCaseAscii(value, "Upgrade") || HttpSyntax.equalsIgnoreCaseAscii(value, "Trailer")
    case 10 =>
      HttpSyntax.equalsIgnoreCaseAscii(value, "Connection") || HttpSyntax.equalsIgnoreCaseAscii(value, "Keep-Alive")
    case 14 => HttpSyntax.equalsIgnoreCaseAscii(value, "Content-Length")
    case 17 => HttpSyntax.equalsIgnoreCaseAscii(value, "Transfer-Encoding")
    case _  => false
  }

  private def twoDigits(value: Int): String = {
    if (value < 10) "0" + value else value.toString
  }

  private def reason(status: Int): String = status match {
    case 100 => "Continue"
    case 200 => "OK"
    case 201 => "Created"
    case 202 => "Accepted"
    case 204 => "No Content"
    case 205 => "Reset Content"
    case 206 => "Partial Content"
    case 301 => "Moved Permanently"
    case 302 => "Found"
    case 303 => "See Other"
    case 304 => "Not Modified"
    case 307 => "Temporary Redirect"
    case 308 => "Permanent Redirect"
    case 400 => "Bad Request"
    case 401 => "Unauthorized"
    case 403 => "Forbidden"
    case 404 => "Not Found"
    case 405 => "Method Not Allowed"
    case 408 => "Request Timeout"
    case 411 => "Length Required"
    case 413 => "Content Too Large"
    case 414 => "URI Too Long"
    case 417 => "Expectation Failed"
    case 431 => "Request Header Fields Too Large"
    case 500 => "Internal Server Error"
    case 501 => "Not Implemented"
    case 503 => "Service Unavailable"
    case 505 => "HTTP Version Not Supported"
    case _   => ""
  }

  private final class HeadBytes {
    private var bytes = new Array[Byte](256)
    private var length = 0

    def append(value: Char): Unit = {
      val byte = if (value <= 0xff) value.toByte else '?'.toByte
      ensure(1)
      bytes(length) = byte
      length += 1
    }

    def appendAscii(value: String): Unit = {
      ensure(value.length)
      var index = 0
      while (index < value.length) {
        bytes(length) = value.charAt(index).toByte
        length += 1
        index += 1
      }
    }

    def appendHttp(value: String): Unit = {
      ensure(value.length)
      var index = 0
      while (index < value.length) {
        val character = value.charAt(index)
        bytes(length) = if (character <= 0xff) character.toByte else '?'.toByte
        length += 1
        index += 1
      }
    }

    def result(): Array[Byte] = java.util.Arrays.copyOf(bytes, length)

    private def ensure(additional: Int): Unit = {
      val required = length + additional
      if (required > bytes.length) {
        var capacity = bytes.length
        while (capacity < required) capacity *= 2
        bytes = java.util.Arrays.copyOf(bytes, capacity)
      }
    }
  }

}
