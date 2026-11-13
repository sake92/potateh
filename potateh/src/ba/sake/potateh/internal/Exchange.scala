package ba.sake.potateh.internal

import java.io.{IOException, InputStream, OutputStream}
import java.net.{Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets.US_ASCII

import scala.util.boundary
import scala.util.control.NonFatal

import boundary.break
import ba.sake.potateh.*

/** All mutable exchange state belongs to its connection worker. */
private[potateh] final class Exchange(
    socket: Socket,
    input: InputStream,
    output: OutputStream,
    head: RequestHeadParser.RequestHead,
    config: ServerConfig,
    handle: Request => Response
) {
  private var activeRequest: Option[Request] = None
  private var committed = false
  private var failure: Option[IOException] = None
  private var live = true
  private var continued = false
  private var expectContinue = false
  private val headers = Headers.from(head.headers)
  private val limits = config.limits
  private val framed: InputStream = head.framing match {
    case RequestHeadParser.NoBody              => new FixedLengthInputStream(input, 0)
    case RequestHeadParser.FixedLength(length) =>
      new FixedLengthInputStream(input, length)
    case RequestHeadParser.Chunked =>
      new ChunkedInputStream(
        input,
        ChunkedInputStream.Limits(
          limits.maxChunkLineBytes,
          limits.maxChunkExtensionBytes,
          limits.maxTrailerLineBytes,
          limits.maxTrailerSectionBytes,
          limits.maxTrailerCount
        ),
        limits.maxBodyBytes
      )
  }

  private def complete: Boolean = framed match {
    case body: FixedLengthInputStream => body.isComplete
    case body: ChunkedInputStream     => body.isComplete
    case _                            => false
  }

  private val body = new RequestBody {
    private var closed = false
    override def trailers: Option[Headers] = {
      checkLifetime()
      framed match {
        case chunks: ChunkedInputStream => chunks.trailers
        case _                          => Option.when(complete)(Headers.empty)
      }
    }
    override def read(): Int = consume(framed.read())
    override def read(bytes: Array[Byte], offset: Int, length: Int): Int = {
      if (offset < 0 || length < 0 || offset > bytes.length || length > bytes.length - offset) {
        throw new IndexOutOfBoundsException()
      }
      if (length == 0) {
        check()
        0
      } else consume(framed.read(bytes, offset, length))
    }
    override def skip(count: Long): Long = if (count <= 0) {
      check()
      0L
    } else consume(framed.skip(count))
    override def available(): Int = {
      check()
      guard(framed.available())
    }
    override def close(): Unit = {
      closed = true
      framed.close()
    }
    private def checkLifetime(): Unit = {
      if (!live) throw new IOException("Request body escaped its exchange")
    }
    private def check(): Unit = {
      checkLifetime()
      failure.foreach(throw _)
      if (closed) throw new IOException("Request body is closed")
    }
    private def consume[A](operation: => A): A = {
      check()
      guard {
        if (head.version == "HTTP/1.1" && !complete && !continued && expectContinue && !committed) {
          output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(US_ASCII))
          output.flush()
          continued = true
        }
        socket.setSoTimeout(
          math.max(1L, config.timeouts.bodyReadTimeout.toMillis).toInt
        )
        operation
      }
    }
    private def guard[A](operation: => A): A = {
      try operation
      catch {
        case error: IOException =>
          failure = Some(error)
          val code = error match {
            case _: ChunkedInputStream.BodyTooLargeException => 413
            case _: SocketTimeoutException                   => 408
            case _                                           => 400
          }
          try reject(code)
          catch { case NonFatal(_) => () }
          closeSocket()
          throw error
      }
    }
  }

  def run(): Boolean = {
    try process()
    catch {
      case NonFatal(error) =>
        try config.onError(error)
        catch { case NonFatal(_) => () }
        try reject(500)
        catch { case NonFatal(_) => () }
        false
    } finally {
      live = false
      try body.close()
      finally activeRequest.foreach(_.completeResponse(config.onError))
    }
  }

  private def process(): Boolean = boundary {
    head.framing match {
      case RequestHeadParser.FixedLength(size) if limits.maxBodyBytes.exists(size > _) =>
        reject(413)
        break(false)
      case _ => ()
    }
    if (head.method == "CONNECT") {
      reject(501)
      break(false)
    }
    val expectations = HttpSyntax.listElements(headers.all("Expect")).getOrElse {
      reject(417)
      break(false)
    }
    if (expectations.exists(!_.equalsIgnoreCase("100-continue"))) {
      reject(417)
      break(false)
    }
    expectContinue = expectations.nonEmpty
    val options = HttpSyntax.listElements(headers.all("Connection")).getOrElse {
      reject(400)
      break(false)
    }
    if (!options.forall(_.forall(HttpSyntax.isToken))) {
      reject(400)
      break(false)
    }
    val persistent = !options.exists(_.equalsIgnoreCase("close")) &&
      (head.version == "HTTP/1.1" || options.exists(
        _.equalsIgnoreCase("keep-alive")
      ))
    val version = {
      if (head.version == "HTTP/1.1") HttpVersion.Http11
      else HttpVersion.Http10
    }
    val request = Request(
      head.method,
      head.target,
      version,
      headers,
      body,
      socket.getLocalSocketAddress().asInstanceOf[java.net.InetSocketAddress],
      socket.getRemoteSocketAddress().asInstanceOf[java.net.InetSocketAddress]
    )
    activeRequest = Some(request)
    val response = handle(request)
    failure.foreach(throw _)
    // A writer may consume the body, but headers must commit to closure before it runs.
    val prepared = ResponseHeadWriter.prepare(
      head.method,
      head.version,
      response,
      !persistent || !complete
    )
    committed = true
    output.write(prepared.bytes)
    ResponseHeadWriter.writeBody(output, response.body, prepared.framing)
    failure.foreach(throw _)
    output.flush()
    persistent && !prepared.closes && complete
  }

  private def reject(code: Int): Unit = {
    if (!committed) {
      val prepared = {
        ResponseHeadWriter.prepare("GET", head.version, Response(code), true)
      }
      committed = true
      output.write(prepared.bytes)
      output.flush()
    }
  }

  private def closeSocket(): Unit = try socket.close()
  catch { case _: IOException => () }

}
