package ba.sake.potateh

import java.io.{InputStream, OutputStream}
import java.net.InetSocketAddress

enum HttpVersion(val wire: String) {

  case Http10 extends HttpVersion("HTTP/1.0")

  case Http11 extends HttpVersion("HTTP/1.1")

}

final class Request private (
    val method: String,
    val target: String,
    val version: HttpVersion,
    val headers: Headers,
    val body: RequestBody,
    val localAddress: InetSocketAddress,
    val remoteAddress: InetSocketAddress
) {

  def withMethod(value: String): Request = {
    copy(method = value)
  }

  def withTarget(value: String): Request = {
    copy(target = value)
  }

  def withVersion(value: HttpVersion): Request = {
    copy(version = value)
  }

  def withHeaders(value: Headers): Request = {
    copy(headers = value)
  }

  def withBody(value: RequestBody): Request = {
    copy(body = value)
  }

  def withLocalAddress(value: InetSocketAddress): Request = {
    copy(localAddress = value)
  }

  def withRemoteAddress(value: InetSocketAddress): Request = {
    copy(remoteAddress = value)
  }

  private def copy(
      method: String = this.method,
      target: String = this.target,
      version: HttpVersion = this.version,
      headers: Headers = this.headers,
      body: RequestBody = this.body,
      localAddress: InetSocketAddress = this.localAddress,
      remoteAddress: InetSocketAddress = this.remoteAddress
  ): Request = {
    new Request(
      method,
      target,
      version,
      headers,
      body,
      localAddress,
      remoteAddress
    )
  }

}

object Request {

  def apply(
      method: String,
      target: String,
      version: HttpVersion,
      headers: Headers,
      body: RequestBody,
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress
  ): Request = {
    new Request(method, target, version, headers, body, localAddress, remoteAddress)
  }

}

final class Response private (
    val status: Int,
    val headers: Headers,
    val body: ResponseBody
) {
  require(status >= 100 && status < 600, s"status must be between 100 and 599 (was $status)")

  def withStatus(value: Int): Response = {
    copy(status = value)
  }

  def withHeaders(value: Headers): Response = {
    copy(headers = value)
  }

  def withBody(value: ResponseBody): Response = {
    copy(body = value)
  }

  private def copy(
      status: Int = this.status,
      headers: Headers = this.headers,
      body: ResponseBody = this.body
  ): Response = {
    new Response(
      status,
      headers,
      body
    )
  }

}

object Response {

  def apply(
      status: Int = 200,
      headers: Headers = Headers.empty,
      body: ResponseBody = ResponseBody.Empty
  ): Response = {
    new Response(status, headers, body)
  }

}

/** Available only during the callback and its response writer. Close does not drain.
  */
abstract class RequestBody private[potateh] () extends InputStream {

  /** None until the framed body is complete; Some(empty) for bodies without trailers.
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

  final class Writer private (
      val length: Option[Long],
      val write: OutputStream => Unit
  ) extends ResponseBody {
    require(length.forall(_ >= 0), "Negative response length")

    def withLength(value: Option[Long]): Writer = new Writer(value, write)

    def withWrite(value: OutputStream => Unit): Writer = new Writer(length, value)

  }

  object Writer {

    def apply(length: Option[Long] = None)(write: OutputStream => Unit): Writer = {
      new Writer(length, write)
    }

  }

}
