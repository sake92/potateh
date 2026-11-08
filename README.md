# potateh

Blocking HTTP/1.0 and HTTP/1.1 server core for Scala 3 on JVM and Scala Native.

- Simple `Request => Response` API with immutable headers and configuration.
- Streaming request and response bodies, including chunked encoding and trailers.
- Bounded connections, configurable size limits and I/O timeouts.
- Persistent connections, ordered pipelining and graceful shutdown.

For Scala Native or JVM with sbt 2, add the dependency in `build.sbt`:

```scala
libraryDependencies += "ba.sake" %% "potateh" % "0.1.0"
```

```scala
import ba.sake.potateh.*
import java.nio.charset.StandardCharsets.UTF_8

val server = Server.start() { request =>
  Response(body = ResponseBody.bytes("hello".getBytes(UTF_8)))
}
println(server.address) // defaults to 127.0.0.1:8080

// Call server.stop() when your application shuts down.
```

Pass a `ServerConfig` to customize the bind address, limits and timeouts.
Start from `ServerConfig.Default`, `HttpLimits.Default` or `HttpTimeouts.Default`
and use `withX` methods for immutable updates, for example
`ServerConfig.Default.withMaxConnections(256)` or `response.withStatus(201)`.

Callbacks may run concurrently across connections. Body streams belong to their
exchange; use them only in the callback or synchronous response writer.
The server manages HTTP framing and connection headers.

Use `Server.start(config, executor)(handle)` to supply a thread pool or JVM
virtual-thread executor. Each task serves a whole connection; queued connections
count toward the connection limit. The caller owns the supplied executor.

Routing, authentication, TLS, HTTP/2 and WebSockets belong outside this core.

Build and test with Java 21 and Deder 0.22.2; Native builds also need clang
(Scala Native 0.5.12).

```sh
deder exec -t compile
deder exec -t test
```

See [architecture](ARCHITECTURE.md), [conformance checks](conformance/README.md)
and [planned improvements](TODO.md).
