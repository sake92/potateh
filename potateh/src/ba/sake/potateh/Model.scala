package ba.sake.potateh

import java.io.{InputStream, OutputStream}
import java.net.InetSocketAddress

enum HttpVersion(val wire: String) {
  case Http10 extends HttpVersion("HTTP/1.0")
  case Http11 extends HttpVersion("HTTP/1.1")
}
final case class Request(
    method: String,
    target: String,
    version: HttpVersion,
    headers: Headers,
    body: RequestBody,
    localAddress: InetSocketAddress,
    remoteAddress: InetSocketAddress
)
final case class Response(
    status: Int = 200,
    headers: Headers = Headers.empty,
    body: ResponseBody = ResponseBody.Empty
)

/** Available only during the callback and its response writer. Close does not
  * drain.
  */
abstract class RequestBody private[potateh] () extends InputStream {

  /** None until the framed body is complete; Some(empty) for bodies without
    * trailers.
    */
  def trailers: Option[Headers]
}
sealed trait ResponseBody
object ResponseBody {
  case object Empty extends ResponseBody
  final class Bytes private[ResponseBody] (
      private[potateh] val value: Array[Byte]
  ) extends ResponseBody
  def bytes(value: Array[Byte]): ResponseBody = new Bytes(value.clone())
  final case class Writer(length: Option[Long] = None)(
      val write: OutputStream => Unit
  ) extends ResponseBody {
    require(length.forall(_ >= 0), "Negative response length")
  }
}
