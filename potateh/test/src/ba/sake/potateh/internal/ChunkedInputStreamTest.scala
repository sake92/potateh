package ba.sake.potateh.internal

import java.io.{
  BufferedInputStream,
  ByteArrayInputStream,
  ByteArrayOutputStream,
  EOFException,
  IOException,
  InputStream
}
import java.net.ProtocolException
import java.nio.charset.StandardCharsets.ISO_8859_1

import ChunkedInputStream.Limits

class ChunkedInputStreamTest extends munit.FunSuite {
  test("bodyLimitRejectsBeforeReadingAnOversizedChunkAndLatchesFailure") {
    val source = stream("2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n")
    val body = new ChunkedInputStream(source, maxBodyBytes = Some(3))
    val buffer = new Array[Byte](8)
    assertEquals(body.read(buffer), 2)
    assertEquals(new String(buffer, 0, 2, ISO_8859_1), "ab")
    val error = intercept[ChunkedInputStream.BodyTooLargeException] {
      body.read(buffer)
    }
    assert(intercept[IOException] { body.read() } eq error)
    assert(intercept[IOException] { body.skip(1) } eq error)
    assert(!(body.isComplete))
    assertEquals(source.read(), 'c'.toInt)
  }

  test("cumulativeBodyLimitCannotOverflowLong") {
    val source = stream("1\r\na\r\n7fffffffffffffff\r\nN")
    val body = {
      new ChunkedInputStream(source, maxBodyBytes = Some(Long.MaxValue))
    }
    assertEquals(body.read(), 'a'.toInt)
    intercept[ChunkedInputStream.BodyTooLargeException] { body.read() }
    assertEquals(source.read(), 'N'.toInt)
  }

  test("decodesChunksAndPreservesNextRequest") {
    val source = stream(
      "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
        "02\r\n\u00ffa\r\na\r\n0123456789\r\nB\r\nabcdefghijk\r\n000\r\n\r\n" + nextRequest
    )
    assertEquals(
      RequestHeadParser.read(source).get.framing,
      RequestHeadParser.Chunked
    )
    val body = new ChunkedInputStream(source)
    assertEquals(body.read(), 255)
    val buffer = Array.fill[Byte](5)(42)
    assertEquals(body.read(buffer, 2, 3), 1)
    assertEquals(buffer.toSeq, Array[Byte](42, 42, 97, 42, 42).toSeq)
    assertEquals(drain(body), "0123456789abcdefghijk")
    assert(body.isComplete)
    assertEquals(body.read(), -1)
    assertEquals(body.read(buffer), -1)
    assertEquals(body.read(buffer, 5, 0), 0)
    assertEquals(RequestHeadParser.read(source).get.target, "/next")
  }

  test("completionRequiresTheTrailerTerminator") {
    val source = stream("1\r\nx\r\n0\r\n\r\n" + nextRequest)
    val body = new ChunkedInputStream(source)
    assert(!(body.isComplete))
    assertEquals(body.read(new Array[Byte](0)), 0)
    assertEquals(
      source.read(),
      '1'.toInt
    ) // Constructor and empty read left source alone.
    val emptySource = stream("0\r\n\r\n" + nextRequest)
    val empty = new ChunkedInputStream(emptySource)
    assert(!(empty.isComplete))
    assertEquals(empty.read(), -1)
    assert(empty.isComplete)
    assertEquals(RequestHeadParser.read(emptySource).get.target, "/next")
    val exact = new ChunkedInputStream(stream("1\r\nx\r\n0\r\n\r\n"))
    assertEquals(exact.read(), 'x'.toInt)
    assert(!(exact.isComplete))
    assertEquals(exact.read(), -1)
    assert(exact.isComplete)
  }

  test("acceptsChunkExtensions") {
    List(
      ";name",
      ";name=token",
      ";a=\"\";b=foo",
      ";quoted=\"x;= y\\\"z\\\\\"",
      " \t; name \t= \tvalue\t; other=\"\t\u00e9\"",
      ";symbol=!#$%&'*+-.^_`|~"
    ).foreach { extension =>
      val body = new ChunkedInputStream(
        stream("1" + extension + "\r\nx\r\n0" + extension + "\r\n\r\n")
      )
      assertEquals(drain(body), "x", extension)
      assert(body.isComplete)
    }
  }

