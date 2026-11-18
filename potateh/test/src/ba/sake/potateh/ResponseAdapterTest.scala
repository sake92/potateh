package ba.sake.potateh

class ResponseAdapterTest extends munit.FunSuite {
  test("translates length and strips connection-nominated headers") {
    val headers = {
      Headers("Content-Length" -> "2", "Connection" -> "keep-alive, X-Private", "X-Private" -> "x", "X-Public" -> "y")
    }
    val response = ResponseAdapter(200, headers, Some(ResponseBody.Writer()(_ => ())), "GET")
    assertEquals(response.headers.fields, Vector("X-Public" -> "y"))
    assertEquals(response.body.asInstanceOf[ResponseBody.Writer].length, Some(2L))
  }

  test("rejects invalid and contradictory framing before writing") {
    val body = Some(ResponseBody.bytes(Array[Byte](1, 2)))
    val cases = List(
      Headers("Content-Length" -> "-1"),
      Headers("Content-Length" -> "3"),
      Headers("Content-Length" -> "2", "Transfer-Encoding" -> "chunked"),
      Headers("Connection" -> "close")
    )
    cases.foreach(headers => intercept[IllegalArgumentException](ResponseAdapter(200, headers, body, "GET")))
  }

  test("preserves HEAD and 304 metadata and 426 upgrade header") {
    for ((method, status) <- List("HEAD" -> 200, "GET" -> 304)) {
      val response = ResponseAdapter(status, Headers("Content-Length" -> "42"), None, method)
      assertEquals(response.body.asInstanceOf[ResponseBody.Writer].length, Some(42L))
    }
    val response = ResponseAdapter(426, Headers("Upgrade" -> "websocket"), None, "GET")
    assertEquals(response.headers.first("Upgrade"), Some("websocket"))
  }
}
