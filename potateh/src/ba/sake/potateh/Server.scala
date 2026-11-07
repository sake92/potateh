package ba.sake.potateh

import java.net.{InetSocketAddress, ServerSocket}

/** A running server. Stopping is terminal and uses the configured grace budget.
  */
final class Server private (private val engine: internal.BlockingServer) {
  def address: InetSocketAddress = engine.address
  def stop(): Unit = engine.stop()
}
object Server {
  def start(
      config: ServerConfig = ServerConfig()
  )(handle: Request => Response): Server = {
    val socket = new ServerSocket()
    try {
      socket.setReuseAddress(true)
      socket.bind(config.bindAddress, config.maxConnections)
      val engine = new internal.BlockingServer(socket, config, handle)
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
