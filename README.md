# potateh

A blocking HTTP/1 server for Scala 3, built for Scala Native and JVM.

Supply a `Request => Response` handler; potateh handles HTTP/1.0 and HTTP/1.1
parsing, body framing and connection management.

- Simple `Request => Response` API with immutable headers and configuration.
- Streaming request and response bodies, including chunked encoding and trailers.
- Bounded connections, configurable size limits and I/O timeouts.
- Persistent connections, ordered pipelining and graceful shutdown.

Routing, authentication, TLS, HTTP/2 and WebSockets belong outside this core.

## Installation

For Scala Native or JVM with sbt 2, add the dependency in `build.sbt`:

```scala
libraryDependencies += "ba.sake" %% "potateh" % "0.1.0"
```

## Start a server

```scala
import ba.sake.potateh.*
import java.nio.charset.StandardCharsets.UTF_8

val server = Server.start() { _ =>
  Response(body = ResponseBody.bytes("hello".getBytes(UTF_8)))
}
println(server.address)
```

The server listens on `127.0.0.1:8080` by default. Try it with:

```sh
curl http://127.0.0.1:8080/
```

Call `server.stop()` when your application shuts down. It stops accepting new
connections and gives active exchanges time to finish before closing them.
There is no automatic shutdown hook.

## Configuration

Start from the defaults and use `withX` methods to create updated values:

```scala
import java.net.InetSocketAddress
import scala.concurrent.duration.*

val config = ServerConfig.Default
  .withBindAddress(new InetSocketAddress("127.0.0.1", 9000))
  .withMaxConnections(256)
  .withLimits(HttpLimits.Default.withMaxBodyBytes(Some(16L * 1024 * 1024)))
  .withTimeouts(HttpTimeouts.Default.withBodyReadTimeout(10.seconds))
```

Pass `config` to `Server.start(config)` in the example above.

| Setting | Default |
| --- | --- |
| Maximum connections | 128 |
| Listener backlog | 128 (subject to OS limits) |
| Maximum request body | 8 MiB |
| Idle keep-alive timeout | 15 seconds |
| Request-head deadline | 10 seconds |
| Body-read / write timeout | 30 seconds each |
| Shutdown grace | 5 seconds |

`HttpLimits` also bounds request lines, headers, chunks and trailers.
Use `withMaxBodyBytes(None)` to disable the body size limit.
`withOnError` replaces the default exchange-error reporting to stderr;
the callback runs on connection workers and should complete promptly.

## Handlers and streaming

Handlers run synchronously and may execute concurrently across connections.
Requests on each connection are handled in order.

Read a request body through `request.body`, an `InputStream`. For streaming
responses, use `ResponseBody.Writer` with a synchronous `OutputStream => Unit`
callback. Body streams belong to their exchange: use them only inside the
handler or response writer. The server manages HTTP framing and connection
headers. Unread request bodies prevent connection reuse.

The default executor uses platform threads, with one worker per connection.
Idle keep-alive connections retain a worker and a connection slot, so increasing
`maxConnections` also increases potential thread and memory use.

Use `Server.start(config, executor)(handle)` to supply an executor, including a
JVM virtual-thread executor. Queued connections count toward the connection
limit, and a small fixed pool can be occupied entirely by idle connections.
You own the supplied executor and must shut it down separately.

## Development

Build and test with Java 21 and Deder 0.22.2; Native builds also need clang
(Scala Native 0.5.12).

```sh
deder exec -t compile
deder exec -t test
```

See [architecture](ARCHITECTURE.md) for execution and lifecycle details,
[conformance checks](conformance/README.md) for external checks and platform
limitations, and [planned improvements](TODO.md).
