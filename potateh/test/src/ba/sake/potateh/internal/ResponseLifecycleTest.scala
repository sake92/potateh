package ba.sake.potateh.internal

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.net.Socket
import java.nio.file.Files

import scala.collection.mutable.ListBuffer

import ba.sake.potateh.*

class ResponseLifecycleTest extends munit.FunSuite {
  private val cases = List(
    (name = "success", method = "POST", status = 200, writes = true),
    (name = "HEAD", method = "HEAD", status = 200, writes = false),
    (name = "204", method = "POST", status = 204, writes = false),
    (name = "205", method = "POST", status = 205, writes = false),
    (name = "304", method = "POST", status = 304, writes = false),
    (name = "handler failure", method = "POST", status = 200, writes = false),
    (name = "construction failure", method = "POST", status = 200, writes = false),
    (name = "preparation failure", method = "POST", status = 200, writes = false),
    (name = "writer failure", method = "POST", status = 200, writes = true),
    (name = "head write failure", method = "POST", status = 200, writes = false),
    (name = "disconnect", method = "POST", status = 200, writes = true),
    (name = "flush failure", method = "POST", status = 200, writes = true)
  )

  for (scenario <- cases) {
    test(s"${scenario.name}: uploaded file survives processing and is cleaned exactly once") {
      val upload = Files.createTempFile("potateh-upload-", ".bin")
      val payload = Array[Byte](0, 1, -1, 13, 10)
      Files.write(upload, payload)
      var cleaned = 0
      var writerCalled = false
      var flushCalled = false
      val errors = ListBuffer.empty[Throwable]
      val socket = new Socket()
      val output = new ByteArrayOutputStream {
        override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
          assertEquals(cleaned, 0)
          if (scenario.name == "head write failure" || (scenario.name == "disconnect" && writerCalled)) {
            throw new IOException("client disconnected")
          }
          super.write(bytes, offset, length)
        }
        override def flush(): Unit = {
          assertEquals(cleaned, 0)
          assert(Files.exists(upload))
          flushCalled = true
          if (scenario.name == "flush failure") throw new IOException("flush failed")
          super.flush()
        }
      }
      val input = new ByteArrayInputStream(Array.emptyByteArray)
      val head = RequestHeadParser.RequestHead(
        scenario.method,
        "/upload",
        "HTTP/1.1",
        Vector("Host" -> "localhost"),
        RequestHeadParser.NoBody
      )
      val exchange = new Exchange(
        socket,
        input,
        output,
        head,
        ServerConfig.Default.withOnError(errors += _),
        request => {
          request.withTarget("/copy").onResponseComplete {
            cleaned += 1
            Files.delete(upload)
          }
          if (scenario.name == "handler failure") throw new IllegalStateException("handler failed")
          val status = if (scenario.name == "construction failure") 600 else scenario.status
          val headers = if (scenario.name == "preparation failure") Headers("Connection" -> "close") else Headers.empty
          Response(
            status,
            headers,
            ResponseBody.Writer(Some(payload.length.toLong)) { out =>
              writerCalled = true
              assertEquals(cleaned, 0)
              assert(Files.exists(upload))
              if (scenario.name == "writer failure") throw new IOException("writer failed")
              out.write(Files.readAllBytes(upload))
            }
          )
        }
      )
      try {
        val reusable = exchange.run()
        assertEquals(cleaned, 1)
        assert(!Files.exists(upload))
        assertEquals(writerCalled, scenario.writes)
        val failed = scenario.name.endsWith("failure") || scenario.name == "disconnect"
        assertEquals(errors.size, if (failed) 1 else 0)
        assertEquals(reusable, !failed)
        if (!failed) assert(flushCalled)
        if (scenario.name == "success") assert(output.toByteArray.takeRight(payload.length).sameElements(payload))
      } finally {
        socket.close()
        Files.deleteIfExists(upload)
      }
    }
  }

  test("cleanup failures are reported without skipping callbacks or repeating completion") {
    val completion = new ResponseCompletion
    val events = ListBuffer.empty[String]
    completion.add(() => {
      events += "first"
      throw new IOException("cleanup failed")
    })
    completion.add(() => {
      events += "second"
      ()
    })
    val report: Throwable => Unit = _ => {
      events += "reported"
      throw new IllegalStateException("reporter failed")
    }
    completion.finish(report)
    completion.finish(report)
    assertEquals(events.toList, List("first", "reported", "second"))
    intercept[IllegalArgumentException](completion.add(() => ()))
  }
}
