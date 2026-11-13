package ba.sake.potateh

/** Immutable fields in wire order. Repeated fields remain separate. */
final class Headers private (val fields: Vector[(name: String, value: String)]) {
  override def equals(other: Any): Boolean = other match {
    case that: Headers => fields == that.fields
    case _             => false
  }

  override def hashCode(): Int = fields.hashCode()

  override def toString: String = s"Headers($fields)"

  def first(name: String): Option[String] = fields.collectFirst {
    case (key, value) if key.equalsIgnoreCase(name) => value
  }

  def all(name: String): Vector[String] = fields.collect {
    case (key, value) if key.equalsIgnoreCase(name) => value
  }

  def add(name: String, value: String): Headers = {
    Headers.validate(name, value)
    new Headers(fields.appended((name = name, value = value)))
  }

  def set(name: String, value: String): Headers = remove(name).add(name, value)

  def remove(name: String): Headers = {
    new Headers(fields.filterNot(_.name.equalsIgnoreCase(name)))
  }

}

object Headers {
  val empty: Headers = new Headers(Vector.empty)

  def apply(fields: (name: String, value: String)*): Headers = from(fields.toVector)

  def from(fields: Vector[(name: String, value: String)]): Headers = {
    fields.foreach { case (name, value) => validate(name, value) }
    new Headers(fields)
  }

  private def validate(name: String, value: String): Unit = {
    require(internal.HttpSyntax.isTokenName(name), "Invalid header name")
    require(internal.HttpSyntax.isFieldValue(value), "Invalid header value")
  }

}
