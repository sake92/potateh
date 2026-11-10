package ba.sake.potateh.internal

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/** Owned by the connection worker and shared by copies of its request. */
private[potateh] final class ResponseCompletion {
  private val callbacks = ListBuffer.empty[() => Unit]
  private var finished = false

  def add(callback: () => Unit): Unit = {
    require(!finished, "Response processing has finished")
    callbacks += callback
  }

  def finish(report: Throwable => Unit): Unit = {
    if (!finished) {
      finished = true
      val pending = callbacks.toList
      callbacks.clear()
      pending.foreach { callback =>
        try callback()
        catch {
          case NonFatal(error) =>
            try report(error)
            catch { case NonFatal(_) => () }
        }
      }
    }
  }
}
