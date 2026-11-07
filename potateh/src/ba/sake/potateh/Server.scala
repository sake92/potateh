package ba.sake.potateh

import java.net.{InetSocketAddress, ServerSocket}
import java.util.concurrent.Executor

/** A running server. Stopping is terminal and uses the configured grace budget.
  */
final class Server private (private val engine: internal.BlockingServer) {
  def address: InetSocketAddress = engine.address
  def stop(): Unit = engine.stop()
}

object Server {
  def start(
      config: ServerConfig = ServerConfig.Default
  )(handle: Request => Response): Server = {
    startServer(config, None, handle)
  }

  /** The caller owns the executor. Each task serves an entire connection. Rejection must throw rather than silently
    * discard tasks.
    */
  def start(config: ServerConfig, executor: Executor)(
      handle: Request => Response
  ): Server = startServer(config, Some(executor), handle)

  private def startServer(
      config: ServerConfig,
      executor: Option[Executor],
      handle: Request => Response
  ): Server = {
    val socket = new ServerSocket()
    try {
      socket.setReuseAddress(true)
      socket.bind(config.bindAddress, 128)
      val engine = new internal.BlockingServer(socket, config, executor, handle)
      engine.start()
      new Server(engine)
    } catch {
      case error: Throwable =>
        try socket.close()
        catch { case _: java.io.IOException => () }
        throw error
    }
  }
}
