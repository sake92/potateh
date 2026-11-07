package ba.sake.potateh.internal

import java.io.{
  BufferedInputStream,
  ByteArrayInputStream,
  IOException,
  InputStream
}
import java.nio.charset.StandardCharsets.ISO_8859_1

import RequestHeadParser._

class RequestHeadParserTest extends munit.FunSuite {
  test("parsesMetadataAndRepeatedFields") {
    val head = parse(
      "CuStOm /a%2Fb?q=x HTTP/1.1\r\nhOsT: example.org\r\n" +
        "X-Value:\t one  two \t\r\nx-value: caf\u00e9\r\nEmpty:\r\n\r\n"
    )
    assertEquals(head.method, "CuStOm")
    assertEquals(head.target, "/a%2Fb?q=x")
    assertEquals(head.version, "HTTP/1.1")
    assertEquals(
      head.headers,
      Vector(
        "hOsT" -> "example.org",
        "X-Value" -> "one  two",
        "x-value" -> "caf\u00e9",
        "Empty" -> ""
      )
    )
    assertEquals(head.framing, NoBody)
  }

  test("acceptsRequestTargetForms") {
    val requests = List(
      "GET / HTTP/1.0",
      "GET //double/path?x=%20 HTTP/1.1",
      "GET http://example.org/a%2Fb?q=2 HTTP/1.1",
      "CONNECT example.org:443 HTTP/1.1",
      "CONNECT [::1]:443 HTTP/1.1",
      "OPTIONS * HTTP/1.1"
    )
    requests.foreach { line =>
      val head = parse(line + "\r\nHost: [::1]:8080\r\n\r\n")
      assertEquals(head.target, line.split(" ")(1))
    }
    assertEquals(parse("GET / HTTP/1.0\r\n\r\n").framing, NoBody)
    assertEquals(parse("OPTIONS * HTTP/1.1\r\nHost:\r\n\r\n").framing, NoBody)
  }

  test("rejectsMalformedRequestLines") {
    List(
      "GET  / HTTP/1.1",
      "GET /  HTTP/1.1",
      " GET / HTTP/1.1",
      "GET / HTTP/1.1 ",
      "GET\t/ HTTP/1.1",
      "G(ET / HTTP/1.1",
      "GET /bad\tpath HTTP/1.1",
      "GET /bad\u0000path HTTP/1.1",
      "GET /caf\u00e9 HTTP/1.1",
      "GET /bad%2 HTTP/1.1",
      "GET /a#fragment HTTP/1.1",
      "GET relative HTTP/1.1",
      "GET * HTTP/1.1",
      "CONNECT /path HTTP/1.1",
      "CONNECT host HTTP/1.1",
      "CONNECT host:443/path HTTP/1.1",
      "GET http://user@host/path HTTP/1.1",
      "GET / HTTP/1.10",
      "GET / http/1.1",
      "GET /",
      "",
      "GET / HTTP/1.1\nHost: x",
      "GET / HTTP/1.1\rX"
    ).foreach(line => rejected(line + "\r\nHost: example.org\r\n\r\n", 400))
    rejected("GET / HTTP/2.0\r\n\r\n", 505)
  }

  test("rejectsMalformedHeadersAndHost") {
    List(
      "",
      "Host: x\r\nHost: x\r\n",
      "Host: x\r\nhost: y\r\n",
      "Host : x\r\n",
      " Host: x\r\n",
      "Host: x\r\n\tfolded\r\n",
      "Host: x\r\nNoColon\r\n",
      "Host: x\r\n: empty-name\r\n",
      "Host: x\r\nBad(name): value\r\n",
      "Host: x\r\nX: bad\u0000value\r\n",
      "Host: x\r\nX: bad\u007fvalue\r\n",
      "Host: x\r\nX: bad\u000bvalue\r\n",
      "Host: x\nX: y\r\n",
      "Host: x\rX: y\r\n",
      "Host: x,y\r\n",
      "Host: user@host\r\n",
      "Host: host/path\r\n",
      "Host: host?query\r\n",
      "Host: host#fragment\r\n",
      "Host: a b\r\n"
    ).foreach(fields => rejected("GET / HTTP/1.1\r\n" + fields + "\r\n", 400))
    rejected("GET / HTTP/1.0\r\nHost: x\r\nHost: x\r\n\r\n", 400)
  }

  test("distinguishesOversizedMethodFromOversizedTarget") {
    rejected(("A" * 9000) + " / HTTP/1.1\r\nHost: x\r\n\r\n", 501)
    rejected("GET /" + ("a" * 9000) + " HTTP/1.1\r\nHost: x\r\n\r\n", 414)
  }

  test("selectsFixedLengthOrNoBody") {
    assertEquals(parse(request("", "POST")).framing, NoBody)
    List("0" -> 0L, "0042" -> 42L, "9223372036854775807" -> Long.MaxValue)
      .foreach { case (wire, length) =>
        assertEquals(
          parse(request("cOnTeNt-LeNgTh:\t" + wire + " \r\n")).framing,
          FixedLength(length)
        )
      }
    assertEquals(
      parse("GET / HTTP/1.0\r\nContent-Length: 3\r\n\r\n").framing,
      FixedLength(3)
    )
    // Request method does not override request-body framing, including HEAD.
    assertEquals(
      parse(request("Content-Length: 7\r\n", "HEAD")).framing,
      FixedLength(7)
    )
  }

  test("rejectsInvalidOrRepeatedLengths") {
    List(
      "",
      "+1",
      "-1",
      "1 2",
      "1\t2",
      "1.0",
      "0x10",
      "1,1",
      "1, 2",
      "9223372036854775808",
      "999999999999999999999999999999"
    ).foreach { value =>
      rejected(request("Content-Length: " + value + "\r\n"), 400)
    }
    rejected(request("Content-Length: 2\r\ncontent-length: 2\r\n"), 400)
    rejected(request("Content-Length: 2\r\ncontent-length: 3\r\n"), 400)
  }

