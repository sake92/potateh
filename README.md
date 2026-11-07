# potateh

Blocking HTTP/1.0 and HTTP/1.1 server core for Scala 3 on JVM and Scala Native.

Build with Java 21 (`JAVA_HOME` must point to it), Deder 0.22.2, Scala 3.9.0
and Scala Native 0.5.12. Native builds require clang and libunwind development files.
Tests use MUnit 1.3.6 on both targets:

```sh
deder exec -t compile
deder exec -t test
```

Configuration is immutable and validated before binding. Defaults bind loopback
port 8080, admit at most 128 connections, allow 8 MiB decoded bodies, 8 KiB
request/header/chunk/trailer lines, 32 KiB initial headers, 100 header fields,
32 KiB cumulative chunk extensions, 16 KiB trailers and 50 trailer fields.
`None` disables the decoded body limit; `Some(0)` permits empty bodies only.
Line limits exclude CRLF; section limits include CRLF and the final blank line.

Timeout defaults are 15 seconds idle, 10 seconds for a complete request head,
30 seconds per blocking body read or socket write, and 5 seconds shutdown grace.
These bound network I/O, not application computation.

```scala
import ba.sake.potateh.*
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8

val server = Server.start(ServerConfig(
  bindAddress = new InetSocketAddress("127.0.0.1", 0)
)) { request =>
  Response(body = ResponseBody.bytes("hello".getBytes(UTF_8)))
}
println(server.address) // actual port, including port 0 allocation
server.stop()
```

`Request.target` preserves the wire target, including escapes and query text.
There is no path decoding, normalization or routing. `Headers` preserves ordered
repeated fields and provides case-insensitive `first`/`all` and immutable
`add`/`set`/`remove`. Initial headers never include trailers;
`request.body.trailers` becomes `Some(headers)` when the framed body is complete.

The engine owns `Content-Length`, `Transfer-Encoding`, `Connection`, `Keep-Alive`,
`Upgrade`, and `Trailer` response headers. Supplying one rejects the response
before commitment. Return a final status (200–599). HEAD, 204, 205 and 304
suppress payloads and never invoke writers. CONNECT and upgrade requests receive
501; unsupported expectations receive 417. Declared oversized bodies receive
413 before callback dispatch or continuation. `100 Continue` is sent lazily
when the callback first consumes a nonempty request body before the final head.

For a streaming echo:

```scala
val server = Server.start(ServerConfig()) { request =>
  Response(body = ResponseBody.Writer() { output =>
    request.body.transferTo(output)
    ()
  })
}
```

A writer runs synchronously and acquires/releases its own application resources
inside the callback. Request reads remain available while it writes. Streams
expire with the exchange; do not retain them or use them from another thread.
Known writer lengths (`Some(n)`) are enforced. Unknown lengths use chunking on
HTTP/1.1 and connection closure on HTTP/1.0. An unread request body closes the
connection after response writing, without draining it. Protocol/body errors
are terminal even if application code catches the exception. After commitment,
failures abort the connection without a second response.

Connections run concurrently on server-owned platform threads, bounded by
`maxConnections`; each connection runs exchanges sequentially. There is no
accepted-socket work queue. The OS listen backlog is 128, separate from application admission.
The initial head deadline starts when the connection worker begins. On a reused
connection, the idle timeout covers waiting for the first byte; receiving it
starts a fresh absolute monotonic head deadline. Bytes trickled during the head
do not renew that deadline. Body reads and actual socket writes each receive
their own I/O timeout; time spent computing in the callback is excluded.

`stop()` is terminal and idempotent. It stops admission, closes idle/head-reading
connections, prevents another exchange starting, and waits for active exchanges
under one shutdown-grace budget. It then closes remaining sockets and signals
owned infrastructure to stop, joining threads within the remaining budget.
Concurrent external stop callers wait for that stop to finish. A callback stop
returns without joining itself; a callback racing another stop returns promptly
so it cannot block that stop. Stopping from a callback closes its own socket.
Zero grace permits immediate socket closure without a thread-join wait.
Arbitrary application computation cannot be terminated safely: socket closure
unblocks I/O but does not guarantee that every callback has returned. There is
no automatic process shutdown hook; applications own when to stop the server.

TLS, HTTP/2, upgrades/WebSockets, CONNECT tunnels, routing, authentication,
cookies, forms, JSON and compression are outside this core. See
[TODO.md](TODO.md) for planned improvements.
