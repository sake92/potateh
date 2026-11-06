package ba.sake.potateh.internal

import java.io.{BufferedInputStream, ByteArrayInputStream, EOFException, IOException, InputStream}
import java.nio.charset.StandardCharsets.ISO_8859_1


class FixedLengthInputStreamTest extends munit.FunSuite {
  test("readsExactlyTheDeclaredBody") {
    val source = stream("\u00ffabcGET /next HTTP/1.0\r\n\r\n")
    val body = new FixedLengthInputStream(source, 4)
    assert(!(body.isComplete))
    assertEquals(body.read(), 255)
    val buffer = Array.fill[Byte](8)(42)
    assertEquals(body.read(buffer, 2, 6), 3)
    assertEquals(buffer .toSeq, Array[Byte](42, 42, 97, 98, 99, 42, 42, 42) .toSeq)
    assert(body.isComplete)
    assertEquals(body.read(), -1)
    assertEquals(body.read(buffer), -1)
    assertEquals(body.read(buffer, 8, 0), 0)
    assertEquals(RequestHeadParser.read(source).get.target, "/next")
  }

  test("handlesFragmentedInput") {
    val payload = "abcdefghijklmnopqrstuvwxyz"
    val source = new ByteArrayInputStream((payload + "GET / HTTP/1.0\r\n\r\n").getBytes(ISO_8859_1)) {
      override def read(b: Array[Byte], off: Int, len: Int): Int =
        super.read(b, off, math.min(len, 2))
    }
    val input = new BufferedInputStream(source, 5)
    try {
      val body = new FixedLengthInputStream(input, payload.length)
      val buffer = new Array[Byte](7)
      val actual = new java.lang.StringBuilder()
      var count = body.read(buffer)
      while (count != -1) {
        assert(count > 0)
        actual.append(new String(buffer, 0, count, ISO_8859_1))
        count = body.read(buffer)
      }
      assertEquals(actual.toString(), payload)
      assert(body.isComplete)
      assertEquals(RequestHeadParser.read(input).get.target, "/")
    } finally input.close()
  }

  test("emptyBodiesAndZeroLengthReadsDoNotConsumeSource") {
    val source = new InputStream {
      override def read(): Int = throw new AssertionError("Source must not be read")
      override def available(): Int = throw new AssertionError("Source must not be probed")
    }
    val empty = new FixedLengthInputStream(source, 0)
    assert(empty.isComplete)
    assertEquals(empty.read(), -1)
    assertEquals(empty.read(new Array[Byte](1)), -1)
    assertEquals(empty.read(new Array[Byte](0)), 0)
    assertEquals(empty.available(), 0)
    assertEquals(empty.skip(Long.MaxValue), 0L)
    val nonempty = new FixedLengthInputStream(source, 1)
    assertEquals(nonempty.read(new Array[Byte](0)), 0)
    assertEquals(nonempty.skip(0), 0L)
    assertEquals(nonempty.skip(-1), 0L)
    assert(!(nonempty.isComplete))
  }

  test("rejectsInvalidArgumentsWithoutConsumingInput") {
    intercept[NullPointerException] { new FixedLengthInputStream(null, 1) }
    val source = stream("abcNEXT")
    intercept[IllegalArgumentException] { new FixedLengthInputStream(source, -1) }
    val body = new FixedLengthInputStream(source, 3)
    val buffer = new Array[Byte](4)
    intercept[NullPointerException] { body.read(null) }
    intercept[NullPointerException] { body.read(null, 0, 0) }
    List((-1, 1), (0, -1), (5, 0), (3, 2), (1, Int.MaxValue), (Int.MaxValue, 1)).foreach {
      case (offset, length) =>
        intercept[IndexOutOfBoundsException] { body.read(buffer, offset, length) }
    }
    assertEquals(body.skip(3), 3L)
    assert(body.isComplete)
    assertEquals(source.read(), 'N'.toInt)
    intercept[IndexOutOfBoundsException] { body.read(buffer, 0, -1) }
  }

  test("supportsLongBodyLengths") {
    val body = new FixedLengthInputStream(stream("abc"), Long.MaxValue)
    assertEquals(body.read(), 'a'.toInt)
    val buffer = new Array[Byte](8)
    assertEquals(body.read(buffer), 2)
    assertEquals(buffer(0), 'b'.toByte)
    assertEquals(buffer(1), 'c'.toByte)
    assert(!(body.isComplete))
    intercept[EOFException] { body.read() }
  }

  test("skipIsBoundedAndHandlesShortReads") {
    val source = new ByteArrayInputStream(("a" * 5000 + "zNEXT").getBytes(ISO_8859_1)) {
      override def read(b: Array[Byte], off: Int, len: Int): Int =
        super.read(b, off, math.min(len, 3))
      override def skip(count: Long): Long = throw new AssertionError("Must verify skipped body bytes")
    }
    val body = new FixedLengthInputStream(source, 5001)
    assertEquals(body.skip(Long.MinValue), 0L)
    assertEquals(body.skip(5000), 5000L)
    assert(!(body.isComplete))
    assertEquals(body.read(), 'z'.toInt)
    assertEquals(body.skip(1), 0L)
    assert(body.isComplete)
    assertEquals(source.read(), 'N'.toInt)
    val shortSource = stream("abcNEXT")
    val shortBody = new FixedLengthInputStream(shortSource, 3)
    assertEquals(shortBody.skip(Long.MaxValue), 3L)
    assertEquals(shortSource.read(), 'N'.toInt)
  }

