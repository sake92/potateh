package ba.sake.potateh.internal

import java.io.{IOException, InputStream}

import scala.util.boundary

import boundary.break
import HttpSyntax.{areTokens, isTokenName}

/** Strict HTTP/1.x head parsing. The caller owns and reuses the input stream. After a parse failure the connection must
  * be closed, not parsed again.
  */
private[potateh] object RequestHeadParser {

  sealed trait BodyFraming

  case object NoBody extends BodyFraming

  final case class FixedLength(length: Long) extends BodyFraming

  case object Chunked extends BodyFraming

  final case class Limits(
      maxRequestLineBytes: Int = 8192,
      maxHeaderLineBytes: Int = 8192,
      maxHeaderSectionBytes: Int = 65536,
      maxHeaderCount: Int = 100
  ) {
    require(maxRequestLineBytes >= 0, "maxRequestLineBytes must be at least 0")
    require(maxHeaderLineBytes >= 0, "maxHeaderLineBytes must be at least 0")
    require(maxHeaderSectionBytes >= 2, "maxHeaderSectionBytes must be at least 2")
    require(maxHeaderCount >= 0, "maxHeaderCount must be at least 0")
  }

  final case class RequestHead(
      method: String,
      target: String,
      version: String,
      headers: Vector[(name: String, value: String)],
      framing: BodyFraming
  )

  final class ParseException(val responseCode: Int, message: String) extends IOException(message)

  /** None means EOF before any request bytes. Limits count wire octets: line limits exclude CRLF; section limits
    * include every CRLF and the final blank line.
    */
  def read(
      input: InputStream,
      limits: Limits = Limits()
  ): Option[RequestHead] = boundary {
    var first = input.read()
    if (first == -1) break(None)
    if (first == '\r') {
      if (input.read() != '\n') fail(400, "CR must be followed by LF")
      first = input.read()
    }
    val line = {
      readLine(input, first, limits.maxRequestLineBytes, 414, requestLine = true)
    }
    val firstSpace = line.indexOf(' ')
    var lastSpace = line.length - 1
    while lastSpace > firstSpace && line.charAt(lastSpace) != ' ' do lastSpace -= 1
    if (firstSpace <= 0 || lastSpace <= firstSpace + 1) {
      fail(400, "Invalid request line")
    }
    val method = line.substring(0, firstSpace)
    val target = line.substring(firstSpace + 1, lastSpace)
    val suppliedVersion = line.substring(lastSpace + 1)
    if (!isTokenName(method)) fail(400, "Invalid method")
    if (
      suppliedVersion.length != 8 || !suppliedVersion.startsWith("HTTP/") ||
      suppliedVersion.charAt(5) < '0' || suppliedVersion.charAt(5) > '9' ||
      suppliedVersion.charAt(6) != '.' || suppliedVersion.charAt(7) < '0' || suppliedVersion.charAt(7) > '9'
    ) {
      fail(400, "Invalid HTTP version")
    }
    if (suppliedVersion.charAt(5) != '1') fail(505, "Unsupported HTTP version")
    val version = if (suppliedVersion == "HTTP/1.0") "HTTP/1.0" else "HTTP/1.1"
    RequestTargetValidation.validateTarget(method, target)

    val fields = Vector.newBuilder[(name: String, value: String)]
    var host: String = null
    var hostCount = 0
    var contentLength: String = null
    var contentLengthCount = 0
    var transferEncodings = Vector.empty[String]
    var sectionBytes = 0
    var count = 0
    var done = false
    while (!done) {
      val remaining = limits.maxHeaderSectionBytes - sectionBytes
      if (remaining < 2) fail(431, "Header section too large")
      val maximum = math.min(limits.maxHeaderLineBytes, remaining - 2)
      val field = readLine(input, input.read(), maximum, 431)
      sectionBytes += field.length + 2
      if (field.isEmpty) done = true
      else {
        if (count == limits.maxHeaderCount) fail(431, "Too many header fields")
        val parsed = {
          HttpSyntax.field(field).getOrElse(fail(400, "Invalid header field"))
        }
        fields += parsed
        val name = parsed.name
        if (name.length == 4 && HttpSyntax.equalsIgnoreCaseAscii(name, "Host")) {
          host = parsed.value
          hostCount += 1
        } else if (name.length == 14 && HttpSyntax.equalsIgnoreCaseAscii(name, "Content-Length")) {
          contentLength = parsed.value
          contentLengthCount += 1
        } else if (name.length == 17 && HttpSyntax.equalsIgnoreCaseAscii(name, "Transfer-Encoding")) {
          transferEncodings = transferEncodings :+ parsed.value
        }
        count += 1
      }
    }
    val headers = fields.result()
    if (hostCount > 1 || (version == "HTTP/1.1" && hostCount == 0)) {
      fail(400, "Missing or repeated Host")
    }
    if (hostCount == 1) RequestTargetValidation.validateHost(host)
    val framing = selectFraming(contentLength, contentLengthCount, transferEncodings, version)
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
      contentLength: String,
      contentLengthCount: Int,
      transferEncodings: Vector[String],
      version: String
  ): BodyFraming = {
    if (contentLengthCount > 0 && transferEncodings.nonEmpty) {
      fail(400, "Conflicting body framing")
    }
    if (transferEncodings.nonEmpty) {
      if (version == "HTTP/1.0") fail(400, "Transfer-Encoding on HTTP/1.0")
      val codings = HttpSyntax
        .listElements(transferEncodings)
        .getOrElse(fail(400, "Too many empty transfer codings"))
      if (codings.isEmpty || !areTokens(codings)) {
        fail(400, "Invalid transfer coding")
      }
      if (
        !HttpSyntax.equalsIgnoreCaseAscii(codings.last, "chunked") ||
        codings.count(coding => HttpSyntax.equalsIgnoreCaseAscii(coding, "chunked")) != 1
      ) {
        fail(400, "Chunked must occur exactly once, last")
      }
      if (codings.size != 1) fail(501, "Unsupported transfer coding")
      Chunked
    } else if (contentLengthCount > 0) {
      if (contentLengthCount != 1) fail(400, "Repeated Content-Length")
      val value = contentLength
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

  private def fail(code: Int, message: String): Nothing = {
    throw new ParseException(code, message)
  }

}
