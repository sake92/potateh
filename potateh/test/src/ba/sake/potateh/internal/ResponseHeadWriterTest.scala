package ba.sake.potateh.internal

import ba.sake.potateh.*
import java.io.{ByteArrayOutputStream, IOException, OutputStream}
import java.nio.charset.StandardCharsets.ISO_8859_1
import ResponseHeadWriter.*
import ResponseHeadWriter.Framing.*

/** Adapted from upstream ResponseHeadWriterTest for engine-owned framing. */
class ResponseHeadWriterTest extends munit.FunSuite {
  private val date = "Thu, 01 Jan 1970 00:00:00 GMT"
  private def text(bytes: Array[Byte]): String = new String(bytes, ISO_8859_1)
  private def prepared(
      body: ResponseBody,
      method: String = "GET",
      version: String = "HTTP/1.1",
      status: Int = 200
  ) =
    prepare(
      method,
      version,
      Response(status, Headers("Date" -> date), body),
      false
    )

  test("serializes status and ordered repeated fields without flattening") {
    val response = Response(
      201,
      Headers("Date" -> date, "Set-Cookie" -> "a=1", "Set-Cookie" -> "b=2"),
      ResponseBody.bytes(Array[Byte](42))
    )
    assertEquals(
      text(prepare("GET", "HTTP/1.1", response, false).bytes),
      "HTTP/1.1 201 Created\r\nDate: " + date + "\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Length: 1\r\n\r\n"
    )
  }
  test("selects fixed chunked and HTTP10 close-delimited framing") {
    assertEquals(prepared(ResponseBody.Empty).framing, FixedLength(0))
    assertEquals(
      prepared(ResponseBody.bytes(Array[Byte](1, 2))).framing,
      FixedLength(2)
    )
    val writer = ResponseBody.Writer()(_ => ())
    assertEquals(prepared(writer).framing, Chunked)
    val old = prepared(writer, version = "HTTP/1.0")
    assertEquals(old.framing, CloseDelimited)
    assert(old.closes)
    assert(!text(old.bytes).contains("Transfer-Encoding"))
    assert(text(old.bytes).contains("Connection: close\r\n"))
  }
  test("HEAD and bodyless statuses suppress writers and override framing") {
    var called = false
    val writer = ResponseBody.Writer(Some(4))(_ => called = true)
    for (
      (method, status) <- List(
        ("HEAD", 200),
        ("GET", 204),
        ("GET", 205),
        ("GET", 304)
      )
    ) {
      val result = prepared(writer, method, status = status)
      assertEquals(result.framing, NoBody)
      val sink = new ByteArrayOutputStream()
      writeBody(sink, writer, result.framing)
      assertEquals(sink.size(), 0)
      assert(!called)
      if (status == 204) assert(!text(result.bytes).contains("Content-Length"))
      else if (status == 205)
        assert(text(result.bytes).contains("Content-Length: 0\r\n"))
      else assert(text(result.bytes).contains("Content-Length: 4\r\n"))
    }
  }
  test(
    "rejects conflicts, response splitting, informational responses and tunnels before emission"
  ) {
    for (
      name <- List(
        "Content-Length",
        "Transfer-Encoding",
        "Connection",
        "Upgrade",
        "Trailer",
        "Keep-Alive"
      )
    ) {
      intercept[IllegalArgumentException](
        prepare(
          "GET",
          "HTTP/1.1",
          Response(headers = Headers(name -> "x")),
          false
        )
      )
    }
    for (
      (name, value) <- List(
        "Bad\r\nInjected" -> "x",
        "Bad name" -> "x",
        "" -> "x",
        "X" -> "x\r\nInjected: y",
        "X" -> "x\u0000",
        "X" -> "x\u007f",
        "X" -> "x\u0100"
      )
    ) {
      intercept[IllegalArgumentException](Headers(name -> value))
    }
    for (status <- List(99, 100, 101, 199, 600))
      intercept[IllegalArgumentException](
        prepared(ResponseBody.Empty, status = status)
      )
    intercept[IllegalArgumentException](
      prepared(ResponseBody.Empty, method = "CONNECT")
    )
  }
  test(
    "enforces writer length underflow overflow and terminal stream failure"
  ) {
    for (actual <- List(1, 3)) {
      val sink = new ByteArrayOutputStream()
      val body =
        ResponseBody.Writer(Some(2))(out => out.write(new Array[Byte](actual)))
      intercept[IOException](writeBody(sink, body, FixedLength(2)))
    }
    var escaped: Option[OutputStream] = None
    val sink = new ByteArrayOutputStream()
    val body = ResponseBody.Writer(Some(2)) { out =>
      escaped = Some(out); out.write(Array[Byte](1, 2))
    }
    writeBody(sink, body, FixedLength(2))
    escaped.foreach(out => intercept[IOException](out.write(3)))
    assertEquals(sink.toByteArray.toSeq, Seq[Byte](1, 2))
  }
  test("adds Date and preserves supplied Date") {
    assert(
      text(prepare("GET", "HTTP/1.1", Response(), false).bytes).matches(
        "(?s).*Date: [A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT\r\n.*"
      )
    )
    assert(
      text(prepared(ResponseBody.Empty).bytes)
        .contains("Date: " + date + "\r\n")
    )
  }
  test("formats UTC dates across calendar boundaries") {
    val cases = List(
      -62135596800000L -> "Mon, 01 Jan 0001 00:00:00 GMT",
      -1L -> "Wed, 31 Dec 1969 23:59:59 GMT",
      0L -> "Thu, 01 Jan 1970 00:00:00 GMT",
      951827696000L -> "Tue, 29 Feb 2000 12:34:56 GMT",
      -2203891200000L -> "Thu, 01 Mar 1900 00:00:00 GMT",
      4107542400000L -> "Mon, 01 Mar 2100 00:00:00 GMT",
      253402300799999L -> "Fri, 31 Dec 9999 23:59:59 GMT"
    )
    cases.foreach { case (millis, expected) =>
      assertEquals(httpDate(millis), expected)
    }
    intercept[IllegalArgumentException](httpDate(Long.MinValue))
    intercept[IllegalArgumentException](httpDate(Long.MaxValue))
  }

  test(
    "failing chunked writers do not emit completion and escaped streams are invalid"
  ) {
    val sink = new ByteArrayOutputStream
    var escaped: Option[OutputStream] = None
    val body = ResponseBody.Writer() { out =>
      escaped = Some(out)
      out.write('x')
      throw new IllegalStateException("writer failed")
    }
    intercept[IllegalStateException](writeBody(sink, body, Chunked))
    assertEquals(text(sink.toByteArray), "1\r\nx\r\n")
    escaped.foreach(out => intercept[IOException](out.write('y')))
  }
}
