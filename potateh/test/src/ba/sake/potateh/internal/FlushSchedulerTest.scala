package ba.sake.potateh.internal

import java.util.concurrent.{CountDownLatch, TimeUnit}

class FlushSchedulerTest extends munit.FunSuite {
  test("a blocked writer leaves the flush clock and other writers free") {
    val scheduler = new FlushScheduler
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val other = new CountDownLatch(1)
    try {
      scheduler.schedule(() => {
        entered.countDown()
        release.await()
      })
      assert(entered.await(3, TimeUnit.SECONDS))
      scheduler.schedule(() => other.countDown())
      assert(other.await(3, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      scheduler.stop()
    }
  }
}
