package ba.sake.potateh

/** Immutable fields in wire order. Repeated fields remain separate. */
final case class Headers private (fields: Vector[(String, String)]) {
  def first(name: String): Option[String] = fields.collectFirst {
    case (key, value) if key.equalsIgnoreCase(name) => value
  }
  def all(name: String): Vector[String] = fields.collect {
    case (key, value) if key.equalsIgnoreCase(name) => value
  }
  def add(name: String, value: String): Headers = Headers.from(fields :+ (name -> value))
  def set(name: String, value: String): Headers = remove(name).add(name, value)
  def remove(name: String): Headers = Headers.from(fields.filterNot(_._1.equalsIgnoreCase(name)))
}
object Headers {
  val empty: Headers = new Headers(Vector.empty)
  def apply(fields: (String, String)*): Headers = from(fields.toVector)
  def from(fields: Vector[(String, String)]): Headers = {
    fields.foreach { case (name, value) =>
      require(name.nonEmpty && name.forall(internal.HttpSyntax.isToken), "Invalid header name")
      require(internal.HttpSyntax.isFieldValue(value), "Invalid header value")
    }
    new Headers(fields)
  }
}
