package ba.sake.potateh.internal

import java.io.{IOException, InputStream}
import java.net.{URI, URISyntaxException}

import HttpSyntax.{isToken, trimOws}

/** Strict HTTP/1.x head parsing. The caller owns and reuses the input stream. After a parse failure the connection must
  * be closed, not parsed again.
  */
private[potateh] object RequestHeadParser {

  sealed trait BodyFraming

  case object NoBody extends BodyFraming

  final case class FixedLength(length: Long) extends BodyFraming

  case object Chunked extends BodyFraming

  final case class Limits(
      requestLineBytes: Int = 8192,
      headerLineBytes: Int = 8192,
      headerSectionBytes: Int = 65536,
      headerCount: Int = 100
  ) {
    require(requestLineBytes >= 0, "requestLineBytes must be at least 0")
    require(headerLineBytes >= 0, "headerLineBytes must be at least 0")
    require(headerSectionBytes >= 2, "headerSectionBytes must be at least 2")
    require(headerCount >= 0, "headerCount must be at least 0")
  }

  final case class RequestHead(
      method: String,
      target: String,
      version: String,
      headers: Vector[(String, String)],
      framing: BodyFraming
  )

  final class ParseException(val responseCode: Int, message: String) extends IOException(message)

  /** None means EOF before any request bytes. Limits count wire octets: line limits exclude CRLF; section limits
    * include every CRLF and the final blank line.
    */
  def read(
      input: InputStream,
      limits: Limits = Limits()
  ): Option[RequestHead] = {
    val first = input.read()
    if (first == -1) return None
    val line = {
      readLine(input, first, limits.requestLineBytes, 414, requestLine = true)
    }
    val firstSpace = line.indexOf(' ')
    val lastSpace = line.lastIndexOf(' ')
    if (firstSpace <= 0 || lastSpace <= firstSpace + 1) {
      fail(400, "Invalid request line")
    }
    val method = line.substring(0, firstSpace)
    val target = line.substring(firstSpace + 1, lastSpace)
    val version = line.substring(lastSpace + 1)
    if (!method.forall(isToken)) fail(400, "Invalid method")
    if (!version.matches("HTTP/[0-9]\\.[0-9]")) {
      fail(400, "Invalid HTTP version")
    }
    if (version != "HTTP/1.0" && version != "HTTP/1.1") {
      fail(505, "Unsupported HTTP version")
    }
    validateTarget(method, target)

    val fields = Vector.newBuilder[(String, String)]
    var sectionBytes = 0
    var count = 0
    var done = false
    while (!done) {
      val remaining = limits.headerSectionBytes - sectionBytes
      if (remaining < 2) fail(431, "Header section too large")
      val maximum = math.min(limits.headerLineBytes, remaining - 2)
      val field = readLine(input, input.read(), maximum, 431)
      sectionBytes += field.length + 2
      if (field.isEmpty) done = true
      else {
        if (count == limits.headerCount) fail(431, "Too many header fields")
        val parsed = {
          HttpSyntax.field(field).getOrElse(fail(400, "Invalid header field"))
        }
        fields += parsed
        count += 1
      }
    }
    val headers = fields.result()
    val hosts = values(headers, "Host")
    if (hosts.size > 1 || (version == "HTTP/1.1" && hosts.isEmpty)) {
      fail(400, "Missing or repeated Host")
    }
    hosts.foreach(validateHost)
    val framing = selectFraming(headers, version)
    Some(RequestHead(method, target, version, headers, framing))
  }

  private def readLine(
      input: InputStream,
      first: Int,
      maximum: Int,
      errorCode: Int,
      requestLine: Boolean = false
  ): String = {
    val result = new java.lang.StringBuilder()
    var next = first
    while (next != '\r') {
      if (next == -1) fail(400, "Truncated request head")
      if (next == '\n') fail(400, "Bare LF in request head")
      if (result.length() == maximum) {
        if (requestLine && result.indexOf(" ") < 0) {
          fail(501, "Request method too long")
        }
        fail(errorCode, "Request head line too long")
      }
      result.append(next.toChar)
      next = input.read()
    }
    if (input.read() != '\n') fail(400, "CR must be followed by LF")
    result.toString()
  }

  private def selectFraming(
      headers: Vector[(String, String)],
      version: String
  ): BodyFraming = {
    val lengths = values(headers, "Content-Length")
    val encodings = values(headers, "Transfer-Encoding")
    if (lengths.nonEmpty && encodings.nonEmpty) {
      fail(400, "Conflicting body framing")
    }
    if (encodings.nonEmpty) {
      if (version == "HTTP/1.0") fail(400, "Transfer-Encoding on HTTP/1.0")
      val codings = encodings.flatMap(_.split(",", -1)).map(trimOws)
      if (codings.exists(c => c.isEmpty || !c.forall(isToken))) {
        fail(400, "Invalid transfer coding")
      }
      if (
        !codings.last.equalsIgnoreCase("chunked") ||
        codings.count(_.equalsIgnoreCase("chunked")) != 1
      ) {
        fail(400, "Chunked must occur exactly once, last")
      }
      if (codings.size != 1) fail(501, "Unsupported transfer coding")
      Chunked
    } else if (lengths.nonEmpty) {
      if (lengths.size != 1) fail(400, "Repeated Content-Length")
      val value = lengths.head
      if (value.isEmpty) fail(400, "Empty Content-Length")
      var length = 0L
      value.foreach { c =>
        if (c < '0' || c > '9') fail(400, "Invalid Content-Length")
        val digit = c - '0'
        if (length > (Long.MaxValue - digit) / 10) {
          fail(400, "Content-Length overflow")
        }
        length = length * 10 + digit
      }
      FixedLength(length)
    } else NoBody
  }

  private def validateTarget(method: String, target: String): Unit = {
    if (!target.forall(c => c > ' ' && c < 127) || target.indexOf('#') >= 0) {
      fail(400, "Invalid request target")
    }
    if (target == "*") {
      if (method != "OPTIONS") fail(400, "Asterisk target requires OPTIONS")
    } else if (method == "CONNECT") {
      val uri = parseUri("http://" + target)
      if (
        Option(uri.getHost()).isEmpty || uri.getPort() < 0 || uri
          .getRawPath() != "" ||
        Option(uri.getRawQuery()).nonEmpty || Option(
          uri.getRawUserInfo()
        ).nonEmpty
      ) {
        fail(400, "Invalid CONNECT authority")
      }
    } else if (target.startsWith("/")) {
      // Prefixing an authority keeps a leading // in the path, rather than
      // letting URI reinterpret it as an authority. Preserve the raw target.
      parseUri("http://request.invalid" + target)
    } else {
      val uri = parseUri(target)
      if (
        !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(
          uri.getScheme()
        )) ||
        Option(uri.getHost()).isEmpty || Option(uri.getRawUserInfo()).nonEmpty
      ) {
        fail(400, "Invalid absolute request target")
      }
    }
  }

  private def validateHost(value: String): Unit = {
    // Empty Host is permitted when the target authority is undefined.
    if (value.nonEmpty) {
      val uri = parseUri("http://" + value)
      if (
        Option(uri.getHost()).isEmpty || Option(
          uri.getRawUserInfo()
        ).nonEmpty ||
        uri.getRawPath() != "" || Option(uri.getRawQuery()).nonEmpty || Option(
          uri.getRawFragment()
        ).nonEmpty
      ) {
        fail(400, "Invalid Host")
      }
    }
  }

  private def parseUri(value: String): URI = {
    try new URI(value)
    catch { case _: URISyntaxException => fail(400, "Invalid URI syntax") }
  }

  private def values(
      headers: Vector[(String, String)],
      name: String
  ): Vector[String] = {
    headers.collect { case (key, value) if key.equalsIgnoreCase(name) => value }
  }

  private def fail(code: Int, message: String): Nothing = {
    throw new ParseException(code, message)
  }
}