  test("rejectsMalformedChunkFraming") {
    val badSizes = List(
      "",
      " 1",
      "+1",
      "-1",
      "0x1",
      "g",
      "1 0",
      "1 ",
      "8000000000000000",
      "FFFFFFFFFFFFFFFFF",
      "1;",
      "1;=x",
      "1;a=",
      "1;a= x y",
      "1;a=\"unterminated",
      "1;a=\"bad\u0000\"",
      "1;a=\"bad\u007f\"",
      "1;a=\"bad\\\u0001\"",
      "1;a=\"x\"junk",
      "1;a=\"x\" ",
      "1;a ",
      "1\u0000"
    )
    val wires = badSizes.map(_ + "\r\nx\r\n0\r\n\r\n") ++ List(
      "1\nx\r\n0\r\n\r\n",
      "1\rXx\r\n0\r\n\r\n",
      "1\r\nxX\n0\r\n\r\n",
      "1\r\nx\rX0\r\n\r\n",
      "1\r\nx\n0\r\n\r\n",
      "0\r\n\n"
    )
    wires.foreach { wire =>
      val body = new ChunkedInputStream(stream(wire))
      val error = intercept[ProtocolException] { drain(body) }
      assert(!(body.isComplete))
      assert(intercept[IOException] { body.read() } eq error)
    }
  }

  test("supportsLongChunkSizes") {
    val body = new ChunkedInputStream(stream("7fffffffffffffff\r\nabc"))
    assertEquals(body.read(), 'a'.toInt)
    val buffer = new Array[Byte](8)
    assertEquals(body.read(buffer), 2)
    assertEquals(buffer(0), 'b'.toByte)
    assertEquals(buffer(1), 'c'.toByte)
    assert(!(body.isComplete))
    intercept[EOFException] { body.read() }
  }

  test("validatesAndExposesSeparateTrailers") {
    val source = stream(
      "POST / HTTP/1.1\r\nHost: original\r\nTransfer-Encoding: chunked\r\n\r\n" +
        "1\r\nx\r\n0\r\nX-Checksum: one\r\nx-checksum: two\r\nX-Note:\t caf\u00e9 \t\r\n" +
        "Empty:\r\nHost: attacker\r\nContent-Length: 999\r\nAuthorization: fake\r\n\r\n" + nextRequest
    )
    val head = RequestHeadParser.read(source).get
    val body = new ChunkedInputStream(source)
    assertEquals(drain(body), "x")
    assert(body.isComplete)
    assertEquals(body.trailers.flatMap(_.first("X-Checksum")), Some("one"))
    assertEquals(
      head.headers,
      Vector("Host" -> "original", "Transfer-Encoding" -> "chunked")
    )
    assertEquals(RequestHeadParser.read(source).get.target, "/next")
    List(
      " Folded: x",
      "\tfolded",
      "Bad : value",
      ": value",
      "NoColon",
      "Bad(name): x",
      "X: bad\u0000",
      "X: bad\u007f",
      "X: bad\u000b"
    ).foreach { trailer =>
      intercept[ProtocolException] {
        drain(new ChunkedInputStream(stream("0\r\n" + trailer + "\r\n\r\n")))
      }
    }
  }

  test("enforcesMetadataLimits") {
    val wire = "1;a=b\r\nx\r\n0;c\r\nX: y\r\n\r\n"
    val limits = Limits(
      maxChunkLineBytes = 5,
      maxChunkExtensionBytes = 6,
      maxTrailerLineBytes = 4,
      maxTrailerSectionBytes = 8,
      maxTrailerCount = 1
    )
    assertEquals(drain(new ChunkedInputStream(stream(wire), limits)), "x")
    List(
      limits.copy(maxChunkLineBytes = 4),
      limits.copy(maxChunkExtensionBytes = 5),
      limits.copy(maxTrailerLineBytes = 3),
      limits.copy(maxTrailerSectionBytes = 7),
      limits.copy(maxTrailerCount = 0)
    ).foreach { tooSmall =>
      intercept[ProtocolException] {
        drain(new ChunkedInputStream(stream(wire), tooSmall))
      }
    }
    assertEquals(
      drain(
        new ChunkedInputStream(
          stream("0\r\n\r\n"),
          Limits(
            maxChunkLineBytes = 1,
            maxChunkExtensionBytes = 0,
            maxTrailerLineBytes = 0,
            maxTrailerSectionBytes = 2,
            maxTrailerCount = 0
          )
        )
      ),
      ""
    )
    intercept[ProtocolException] {
      drain(
        new ChunkedInputStream(stream("0\r\nX: " + ("a" * 8190) + "\r\n\r\n"))
      )
    }
    intercept[ProtocolException] {
      drain(
        new ChunkedInputStream(stream("0\r\n" + ("X: y\r\n" * 101) + "\r\n"))
      )
    }
    intercept[ProtocolException] {
      drain(
        new ChunkedInputStream(
          stream("0\r\n" + (("X: " + ("a" * 8000) + "\r\n") * 9) + "\r\n")
        )
      )
    }
    intercept[ProtocolException] {
      drain(
        new ChunkedInputStream(
          stream("1;" + ("a" * 8191) + "\r\nx\r\n0\r\n\r\n")
        )
      )
    }
    val extendedChunk = "1;" + ("a" * 8000) + "\r\nx\r\n"
    intercept[ProtocolException] {
      drain(new ChunkedInputStream(stream((extendedChunk * 9) + "0\r\n\r\n")))
    }
  }

