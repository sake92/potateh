package ba.sake.potateh

import ba.sake.potateh.internal.HttpSyntax
import org.openjdk.jmh.annotations.*

@State(Scope.Thread)
class HeaderBenchmark {
  @Param(Array("missing", "single", "repeated"))
  var shape: String = ""
  var headers: Headers = Headers.empty
  var values: Vector[String] = Vector.empty

  @Setup def prepare(): Unit = {
    val base = Headers("Host" -> "localhost", "User-Agent" -> "benchmark", "Accept" -> "*/*")
    headers = shape match {
      case "missing" => base
      case "single"  => base.add("Connection", "keep-alive")
      case _         => base.add("Connection", "keep-alive, upgrade").add("cOnNeCtIoN", "other")
    }
    values = headers.all("connection")
  }

  @Benchmark def allValues(): Vector[String] = headers.all("connection")

  @Benchmark def parseList(): Option[Vector[String]] = HttpSyntax.listElements(values)
}