  test("validatesTransferEncoding") {
    assertEquals(
      parse(request("Transfer-Encoding: ChUnKeD\r\n")).framing,
      Chunked
    )
    List(
      "",
      "identity",
      "gzip",
      "chunked, gzip",
      "chunked, chunked",
      ",chunked",
      "chunked,",
      "chunked;foo=bar"
    ).foreach { value =>
      rejected(request("Transfer-Encoding: " + value + "\r\n"), 400)
    }
    rejected(request("Transfer-Encoding: gzip, chunked\r\n"), 501)
    rejected(
      request("Transfer-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n"),
      501
    )
    rejected(
      request("Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n"),
      400
    )
    rejected(
      request("Content-Length: 0\r\nTransfer-Encoding: chunked\r\n"),
      400
    )
    rejected("POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n", 400)
  }

  test("enforcesLimitsAtBoundaries") {
    val wire = "GET / HTTP/1.1\r\nHost: x\r\nX: y\r\n\r\n"
    val limits = Limits(
      requestLineBytes = 14,
      headerLineBytes = 7,
      headerSectionBytes = 17,
      headerCount = 2
    )
    assertEquals(parse(wire, limits).framing, NoBody)
    rejected(wire, 414, limits.copy(requestLineBytes = 13))
    rejected(wire, 431, limits.copy(headerLineBytes = 6))
    rejected(wire, 431, limits.copy(headerSectionBytes = 16))
    rejected(wire, 431, limits.copy(headerCount = 1))
    val emptyHeaders =
      Limits(headerLineBytes = 0, headerSectionBytes = 2, headerCount = 0)
    assertEquals(parse("GET / HTTP/1.0\r\n\r\n", emptyHeaders).framing, NoBody)
    rejected("GET / HTTP/1.0\r\nX: y\r\n\r\n", 431, emptyHeaders)
    // Also check the shipped defaults, not only injected small limits.
    rejected(request("X: " + ("a" * 8190) + "\r\n"), 431)
    rejected(request("X: y\r\n" * 100), 431) // Host is one field too.
    rejected(request(("X: " + ("a" * 8000) + "\r\n") * 9), 431)
    rejected("GET /" + ("a" * 8192) + " HTTP/1.1\r\nHost: x\r\n\r\n", 414)
  }

  test("distinguishesCleanEofFromTruncation") {
    assertEquals(read(stream("")), None)
    val complete = request("Content-Length: 2\r\n")
    (1 until complete.length).foreach { end =>
      rejected(complete.substring(0, end), 400)
    }
  }

  test("propagatesInputFailure") {
    val failure = new IOException("source failed")
    val input = new InputStream {
      private var remaining = 8
      override def read(): Int = {
        if (remaining == 0) throw failure
        remaining -= 1
        'G'.toInt
      }
    }
    try {
      read(input)
      fail("Expected source failure")
    } catch { case actual: IOException => assert(actual eq failure) }
  }

  test("preservesBodiesAndFollowingRequests") {
    val second = "GET /next HTTP/1.0\r\n\r\n"
    val input =
      stream(request("Content-Length: 4\r\n", "POST") + "body" + second)
    assertEquals(read(input).get.framing, FixedLength(4))
    assertEquals(
      new String(Array.fill[Byte](4)(input.read().toByte), ISO_8859_1),
      "body"
    )
    assertEquals(read(input).get.target, "/next")
    assertEquals(read(input), None)

    val chunked =
      stream(request("Transfer-Encoding: chunked\r\n") + "0\r\n\r\n" + second)
    assertEquals(read(chunked).get.framing, Chunked)
    assertEquals(chunked.read(), '0'.toInt)

    val pipeline = stream(second + second)
    assertEquals(read(pipeline).get.target, "/next")
    assertEquals(read(pipeline).get.target, "/next")
    assertEquals(read(pipeline), None)
  }

  test("handlesEveryFragmentBoundary") {
    val head = request("Content-Length: 3\r\nX-Test: abc\r\n", "POST")
    val bytes =
      (head + "xyz" + "GET /next HTTP/1.0\r\n\r\n").getBytes(ISO_8859_1)
    (1 until head.length).foreach { boundary =>
      val source = new ByteArrayInputStream(bytes) {
        override def read(b: Array[Byte], off: Int, len: Int): Int = {
          val maximum = if (pos < boundary) boundary - pos else 1
          super.read(b, off, math.min(len, maximum))
        }
      }
      val input = new BufferedInputStream(source, 16)
      try {
        assertEquals(read(input).get.framing, FixedLength(3))
        assertEquals(input.read(), 'x'.toInt)
        assertEquals(input.read(), 'y'.toInt)
        assertEquals(input.read(), 'z'.toInt)
        assertEquals(read(input).get.target, "/next")
        assertEquals(read(input), None)
      } finally input.close()
    }
  }

  private def request(fields: String, method: String = "GET"): String =
    method + " / HTTP/1.1\r\nHost: example.org\r\n" + fields + "\r\n"

  private def stream(wire: String): ByteArrayInputStream =
    new ByteArrayInputStream(wire.getBytes(ISO_8859_1))

  private def parse(wire: String, limits: Limits = Limits()): RequestHead =
    read(stream(wire), limits).get

  private def rejected(
      wire: String,
      code: Int,
      limits: Limits = Limits()
  ): Unit = {
    try {
      parse(wire, limits)
      fail("Expected rejection: " + wire)
    } catch {
      case error: ParseException =>
        assertEquals(error.responseCode, code, "Response code for " + wire)
    }
  }
}