  test("handlesEveryFragmentBoundary") {
    val wire = "3;a=\"x\\\"y\"\r\nabc\r\n2\r\nde\r\n0;end\r\nX: y\r\n\r\n"
    val bytes = (wire + nextRequest).getBytes(ISO_8859_1)
    (1 until wire.length).foreach { boundary =>
      val source = new ByteArrayInputStream(bytes) {
        override def read(b: Array[Byte], off: Int, len: Int): Int = {
          val maximum = if (pos < boundary) boundary - pos else 1
          super.read(b, off, math.min(len, maximum))
        }
      }
      val input = new BufferedInputStream(source, 8)
      try {
        val body = new ChunkedInputStream(input)
        assertEquals(drain(body), "abcde")
        assert(body.isComplete)
        assertEquals(RequestHeadParser.read(input).get.target, "/next")
      } finally input.close()
    }
  }

  test("truncationIsTerminal") {
    val wire = "2;ext=\"a\"\r\nab\r\n0\r\nX: y\r\n\r\n"
    (0 until wire.length).foreach { end =>
      val body = new ChunkedInputStream(stream(wire.substring(0, end)))
      val error = intercept[EOFException] { drain(body) }
      assert(!(body.isComplete))
      assert(intercept[IOException] { body.read() } eq error)
      assert(intercept[IOException] { body.skip(1) } eq error)
      assert(intercept[IOException] { body.available() } eq error)
    }
  }

  test("sourceFailuresAreTerminal") {
    val wire = "2\r\nab\r\n0\r\nX: y\r\n\r\n"
    for {
      boundary <- 0 until wire.length
      operation <- List("single", "bulk", "skip")
    } {
      val error = new IOException("source failure")
      var broken = true
      var calls = 0
      val source = new ByteArrayInputStream(wire.getBytes(ISO_8859_1)) {
        override def read(): Int = {
          calls += 1
          if (broken && pos == boundary) throw error
          super.read()
        }
        override def read(b: Array[Byte], off: Int, len: Int): Int = {
          calls += 1
          if (broken && pos == boundary) throw error
          val maximum = if (broken && pos < boundary) boundary - pos else len
          super.read(b, off, math.min(len, maximum))
        }
      }
      val body = new ChunkedInputStream(source)
      assert(intercept[IOException] {
        {
          if (operation == "single") while (body.read() != -1) ()
          else if (operation == "bulk") drain(body)
          else body.skip(Long.MaxValue)
          ()
        }
      } eq error)
      val callsAtFailure = calls
      broken = false
      assert(intercept[IOException] { body.read() } eq error)
      assert(intercept[IOException] { body.read(new Array[Byte](4)) } eq error)
      assert(intercept[IOException] { body.available() } eq error)
      assertEquals(calls, callsAtFailure)
      assert(!(body.isComplete))
    }
    val error = new IOException("availability failed")
    val source = {
      new ByteArrayInputStream("2\r\nab\r\n0\r\n\r\n".getBytes(ISO_8859_1)) {
        override def available(): Int = throw error
      }
    }
    val body = new ChunkedInputStream(source)
    assertEquals(body.read(), 'a'.toInt)
    assert(intercept[IOException] { body.available() } eq error)
    assert(intercept[IOException] { body.read() } eq error)
  }

