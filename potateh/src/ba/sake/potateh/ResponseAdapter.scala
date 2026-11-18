package ba.sake.potateh

/** Converts HTTP-style response headers to the server's body-length and connection model. */
object ResponseAdapter {
  private val managed = Vector("Content-Length", "Transfer-Encoding", "Connection", "Keep-Alive", "Upgrade", "Trailer")

  /** The optional body distinguishes an absent body from an explicitly sized representation for HEAD and 304. */
  def apply(status: Int, headers: Headers, body: Option[ResponseBody], requestMethod: String): Response = {
    val connectionFields = headers.all("Connection").flatMap(_.split(',')).map(_.trim)
    require(!connectionFields.exists(_.equalsIgnoreCase("close")), "Potateh does not support response-requested close")
    require(status == 426 || headers.all("Upgrade").isEmpty, "Potateh does not support protocol upgrades")
    require(headers.all("Trailer").isEmpty, "Potateh does not support response trailers")
    val lengths = headers.all("Content-Length")
    val length = lengths.headOption.map { value =>
      require(
        lengths.size == 1 && value.nonEmpty && value.forall(c => c >= '0' && c <= '9'),
        "Invalid response Content-Length"
      )
      value.toLongOption.getOrElse(throw new IllegalArgumentException("Response Content-Length overflow"))
    }
    val transferEncoding = headers.all("Transfer-Encoding")
    require(length.isEmpty || transferEncoding.isEmpty, "Conflicting response framing")
    val knownLength = body.flatMap(internal.ResponseHeadWriter.length)
    val suppressed = requestMethod == "HEAD" || status == 204 || status == 205 || status == 304
    require(
      suppressed || length.isEmpty || knownLength.isEmpty || length == knownLength,
      "Response Content-Length does not match body length"
    )
    val bodyLength = if (length.nonEmpty) length else if (transferEncoding.nonEmpty) None else knownLength
    val filtered = headers.fields.filterNot { field =>
      val name = field.name
      val upgrade426 = status == 426 && name.equalsIgnoreCase("Upgrade")
      !upgrade426 && (managed.exists(name.equalsIgnoreCase) || connectionFields.exists(name.equalsIgnoreCase))
    }
    val outputBody = body match {
      case None if length.nonEmpty && (requestMethod == "HEAD" || status == 304) => ResponseBody.Writer(length)(_ => ())
      case None                                                                  =>
        require(length.forall(_ == 0), "Nonzero Content-Length without a body")
        ResponseBody.Empty
      case Some(writer: ResponseBody.Writer)                     => writer.withLength(bodyLength)
      case Some(bytes: ResponseBody.Bytes) if bodyLength.isEmpty =>
        ResponseBody.Writer()(_.write(bytes.value))
      case Some(other) => other
    }
    Response(status, Headers.from(filtered), outputBody)
  }
}