  test("availabilityIsBoundedByRemainingBody") {
    val body = new FixedLengthInputStream(stream("abcNEXT"), 3)
    assertEquals(body.available(), 3)
    assertEquals(body.read(), 'a'.toInt)
    assertEquals(body.available(), 2)
    assertEquals(body.skip(2), 2L)
    assertEquals(body.available(), 0)
    val source = new InputStream {
      override def read(): Int = 'x'.toInt
      override def available(): Int = 0
    }
    val unknown = new FixedLengthInputStream(source, 1)
    assertEquals(unknown.available(), 0)
    assert(!(unknown.isComplete))
    assertEquals(unknown.read(), 'x'.toInt)
    assert(unknown.isComplete)
  }

  test("truncationIsTerminal") {
    List("single", "bulk", "skip").foreach { operation =>
      val body = new FixedLengthInputStream(stream("ab"), 3)
      assertEquals(body.read(), 'a'.toInt)
      assertEquals(body.read(), 'b'.toInt)
      val error = intercept[EOFException] { {
        operation match {
          case "single" => body.read()
          case "bulk" => body.read(new Array[Byte](4))
          case "skip" => body.skip(1)
        }
        ()
      } }
      assert(!(body.isComplete))
      assert(intercept[IOException] { body.read() } eq error)
      assert(intercept[IOException] { body.available() } eq error)
      body.close()
      assert(!(body.isComplete))
    }
  }

  test("sourceFailuresAreTerminal") {
    List("single", "bulk", "skip", "available").foreach { operation =>
      val error = new IOException("source failure")
      var broken = false
      var calls = 0
      val source = new ByteArrayInputStream("abcNEXT".getBytes(ISO_8859_1)) {
        override def read(): Int = {
          calls += 1
          if (broken) throw error
          super.read()
        }
        override def read(b: Array[Byte], off: Int, len: Int): Int = {
          calls += 1
          if (broken) throw error
          super.read(b, off, len)
        }
        override def available(): Int = {
          calls += 1
          if (broken) throw error
          super.available()
        }
      }
      val body = new FixedLengthInputStream(source, 3)
      assertEquals(body.read(), 'a'.toInt)
      broken = true
      assert(intercept[IOException] { {
        operation match {
          case "single" => body.read()
          case "bulk" => body.read(new Array[Byte](4))
          case "skip" => body.skip(1)
          case "available" => body.available()
        }
        ()
      } } eq error)
      val callsAtFailure = calls
      broken = false
      assert(intercept[IOException] { body.read() } eq error)
      assert(intercept[IOException] { body.read(new Array[Byte](4)) } eq error)
      assert(intercept[IOException] { body.skip(1) } eq error)
      assert(intercept[IOException] { body.available() } eq error)
      assertEquals(calls, callsAtFailure)
      assert(!(body.isComplete))
      assertEquals(source.read(), 'b'.toInt)
    }
  }

  test("closePreservesConnectionOwnership") {
    var sourceClosed = false
    val source = new ByteArrayInputStream("abcNEXT".getBytes(ISO_8859_1)) {
      override def close(): Unit = sourceClosed = true
    }
    val body = new FixedLengthInputStream(source, 3)
    assertEquals(body.read(), 'a'.toInt)
    body.close()
    body.close()
    assert(!(body.isComplete))
    assert(!(sourceClosed))
    intercept[IOException] { body.read() }
    intercept[IOException] { body.read(new Array[Byte](4)) }
    intercept[IOException] { body.read(new Array[Byte](0)) }
    intercept[IOException] { body.skip(0) }
    intercept[IOException] { body.available() }
    assertEquals(source.read(), 'b'.toInt) // Closing did not drain unread bytes.
    val complete = new FixedLengthInputStream(source, 1)
    assertEquals(complete.read(), 'c'.toInt)
    complete.close()
    assert(complete.isComplete)
    assert(!(sourceClosed))
    assertEquals(source.read(), 'N'.toInt)
  }

  test("doesNotSupportMarkOrReset") {
    val body = new FixedLengthInputStream(stream("abcNEXT"), 3)
    assert(!(body.markSupported()))
    assertEquals(body.read(), 'a'.toInt)
    body.mark(10)
    assertEquals(body.read(), 'b'.toInt)
    intercept[IOException] { body.reset() }
    assertEquals(body.read(), 'c'.toInt)
    assert(body.isComplete)
  }

  private def stream(value: String): ByteArrayInputStream =
    new ByteArrayInputStream(value.getBytes(ISO_8859_1))
}