  test("skipCountsOnlyPayloadBytes") {
    val source = new ByteArrayInputStream(
      ("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n" + nextRequest).getBytes(ISO_8859_1)
    ) {
      override def read(b: Array[Byte], off: Int, len: Int): Int = {
        super.read(b, off, math.min(len, 1))
      }
      override def skip(count: Long): Long = {
        throw new AssertionError("Must decode skipped bytes")
      }
    }
    val body = new ChunkedInputStream(source)
    assertEquals(body.skip(-1), 0L)
    assertEquals(body.skip(0), 0L)
    assertEquals(body.skip(4), 4L)
    assertEquals(body.read(), 'e'.toInt)
    assert(!(body.isComplete))
    assertEquals(body.skip(Long.MaxValue), 0L)
    assert(body.isComplete)
    assertEquals(RequestHeadParser.read(source).get.target, "/next")
    val all = new ChunkedInputStream(stream("1\r\na\r\n1\r\nb\r\n0\r\n\r\n"))
    assertEquals(all.skip(Long.MaxValue), 2L)
    assert(all.isComplete)
  }

  test("availabilityNeverConsumesFraming") {
    val source = stream("3\r\nabc\r\n0\r\n\r\n" + nextRequest)
    val body = new ChunkedInputStream(source)
    val initial = source.available()
    assertEquals(body.available(), 0)
    assertEquals(source.available(), initial)
    assertEquals(body.read(), 'a'.toInt)
    assertEquals(body.available(), 2)
    assertEquals(body.skip(2), 2L)
    val afterData = source.available()
    assertEquals(body.available(), 0)
    assertEquals(source.available(), afterData)
    assert(!(body.isComplete))
    assertEquals(body.read(), -1)
    assertEquals(body.available(), 0)
    assertEquals(RequestHeadParser.read(source).get.target, "/next")
  }

  test("rejectsInvalidArgumentsWithoutConsumingInput") {
    intercept[NullPointerException] { new ChunkedInputStream(null) }
    val source = stream("1\r\nx\r\n0\r\n\r\n")
    val body = new ChunkedInputStream(source)
    intercept[NullPointerException] { body.read(null) }
    intercept[NullPointerException] { body.read(null, 0, 0) }
    val buffer = new Array[Byte](4)
    List((-1, 1), (0, -1), (5, 0), (3, 2), (1, Int.MaxValue), (Int.MaxValue, 1))
      .foreach { case (offset, length) =>
        intercept[IndexOutOfBoundsException] {
          body.read(buffer, offset, length)
        }
      }
    assertEquals(drain(body), "x")
    assert(body.isComplete)
  }

  test("closeAndResetPreserveConnectionOwnership") {
    var sourceClosed = false
    val source = {
      new ByteArrayInputStream("2\r\nab\r\n0\r\n\r\n".getBytes(ISO_8859_1)) {
        override def close(): Unit = sourceClosed = true
      }
    }
    val body = new ChunkedInputStream(source)
    assert(!(body.markSupported()))
    assertEquals(body.read(), 'a'.toInt)
    body.mark(10)
    intercept[IOException] { body.reset() }
    body.close()
    body.close()
    assert(!(sourceClosed))
    assert(!(body.isComplete))
    intercept[IOException] { body.read() }
    intercept[IOException] { body.read(new Array[Byte](0)) }
    intercept[IOException] { body.skip(0) }
    intercept[IOException] { body.available() }
    assertEquals(source.read(), 'b'.toInt)
    val completeSource = {
      new ByteArrayInputStream("0\r\n\r\nNEXT".getBytes(ISO_8859_1)) {
        override def close(): Unit = sourceClosed = true
      }
    }
    val complete = new ChunkedInputStream(completeSource)
    assertEquals(complete.read(), -1)
    complete.close()
    assert(complete.isComplete)
    assert(!(sourceClosed))
    assertEquals(completeSource.read(), 'N'.toInt)
  }

  private val nextRequest = "GET /next HTTP/1.0\r\n\r\n"

  private def stream(wire: String): ByteArrayInputStream = {
    new ByteArrayInputStream(wire.getBytes(ISO_8859_1))
  }

  private def drain(input: InputStream): String = {
    val output = new ByteArrayOutputStream()
    val buffer = new Array[Byte](7)
    var count = input.read(buffer)
    while (count != -1) {
      assert(count > 0, "Read must make progress")
      output.write(buffer, 0, count)
      count = input.read(buffer)
    }
    new String(output.toByteArray(), ISO_8859_1)
  }

}
