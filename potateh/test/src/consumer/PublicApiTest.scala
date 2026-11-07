package consumer

import scala.compiletime.testing.typeChecks

class PublicApiTest extends munit.FunSuite {
  test("headers expose named fields and accept ordinary tuple collections") {
    import ba.sake.potateh.Headers

    val fields = Vector("X-One" -> "a", "x-one" -> "b")
    val headers = Headers.from(fields)
    assertEquals(headers.fields.head.name, "X-One")
    assertEquals(headers.fields.head.value, "a")
    val named = Headers.from(Vector((name = "X-One", value = "a"), (name = "x-one", value = "b")))
    assertEquals(named, headers)
    assertEquals(Headers((name = "X-One", value = "a")).fields.head.value, "a")
  }

  test("public models do not expose constructor or case class evolution contracts") {
    assert(!typeChecks("""
      import ba.sake.potateh.*
      new Response(200, Headers.empty, ResponseBody.Empty)
    """))
    assert(!typeChecks("""
      import ba.sake.potateh.*
      new ServerConfig(new java.net.InetSocketAddress(8080), 1, HttpLimits.Default, HttpTimeouts.Default)
    """))
    assert(!typeChecks("ba.sake.potateh.Response().copy()"))
    assert(!typeChecks("ba.sake.potateh.ServerConfig.Default.copy()"))
    assert(!typeChecks("ba.sake.potateh.HttpLimits.Default.copy()"))
    assert(!typeChecks("ba.sake.potateh.HttpTimeouts.Default.copy()"))
    assert(!typeChecks("ba.sake.potateh.Headers.empty.copy()"))
    assert(!typeChecks("ba.sake.potateh.ResponseBody.Writer()(_ => ()).copy()"))
    assert(!typeChecks("def copied(r: ba.sake.potateh.Request) = r.copy()"))
  }
}
