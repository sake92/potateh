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
