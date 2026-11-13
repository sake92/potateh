package ba.sake.potateh

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets.US_ASCII

import ba.sake.potateh.internal.RequestHeadParser

object RequestHeadBenchmark {
  def main(args: Array[String]): Unit = {
    val heads = Map(
      "head_origin" -> Array(
        "GET /baseline11?a=13&b=42 HTTP/1.1\r\nHost: localhost:8080\r\n\r\n",
        "GET /baseline11?a=24&b=13 HTTP/1.1\r\nHost: localhost:8081\r\n\r\n"
      ),
      "head_escaped" -> Array(
        "GET /hello%20world?q=caf%C3%A9 HTTP/1.1\r\nHost: example.com\r\n\r\n",
        "GET /a%2Fb?q=x%2By HTTP/1.1\r\nHost: example.org\r\n\r\n"
      ),
      "head_absolute" -> Array(
        "GET http://example.com/a?b=1 HTTP/1.1\r\nHost: example.com\r\n\r\n",
        "GET https://example.org/b?c=2 HTTP/1.1\r\nHost: example.org\r\n\r\n"
      ),
      "head_connect" -> Array(
        "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n",
        "CONNECT example.org:443 HTTP/1.1\r\nHost: example.org:443\r\n\r\n"
      )
    ).view.mapValues(_.map(_.getBytes(US_ASCII))).toMap
    val limits = RequestHeadParser.Limits()
    val targets = Array("/baseline11?a=13&b=42", "/baseline11?a=24&b=13")
    val hosts = Array("localhost:8080", "localhost:8081")
    val headCases = Array("head_origin", "head_escaped", "head_absolute", "head_connect").map { name =>
      val inputs = heads(name)
      name -> ((i: Int) => {
        val head = RequestHeadParser.read(new ByteArrayInputStream(inputs(i & 1)), limits).get
        (head.target.length + head.headers.size).toLong
      })
    }
    val uriCases: Array[(String, Int => Long)] = Array(
      "uri_target_construct" -> ((i: Int) => {
        new URI("http://request.invalid" + targets(i & 1)).getRawPath.length.toLong
      }),
      "uri_host_construct" -> ((i: Int) => new URI("http://" + hosts(i & 1)).getRawAuthority.length.toLong)
    )
    var sink = 0L
    for ((name, operation) <- headCases ++ uriCases) {
      for (round <- 0 until 8) {
        val iterations = 50000
        val start = System.nanoTime()
        var i = 0
        var checksum = 0L
        while (i < iterations) {
          checksum += operation(i)
          i += 1
        }
        val elapsed = System.nanoTime() - start
        sink += checksum
        if (round >= 3) println(s"$name round=$round ns_per_op=${elapsed / iterations} checksum=$checksum")
      }
    }
    println(s"sink=$sink")
    ResponseHeadBenchmark.run()
  }
}
