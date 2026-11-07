package ba.sake.potateh

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class ModelTest extends munit.FunSuite {
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
    intercept[IllegalArgumentException](ServerConfig(maxConnections = 0))
    intercept[IllegalArgumentException](HttpLimits(bodyBytes = Some(-1)))
    intercept[IllegalArgumentException](HttpTimeouts(head = Duration.Zero))
    intercept[IllegalArgumentException](
      HttpTimeouts(head = Int.MaxValue.toLong.millis + 1.nanosecond)
    )
    intercept[IllegalArgumentException](ResponseBody.Writer(Some(-1))(_ => ()))
  }
  test("platform threads publish and join on both targets") {
    val value = new AtomicInteger(0)
    val thread = new Thread(() => { value.set(42); () })
    thread.start()
    thread.join(2000)
    assert(!thread.isAlive)
    assertEquals(value.get(), 42)
  }
}
