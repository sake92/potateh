package ba.sake.potateh.internal

import ba.sake.potateh.*
import java.io.OutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1

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
    val managed = Set(
      "content-length",
      "transfer-encoding",
      "connection",
      "keep-alive",
      "upgrade",
      "trailer"
    )
    response.headers.fields.foreach { case (key, _) =>
      require(
        !managed.exists(_.equalsIgnoreCase(key)),
        "Engine owns framing and connection headers"
      )
    }
    val size = length(response.body)
    val forbidden = status == 204
    val metadata = method == "HEAD" || status == 304
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
    var headers = response.headers
    if (headers.first("Date").isEmpty) {
      headers = headers.add("Date", httpDate(System.currentTimeMillis()))
    }
    if (!forbidden) {
      if (status == 205) headers = headers.add("Content-Length", "0")
      else {
        size.foreach(value => headers = headers.add("Content-Length", value.toString))
      }
    }
    if (framing == Chunked) {
      headers = headers.add("Transfer-Encoding", "chunked")
    }
    if (closes) headers = headers.add("Connection", "close")
    else if (version == "HTTP/1.0") {
      headers = headers.add("Connection", "keep-alive")
    }
    val head = new StringBuilder
    head
      .append(version)
      .append(' ')
      .append(status)
      .append(' ')
      .append(reason(status))
      .append("\r\n")
    headers.fields.foreach { case (name, value) =>
      head.append(name).append(": ").append(value).append("\r\n")
    }
    head.append("\r\n")
    Prepared(head.toString.getBytes(ISO_8859_1), framing, closes)
  }

  def writeBody(
      output: OutputStream,
      body: ResponseBody,
      framing: Framing
  ): Unit = {
    if (framing == NoBody) return
    val stream = framing match {
      case FixedLength(size) => new FixedLengthOutputStream(output, size)
      case Chunked           => new ChunkedOutputStream(output)
      case CloseDelimited    =>
        new OutputStream {
          private var closed = false
          override def write(value: Int): Unit = {
            check(); output.write(value)
          }
          override def write(
              bytes: Array[Byte],
              offset: Int,
              length: Int
          ): Unit = { check(); output.write(bytes, offset, length) }
          override def flush(): Unit = { check(); output.flush() }
          override def close(): Unit = { closed = true; output.flush() }
          private def check(): Unit = {
            if (closed) throw new java.io.IOException("Response body is closed")
          }
        }
      case NoBody => throw new IllegalStateException()
    }
    var live = true
    val scoped = new OutputStream {
      private def check(): Unit = if (!live) {
        throw new java.io.IOException("Response body escaped its exchange")
      }
      override def write(value: Int): Unit = { check(); stream.write(value) }
      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
        check(); stream.write(bytes, offset, length)
      }
      override def flush(): Unit = { check(); stream.flush() }
      override def close(): Unit = { check(); stream.close() }
    }
    // Only successful writers finalize framing. A failing chunked writer must
    // not emit a terminating chunk that would make a partial response complete.
    try {
      body match {
        case ResponseBody.Empty          => ()
        case bytes: ResponseBody.Bytes   => scoped.write(bytes.value)
        case writer: ResponseBody.Writer => writer.write(scoped)
      }
      stream.close()
    } finally live = false
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
}
