package ba.sake.potateh

import java.net.InetSocketAddress

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
    val socket = config.serverSocketFactory()
    try {
      socket.setReuseAddress(true)
      socket.bind(config.bindAddress, config.backlog)
      val engine = new internal.BlockingServer(socket, config, config.executor, handle)
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
