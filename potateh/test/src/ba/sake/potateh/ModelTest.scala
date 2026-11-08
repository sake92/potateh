package ba.sake.potateh

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class ModelTest extends munit.FunSuite {
  test("limits report individual invalid fields in updates") {
    val limits = HttpLimits.Default
    val cases = List[(name: String, minimum: Int, update: Int => HttpLimits)](
      ("requestLineBytes", 1, limits.withRequestLineBytes),
      ("headerLineBytes", 1, limits.withHeaderLineBytes),
      ("headerSectionBytes", 2, limits.withHeaderSectionBytes),
      ("headerCount", 0, limits.withHeaderCount),
      ("chunkLineBytes", 1, limits.withChunkLineBytes),
      ("chunkExtensionBytes", 0, limits.withChunkExtensionBytes),
      ("trailerLineBytes", 1, limits.withTrailerLineBytes),
      ("trailerSectionBytes", 2, limits.withTrailerSectionBytes),
      ("trailerCount", 0, limits.withTrailerCount)
    )
    cases.foreach { case (name, minimum, update) =>
      val invalid = minimum - 1
      val expected = s"requirement failed: $name must be at least $minimum (was $invalid)"
      interceptMessage[IllegalArgumentException](expected)(update(invalid))
      update(minimum)
    }
    val expected = "requirement failed: bodyBytes must be nonnegative when defined (was Some(-1))"
    interceptMessage[IllegalArgumentException](expected)(limits.withBodyBytes(Some(-1)))
    assertEquals(HttpLimits.Default.withBodyBytes(Some(0)).bodyBytes, Some(0L))
    assertEquals(limits.withBodyBytes(None).bodyBytes, None)
  }
  test("response status is validated on creation and updates") {
    for (status <- List(Int.MinValue, 99, 600, Int.MaxValue)) {
      val expected = s"requirement failed: status must be between 100 and 599 (was $status)"
      interceptMessage[IllegalArgumentException](expected)(Response(status = status))
      interceptMessage[IllegalArgumentException](expected)(Response().withStatus(status))
    }
    for (status <- List(100, 199, 200, 471, 599)) {
      assertEquals(Response(status = status).status, status)
      assertEquals(Response().withStatus(status).status, status)
    }
  }
  test("timeouts report individual invalid fields") {
    val timeouts = HttpTimeouts.Default
    val cases = List[(name: String, update: FiniteDuration => HttpTimeouts)](
      ("idle", timeouts.withIdle),
      ("head", timeouts.withHead),
      ("bodyRead", timeouts.withBodyRead),
      ("write", timeouts.withWrite)
    )
    cases.foreach { case (name, update) =>
      for (invalid <- List(-1.nanosecond, Duration.Zero, Int.MaxValue.toLong.millis + 1.nanosecond)) {
        val expected = s"requirement failed: $name must be positive and at most 2147483647 milliseconds (was $invalid)"
        interceptMessage[IllegalArgumentException](expected)(update(invalid))
      }
      update(1.nanosecond)
      update(Int.MaxValue.toLong.millis)
    }
    for (invalid <- List(-1.nanosecond, 1.day + 1.nanosecond)) {
      val expected = s"requirement failed: shutdownGrace must be between zero and one day (was $invalid)"
      interceptMessage[IllegalArgumentException](expected)(timeouts.withShutdownGrace(invalid))
    }
    assertEquals(HttpTimeouts.Default.withShutdownGrace(Duration.Zero).shutdownGrace, Duration.Zero)
    assertEquals(timeouts.withShutdownGrace(1.day).shutdownGrace, 1.day)
  }
  test("immutable ordered duplicate headers and case insensitive operations") {
    val initial = Headers("X-One" -> "a", "x-one" -> "b", "Other" -> "c")
    assertEquals(initial.all("X-ONE"), Vector("a", "b"))
    assertEquals(initial.first("x-one"), Some("a"))
    assertEquals(
      initial.set("x-ONE", "d").fields,
      Vector("Other" -> "c", "x-ONE" -> "d")
    )
    assertEquals(initial.remove("X-ONE").fields, Vector("Other" -> "c"))
    assertEquals(initial.fields.size, 3)
    intercept[IllegalArgumentException](Headers("x\r\nBad" -> "y"))
    intercept[IllegalArgumentException](Headers("x" -> "y\r\nBad: z"))
  }
  test("configuration rejects invalid bounds before binding") {
    intercept[IllegalArgumentException](ServerConfig.Default.withMaxConnections(0))
    for (backlog <- List(0, -1)) intercept[IllegalArgumentException](ServerConfig.Default.withBacklog(backlog))
    intercept[IllegalArgumentException](HttpLimits.Default.withBodyBytes(Some(-1)))
    intercept[IllegalArgumentException](HttpTimeouts.Default.withHead(Duration.Zero))
    intercept[IllegalArgumentException](
      HttpTimeouts.Default.withHead(Int.MaxValue.toLong.millis + 1.nanosecond)
    )
    intercept[IllegalArgumentException](ResponseBody.Writer(Some(-1))(_ => ()))
  }
  test("configuration updates preserve unrelated settings and validate new bounds") {
    val reporter: Throwable => Unit = _ => ()
    val original = ServerConfig.Default
      .withBacklog(257)
      .withOnError(reporter)
      .withMaxConnections(17)
      .withLimits(HttpLimits.Default.withHeaderCount(23))
      .withTimeouts(HttpTimeouts.Default.withHead(7.seconds))
    val changed = original
      .withMaxConnections(19)
      .withLimits(original.limits.withBodyBytes(None))
      .withTimeouts(original.timeouts.withWrite(11.seconds))
    assertEquals(changed.maxConnections, 19)
    assertEquals(changed.backlog, 257)
    assert(changed.onError eq reporter)
    assertEquals(ServerConfig.Default.backlog, 128)
    assertEquals(changed.bindAddress, original.bindAddress)
    assertEquals(changed.limits.headerCount, 23)
    assertEquals(changed.limits.bodyBytes, None)
    assertEquals(changed.timeouts.head, 7.seconds)
    assertEquals(changed.timeouts.write, 11.seconds)
    assertEquals(ServerConfig.Default.maxConnections, 128)
    assertEquals(HttpLimits.Default.headerCount, 100)
    assertEquals(HttpTimeouts.Default.head, 10.seconds)
    assertEquals(original.maxConnections, 17)
    assertEquals(original.limits.bodyBytes, Some(8L * 1024 * 1024))
    assertEquals(original.timeouts.write, 30.seconds)
    intercept[IllegalArgumentException](original.withMaxConnections(0))
    intercept[IllegalArgumentException](original.limits.withBodyBytes(Some(-1)))
    intercept[IllegalArgumentException](original.timeouts.withHead(Duration.Zero))
  }
  test("response updates retain the writer and leave the original unchanged") {
    val output = new java.io.ByteArrayOutputStream()
    val writer = ResponseBody.Writer()(out => out.write(42)).withLength(Some(1))
    val original = Response(status = 201, body = writer)
    val changed = original.withStatus(202).withHeaders(Headers("X-Test" -> "yes"))
    assertEquals(changed.status, 202)
    assertEquals(changed.headers.first("X-Test"), Some("yes"))
    val retainedWriter = changed.body.asInstanceOf[ResponseBody.Writer]
    assertEquals(retainedWriter.length, Some(1L))
    retainedWriter.write(output)
    assertEquals(output.toByteArray.toVector, Vector(42.toByte))
    assertEquals(original.status, 201)
    assertEquals(original.headers.fields, Vector.empty)
    intercept[IllegalArgumentException](writer.withLength(Some(-1)))
  }
  test("headers retain ordered case sensitive value equality") {
    val headers = Headers("X-Test" -> "one", "X-Test" -> "two")
    val same = Headers.from(headers.fields)
    assertEquals(headers, same)
    assertEquals(headers.hashCode(), same.hashCode())
    assertNotEquals(headers, Headers("X-Test" -> "two", "X-Test" -> "one"))
    assertNotEquals(headers, Headers("x-test" -> "one", "X-Test" -> "two"))
  }
  test("platform threads publish and join on both targets") {
    val value = new AtomicInteger(0)
    val thread = new Thread(() => {
      value.set(42)
      ()
    })
    thread.start()
    thread.join(2000)
    assert(!thread.isAlive)
    assertEquals(value.get(), 42)
  }
}
