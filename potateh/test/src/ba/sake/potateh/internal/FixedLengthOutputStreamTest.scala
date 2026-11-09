package ba.sake.potateh.internal

import java.io.{BufferedOutputStream, ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets.ISO_8859_1

class FixedLengthOutputStreamTest extends munit.FunSuite {
  test("noBodyCloseFlushesHeadersWithoutEmittingFraming") {
    val sink = new RecordingOutputStream()
    val connection = new BufferedOutputStream(sink, 128)
    try {
      val headers = "HTTP/1.1 204 No Content\r\n\r\n".getBytes(ISO_8859_1)
      connection.write(headers)
      val body = new FixedLengthOutputStream(connection, 0)
      body.write(new Array[Byte](0))
      assertEquals(sink.size(), 0)
      assert(!(body.isComplete))
      body.close()
      body.close()
      assert(body.isComplete)
      assertEquals(sink.toByteArray().toSeq, headers.toSeq)
      assertEquals(sink.flushes, 1)
      assertEquals(sink.closes, 0)
      intercept[IOException] { body.write('x') }
      intercept[IOException] { body.flush() }

      val nextHeaders = {
        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n".getBytes(ISO_8859_1)
      }
      connection.write(nextHeaders)
      val next = new FixedLengthOutputStream(connection, 2)
      next.write("ok".getBytes(ISO_8859_1))
      next.close()
      val expected = headers ++ nextHeaders ++ "ok".getBytes(ISO_8859_1)
      assertEquals(sink.toByteArray().toSeq, expected.toSeq)
      assert(next.isComplete)
      assertEquals(sink.flushes, 2)
      assertEquals(sink.closes, 0)
    } finally connection.close()
  }

  test("noBodyWritesFailWithoutEmittingPayload") {
    for (mode <- List("single", "bulk", "slice")) {
      val sink = new RecordingOutputStream()
      val headers = "HTTP/1.1 204 No Content\r\n\r\n".getBytes(ISO_8859_1)
      sink.write(headers)
      val body = new FixedLengthOutputStream(sink, 0)
      val error = intercept[IOException] {
        {
          if (mode == "single") body.write('x')
          else if (mode == "bulk") body.write("xy".getBytes(ISO_8859_1))
          else body.write("_xy_".getBytes(ISO_8859_1), 1, 2)
        }
      }
      assertEquals(sink.toByteArray().toSeq, headers.toSeq)
      assert(!(body.isComplete))
      assert(intercept[IOException] { body.write('y') } eq error)
      assert(intercept[IOException] { body.flush() } eq error)
      assert(intercept[IOException] { body.close() } eq error)
      body.close()
      assert(!(body.isComplete))
      assertEquals(sink.flushes, 0)
      assertEquals(sink.closes, 0)
      assertEquals(sink.toByteArray().toSeq, headers.toSeq)
    }
  }

  test("writesExactlyTheDeclaredBody") {
    val sink = new ByteArrayOutputStream()
    val body = new FixedLengthOutputStream(sink, 5)
    body.write(0x1ff)
    body.write(-2)
    val bytes = "_abc_".getBytes(ISO_8859_1)
    body.write(bytes, 1, 2)
    body.write(Array[Byte](99))
    assertEquals(
      sink.toByteArray().toSeq,
      Array[Byte](-1, -2, 97, 98, 99).toSeq
    )
    assertEquals(bytes.toSeq, "_abc_".getBytes(ISO_8859_1).toSeq)
    assert(!(body.isComplete))
    body.close()
    assert(body.isComplete)
    assertEquals(
      sink.toByteArray().toSeq,
      Array[Byte](-1, -2, 97, 98, 99).toSeq
    )
  }

  test("excessWritesAreTerminalAndDoNotEmitAPrefix") {
    for {
      mode <- List("single", "bulk")
      remaining <- List(0, 1)
    } {
      val sink = new RecordingOutputStream()
      val body = new FixedLengthOutputStream(sink, remaining + 1)
      body.write('a')
      if (mode == "single" && remaining == 1) body.write('b')
      val before = sink.toByteArray()
      val error = intercept[IOException] {
        {
          if (mode == "single") body.write('x')
          else body.write("xy".getBytes(ISO_8859_1))
        }
      }
      assertEquals(sink.toByteArray().toSeq, before.toSeq)
      assert(!(body.isComplete))
      assert(intercept[IOException] { body.write('z') } eq error)
      assert(intercept[IOException] { body.flush() } eq error)
      assert(intercept[IOException] { body.close() } eq error)
      body.close()
      assert(!(body.isComplete))
      assertEquals(sink.flushes, 0)
      assertEquals(sink.closes, 0)
      assertEquals(sink.toByteArray().toSeq, before.toSeq)
    }
  }

  test("shortBodiesFailOnClose") {
    for (written <- List(0, 2)) {
      val sink = new RecordingOutputStream()
      val body = new FixedLengthOutputStream(sink, 3)
      body.write("ab".getBytes(ISO_8859_1), 0, written)
      val error = intercept[IOException] { body.close() }
      assertEquals(sink.size(), written)
      assert(!(body.isComplete))
      assert(intercept[IOException] { body.write('x') } eq error)
      assert(intercept[IOException] { body.flush() } eq error)
      body.close()
      assertEquals(sink.flushes, 0)
      assertEquals(sink.closes, 0)
    }
  }

  test("flushDoesNotCompleteTheBody") {
    val sink = new ByteArrayOutputStream()
    val buffered = new BufferedOutputStream(sink, 16)
    try {
      val body = new FixedLengthOutputStream(buffered, 3)
      body.write('a')
      assertEquals(sink.size(), 0)
      body.flush()
      assertEquals(sink.toByteArray().toSeq, Array[Byte](97).toSeq)
      assert(!(body.isComplete))
      body.write("bc".getBytes(ISO_8859_1))
      assertEquals(sink.size(), 1)
      body.flush()
      assertEquals(sink.size(), 3)
      assert(!(body.isComplete))
      body.close()
      assert(body.isComplete)
      assertEquals(sink.toByteArray().toSeq, "abc".getBytes(ISO_8859_1).toSeq)
    } finally buffered.close()
  }

  test("closePreservesConnectionOwnership") {
    val sink = new RecordingOutputStream()
    val body = new FixedLengthOutputStream(sink, 2)
    body.write("ab".getBytes(ISO_8859_1))
    body.close()
    body.close()
    assert(body.isComplete)
    assertEquals(sink.flushes, 1)
    assertEquals(sink.closes, 0)
    intercept[IOException] { body.write('x') }
    intercept[IOException] { body.write(new Array[Byte](0)) }
    intercept[IOException] { body.flush() }
    val next = new FixedLengthOutputStream(sink, 2)
    next.write("cd".getBytes(ISO_8859_1))
    next.close()
    assertEquals(sink.toByteArray().toSeq, "abcd".getBytes(ISO_8859_1).toSeq)
    assertEquals(sink.flushes, 2)
    assertEquals(sink.closes, 0)

    val bytes = new ByteArrayOutputStream()
    val buffered = new BufferedOutputStream(bytes, 16)
    try {
      val bufferedBody = new FixedLengthOutputStream(buffered, 2)
      bufferedBody.write("xy".getBytes(ISO_8859_1))
      assertEquals(bytes.size(), 0)
      bufferedBody.close()
      assertEquals(bytes.toByteArray().toSeq, "xy".getBytes(ISO_8859_1).toSeq)
      assert(bufferedBody.isComplete)
    } finally buffered.close()
  }

  test("emptyWritesDoNotTouchTheSink") {
    val sink = new ByteArrayOutputStream() {
      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
        throw new AssertionError("Empty write must not reach sink")
      }
    }
    val body = new FixedLengthOutputStream(sink, 0)
    body.write(new Array[Byte](0))
    body.write(new Array[Byte](4), 4, 0)
    assert(!(body.isComplete))
    body.close()
    assert(body.isComplete)
    assertEquals(sink.size(), 0)
    val nonempty = new FixedLengthOutputStream(sink, 1)
    nonempty.write(new Array[Byte](0))
    nonempty.write('x')
    nonempty.close()
    assert(nonempty.isComplete)
    assertEquals(sink.size(), 1)
  }

  test("rejectsInvalidArgumentsWithoutEmittingBytes") {
    intercept[NullPointerException] { new FixedLengthOutputStream(null, 1) }
    val sink = new RecordingOutputStream()
    intercept[IllegalArgumentException] {
      new FixedLengthOutputStream(sink, -1)
    }
    val body = new FixedLengthOutputStream(sink, 3)
    intercept[NullPointerException] { body.write(null) }
    intercept[NullPointerException] { body.write(null, 0, 0) }
    val bytes = "abc".getBytes(ISO_8859_1)
    List((-1, 1), (0, -1), (4, 0), (2, 2), (1, Int.MaxValue), (Int.MaxValue, 1))
      .foreach { case (offset, length) =>
        intercept[IndexOutOfBoundsException] {
          body.write(bytes, offset, length)
        }
      }
    assertEquals(sink.size(), 0)
    assertEquals(sink.flushes, 0)
    body.write(bytes)
    body.close()
    assert(body.isComplete)
    assertEquals(sink.toByteArray().toSeq, bytes.toSeq)
  }

  test("supportsLongBodyLengths") {
    val sink = new ByteArrayOutputStream()
    val body = new FixedLengthOutputStream(sink, Long.MaxValue)
    body.write('a')
    body.write("bcd".getBytes(ISO_8859_1))
    body.flush()
    assertEquals(sink.toByteArray().toSeq, "abcd".getBytes(ISO_8859_1).toSeq)
    assert(!(body.isComplete))
    intercept[IOException] { body.close() }
    assert(!(body.isComplete))
  }

  test("writeFailuresAreTerminal") {
    for {
      mode <- List("single", "bulk")
      partial <- List(false, true)
    } {
      val error = new IOException("sink write failed")
      var broken = false
      var calls = 0
      val sink = new ByteArrayOutputStream() {
        override def write(value: Int): Unit = {
          calls += 1
          if (broken) {
            if (partial) super.write(value)
            throw error
          }
          super.write(value)
        }
        override def write(
            bytes: Array[Byte],
            offset: Int,
            length: Int
        ): Unit = {
          calls += 1
          if (broken) {
            if (partial) super.write(bytes, offset, 1)
            throw error
          }
          super.write(bytes, offset, length)
        }
        override def flush(): Unit = calls += 1
        override def close(): Unit = {
          throw new AssertionError("Sink is caller-owned")
        }
      }
      val body = new FixedLengthOutputStream(sink, 4)
      body.write('a')
      broken = true
      assert(intercept[IOException] {
        {
          if (mode == "single") body.write('b')
          else body.write("bc".getBytes(ISO_8859_1))
        }
      } eq error)
      val expected = if (partial) "ab" else "a"
      assertEquals(
        sink.toByteArray().toSeq,
        expected.getBytes(ISO_8859_1).toSeq
      )
      val callsAtFailure = calls
      broken = false
      assert(intercept[IOException] { body.write('z') } eq error)
      assert(intercept[IOException] { body.write(Array[Byte](1)) } eq error)
      assert(intercept[IOException] { body.flush() } eq error)
      assert(intercept[IOException] { body.close() } eq error)
      body.close()
      assert(!(body.isComplete))
      assertEquals(calls, callsAtFailure)
      assertEquals(
        sink.toByteArray().toSeq,
        expected.getBytes(ISO_8859_1).toSeq
      )
    }
  }

  test("flushFailuresAreTerminal") {
    for {
      mode <- List("flush", "close")
      length <- List(0, 1)
    } {
      val error = new IOException("sink flush failed")
      var broken = true
      var flushes = 0
      val sink = new ByteArrayOutputStream() {
        override def flush(): Unit = {
          flushes += 1
          if (broken) throw error
        }
        override def close(): Unit = {
          throw new AssertionError("Sink is caller-owned")
        }
      }
      val body = new FixedLengthOutputStream(sink, length)
      if (length == 1) body.write('a')
      assert(intercept[IOException] {
        {
          if (mode == "flush") body.flush() else body.close()
        }
      } eq error)
      broken = false
      assert(!(body.isComplete))
      assert(intercept[IOException] { body.flush() } eq error)
      assert(intercept[IOException] { body.write('x') } eq error)
      if (mode == "flush") assert(intercept[IOException] {
        body.close()
      } eq error)
      body.close()
      assertEquals(flushes, 1)
      val expected = if (length == 1) Array[Byte](97) else new Array[Byte](0)
      assertEquals(sink.toByteArray().toSeq, expected.toSeq)
      assert(!(body.isComplete))
    }
  }

  private class RecordingOutputStream extends ByteArrayOutputStream {
    var flushes = 0
    var closes = 0

    override def flush(): Unit = flushes += 1

    override def close(): Unit = closes += 1

  }

}
