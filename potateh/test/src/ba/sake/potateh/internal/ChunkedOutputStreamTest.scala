package ba.sake.potateh.internal

import java.io.{BufferedOutputStream, ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, IOException, OutputStream}
import java.nio.charset.StandardCharsets.ISO_8859_1


class ChunkedOutputStreamTest extends munit.FunSuite {
  test("encodesWritesWithHexSizes") {
    val sink = new ByteArrayOutputStream()
    val body = new ChunkedOutputStream(sink)
    body.write(0x1ff)
    body.write(-2)
    val bytes = "_0123456789abcdef_".getBytes(ISO_8859_1)
    body.write(bytes, 1, 16)
    body.write("0123456789".getBytes(ISO_8859_1))
    val expected = "1\r\n\u00ff\r\n1\r\n\u00fe\r\n10\r\n0123456789abcdef\r\na\r\n0123456789\r\n"
    assertEquals(sink.toByteArray() .toSeq, expected.getBytes(ISO_8859_1) .toSeq)
    assertEquals(bytes .toSeq, "_0123456789abcdef_".getBytes(ISO_8859_1) .toSeq)
    assert(!(body.isComplete))
    body.close()
    assert(body.isComplete)
    assertEquals(sink.toByteArray() .toSeq, (expected + "0\r\n\r\n").getBytes(ISO_8859_1) .toSeq)
  }

  test("emptyWritesEmitNothingAndEmptyCloseTerminates") {
    val sink = new FailingOutputStream()
    val body = new ChunkedOutputStream(sink)
    body.write(new Array[Byte](0))
    body.write(new Array[Byte](4), 4, 0)
    assertEquals(sink.writes, 0)
    assertEquals(sink.flushes, 0)
    assert(!(body.isComplete))
    body.close()
    assertEquals(sink.bytes.toByteArray() .toSeq, "0\r\n\r\n".getBytes(ISO_8859_1) .toSeq)
    assertEquals(sink.flushes, 1)
    assert(body.isComplete)

    val nonemptySink = new ByteArrayOutputStream()
    val nonempty = new ChunkedOutputStream(nonemptySink)
    nonempty.write('a')
    nonempty.write(new Array[Byte](0))
    nonempty.write('b')
    nonempty.close()
    assertEquals(nonemptySink.toByteArray() .toSeq, "1\r\na\r\n1\r\nb\r\n0\r\n\r\n".getBytes(ISO_8859_1) .toSeq)
  }

  test("flushDoesNotTerminateTheBody") {
    val sink = new ByteArrayOutputStream()
    val buffered = new BufferedOutputStream(sink, 64)
    try {
      val body = new ChunkedOutputStream(buffered)
      body.write('a')
      assertEquals(sink.size(), 0)
      body.flush()
      assertEquals(sink.toByteArray() .toSeq, "1\r\na\r\n".getBytes(ISO_8859_1) .toSeq)
      assert(!(body.isComplete))
      body.write("bc".getBytes(ISO_8859_1))
      body.close()
      assertEquals(sink.toByteArray() .toSeq, "1\r\na\r\n2\r\nbc\r\n0\r\n\r\n".getBytes(ISO_8859_1) .toSeq)
      assert(body.isComplete)
    } finally buffered.close()
  }

  test("closePreservesConnectionOwnership") {
    val sink = new FailingOutputStream()
    val body = new ChunkedOutputStream(sink)
    body.write('a')
    body.close()
    body.close()
    assert(body.isComplete)
    assertEquals(sink.flushes, 1)
    assertEquals(sink.closes, 0)
    intercept[IOException] { body.write('x') }
    intercept[IOException] { body.write(new Array[Byte](0)) }
    intercept[IOException] { body.flush() }
    val nextHeaders = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
    sink.write(nextHeaders.getBytes(ISO_8859_1))
    val next = new ChunkedOutputStream(sink)
    next.write('b')
    next.close()
    val expected = "1\r\na\r\n0\r\n\r\n" + nextHeaders + "1\r\nb\r\n0\r\n\r\n"
    assertEquals(sink.bytes.toByteArray() .toSeq, expected.getBytes(ISO_8859_1) .toSeq)
    assertEquals(sink.flushes, 2)
    assertEquals(sink.closes, 0)
  }

  test("rejectsInvalidArgumentsWithoutEmittingBytes") {
    intercept[NullPointerException] { new ChunkedOutputStream(null) }
    val sink = new FailingOutputStream()
    val body = new ChunkedOutputStream(sink)
    intercept[NullPointerException] { body.write(null) }
    intercept[NullPointerException] { body.write(null, 0, 0) }
    val bytes = "abc".getBytes(ISO_8859_1)
    List((-1, 1), (0, -1), (4, 0), (2, 2), (1, Int.MaxValue), (Int.MaxValue, 1)).foreach {
      case (offset, length) =>
        intercept[IndexOutOfBoundsException] { body.write(bytes, offset, length) }
    }
    assertEquals(sink.writes, 0)
    body.write(bytes)
    body.close()
    assert(body.isComplete)
    assertEquals(sink.bytes.toByteArray() .toSeq, "3\r\nabc\r\n0\r\n\r\n".getBytes(ISO_8859_1) .toSeq)
  }

