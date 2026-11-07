package consumer

import scala.compiletime.testing.typeChecks

class PublicApiTest extends munit.FunSuite {
  test("public models do not expose constructor or case class evolution contracts") {
    assert(!typeChecks("""
      import ba.sake.potateh.*
      new Response(200, Headers.empty, ResponseBody.Empty)
    """))
    assert(!typeChecks("""
      import ba.sake.potateh.*
      new ServerConfig(new java.net.InetSocketAddress(8080), 1, HttpLimits(), HttpTimeouts())
    """))
    assert(!typeChecks("ba.sake.potateh.Response().copy()"))
    assert(!typeChecks("ba.sake.potateh.ServerConfig().copy()"))
    assert(!typeChecks("ba.sake.potateh.HttpLimits().copy()"))
    assert(!typeChecks("ba.sake.potateh.HttpTimeouts().copy()"))
    assert(!typeChecks("ba.sake.potateh.Headers.empty.copy()"))
    assert(!typeChecks("ba.sake.potateh.ResponseBody.Writer()(_ => ()).copy()"))
    assert(!typeChecks("def copied(r: ba.sake.potateh.Request) = r.copy()"))
  }
}
