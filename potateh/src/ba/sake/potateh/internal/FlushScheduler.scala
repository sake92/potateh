package ba.sake.potateh.internal

import java.util.concurrent.{
  Executors,
  RejectedExecutionException,
  ScheduledFuture,
  ScheduledThreadPoolExecutor,
  ThreadFactory,
  TimeUnit
}

private[potateh] final class FlushScheduler {
  private def threads(name: String): ThreadFactory = task => {
    val thread = new Thread(task, name)
    thread.setDaemon(true)
    thread
  }

  private val clock = new ScheduledThreadPoolExecutor(1, threads("potateh-flush-clock"))
  clock.setRemoveOnCancelPolicy(true)
  private val writers = Executors.newCachedThreadPool(threads("potateh-flush-worker"))

  def schedule(task: Runnable): ScheduledFuture[?] = {
    val dispatch: Runnable = () => {
      try writers.execute(task)
      catch { case _: RejectedExecutionException => () }
    }
    clock.schedule(dispatch, 1L, TimeUnit.MILLISECONDS)
  }

  def stop(): Unit = {
    clock.shutdownNow()
    writers.shutdownNow()
  }
}