  test("writeFailuresAreTerminal") {
    for (mode <- List("single", "bulk")) {
      val chunk = if (mode == "single") "1\r\nb\r\n" else "3\r\nbcd\r\n"
      for (boundary <- 0 until chunk.length) {
        val sink = new FailingOutputStream()
        val body = new ChunkedOutputStream(sink)
        body.write('a')
        val initial = "1\r\na\r\n"
        sink.failAt = initial.length + boundary
        assert(intercept[IOException] { {
          if (mode == "single") body.write('b') else body.write("bcd".getBytes(ISO_8859_1))
        } } eq sink.error)
        val expected = (initial + chunk.substring(0, boundary)).getBytes(ISO_8859_1)
        assertEquals(sink.bytes.toByteArray() .toSeq, expected .toSeq)
        val writesAtFailure = sink.writes
        sink.failAt = -1
        assert(intercept[IOException] { body.write('x') } eq sink.error)
        assert(intercept[IOException] { body.write(Array[Byte](1)) } eq sink.error)
        assert(intercept[IOException] { body.flush() } eq sink.error)
        assert(intercept[IOException] { body.close() } eq sink.error)
        body.close()
        assert(!(body.isComplete))
        assertEquals(sink.writes, writesAtFailure)
        assertEquals(sink.flushes, 0)
        assertEquals(sink.closes, 0)
        assertEquals(sink.bytes.toByteArray() .toSeq, expected .toSeq)
      }
    }
  }

  test("terminationFailuresAreTerminal") {
    val ending = "0\r\n\r\n"
    for (boundary <- 0 until ending.length) {
      val sink = new FailingOutputStream()
      val body = new ChunkedOutputStream(sink)
      body.write('a')
      val initial = "1\r\na\r\n"
      sink.failAt = initial.length + boundary
      assert(intercept[IOException] { body.close() } eq sink.error)
      val expected = (initial + ending.substring(0, boundary)).getBytes(ISO_8859_1)
      assertEquals(sink.bytes.toByteArray() .toSeq, expected .toSeq)
      val writesAtFailure = sink.writes
      sink.failAt = -1
      body.close()
      assert(intercept[IOException] { body.write('x') } eq sink.error)
      assert(intercept[IOException] { body.flush() } eq sink.error)
      assert(!(body.isComplete))
      assertEquals(sink.writes, writesAtFailure)
      assertEquals(sink.flushes, 0)
      assertEquals(sink.closes, 0)
      assertEquals(sink.bytes.toByteArray() .toSeq, expected .toSeq)
    }
  }

  test("flushFailuresAreTerminal") {
    for (mode <- List("flush", "close")) {
      val sink = new FailingOutputStream()
      val body = new ChunkedOutputStream(sink)
      body.write('a')
      sink.failFlush = true
      assert(intercept[IOException] { {
        if (mode == "flush") body.flush() else body.close()
      } } eq sink.error)
      val expected = if (mode == "flush") "1\r\na\r\n" else "1\r\na\r\n0\r\n\r\n"
      val writesAtFailure = sink.writes
      sink.failFlush = false
      assert(intercept[IOException] { body.write('x') } eq sink.error)
      assert(intercept[IOException] { body.flush() } eq sink.error)
      if (mode == "flush") assert(intercept[IOException] { body.close() } eq sink.error)
      body.close()
      assert(!(body.isComplete))
      assertEquals(sink.writes, writesAtFailure)
      assertEquals(sink.flushes, 1)
      assertEquals(sink.closes, 0)
      assertEquals(sink.bytes.toByteArray() .toSeq, expected.getBytes(ISO_8859_1) .toSeq)
    }
  }

  test("roundTripsAcrossWriteBoundaries") {
    val sink = new ByteArrayOutputStream()
    val expected = new ByteArrayOutputStream()
    val body = new ChunkedOutputStream(sink)
    for (size <- List(1, 2, 15, 16, 255, 256, 4097)) {
      val bytes = Array.tabulate[Byte](size)(index => ((index * 37) % 256).toByte)
      body.write(bytes)
      expected.write(bytes)
      body.write(255)
      expected.write(255)
    }
    body.close()
    val nextResponse = "HTTP/1.1 204 No Content\r\n\r\n".getBytes(ISO_8859_1)
    sink.write(nextResponse)
    val source = new ByteArrayInputStream(sink.toByteArray())
    val decoded = new ChunkedInputStream(source)
    val actual = new ByteArrayOutputStream()
    val buffer = new Array[Byte](11)
    var count = decoded.read(buffer)
    while (count != -1) {
      assert(count > 0)
      actual.write(buffer, 0, count)
      count = decoded.read(buffer)
    }
    assertEquals(actual.toByteArray() .toSeq, expected.toByteArray() .toSeq)
    assert(decoded.isComplete)
    assertEquals(source.available(), nextResponse.length)
    val following = new Array[Byte](nextResponse.length)
    new DataInputStream(source).readFully(following)
    assertEquals(following .toSeq, nextResponse .toSeq)
  }

  private class FailingOutputStream extends OutputStream {
    val bytes = new ByteArrayOutputStream()
    val error = new IOException("sink failed")
    var failAt = -1
    var failFlush = false
    var writes = 0
    var flushes = 0
    var closes = 0

    override def write(value: Int): Unit = {
      writes += 1
      if (bytes.size() == failAt) throw error
      bytes.write(value)
    }

    override def flush(): Unit = {
      flushes += 1
      if (failFlush) throw error
    }

    override def close(): Unit = closes += 1
  }
}
