# potateh implementation plan

## Scope and decisions

Build a minimal blocking HTTP server core for Scala 3, targeting JVM and Scala Native. Own TCP sockets, HTTP/1.0 and HTTP/1.1 parsing, message framing, streaming and connection lifecycle. Expose raw request targets without path/query parsing, decoding or normalization.

Application entry point: `Server.start(config)(handle: Request => Response): Server`. Frameworks own handler classes, dependency injection, middleware and dispatch. No routing, authentication, cookies, forms, JSON, compression, TLS, HTTP/2, WebSocket upgrades or CONNECT tunnels in v1.

The returned server exposes its actual bound address (including port 0) and one idempotent, blocking `stop(): Unit`. Shutdown grace belongs in configuration. No required `close()`/`awaitTermination()` pair, scoped wrapper or automatic process shutdown hook. Application code decides when to stop the server.

## Build

- Use Deder, Scala **3.9.0**, and Java **21** for the JVM build and build runtime.
- Cross-build the library and shared behavior tests for JVM and Scala Native; do not carry over sbt or Scala 2 compatibility projects.
- Latest stable Scala Native verified on 2026-11-06: **0.5.12**, via the [release API](https://api.github.com/repos/scala-native/scala-native/releases/latest). Recheck at bootstrap and pin the latest stable compatible release, not a floating version or development snapshot.
- Select and pin a current Deder version during bootstrap using its documented cross-platform configuration. Verify Scala 3.9.0 compiler-plugin artifacts and Native multithreading before porting implementation.
- Prefer shared sources and standard blocking socket/stream APIs. Add platform-specific code only for demonstrated differences. Use Scalafmt's common preset unless the repository establishes another configuration.

## Public model

- `Request`: method string, unchanged target string, HTTP version, immutable headers, framed request body, local/remote socket addresses.
- `Response`: numeric status, immutable headers, response body.
- `Headers`: ordered repeated fields, case-insensitive lookup, first/all lookup and immutable add/set/remove operations. Never flatten all repeated fields into comma-separated values.
- `RequestBody`: blocking input stream bounded to one message; validated trailers available after EOF, distinct from initial headers.
- `ResponseBody`: empty, byte array, or synchronous output-stream writer with `Option[Long]` length. Known length is enforced; unknown length uses chunking for HTTP/1.1 and connection closure for HTTP/1.0.
- The engine owns framing and connection headers. Validate application response headers before commitment; reject conflicts instead of allowing ambiguous framing. HEAD and bodyless-status rules override body transmission.
- Streams belong to the exchange and cannot escape its lifetime. Request reads remain available during response writing, permitting streaming echoes. Writers acquire and release their own application resources inside the callback; writers are not invoked for bodyless responses.
- Callback invocations may run concurrently across connections; each connection processes requests sequentially.

## Configuration: new Scala case classes

Design configuration for potateh rather than copying the source's configuration mechanism or values. Use a small immutable `ServerConfig`, with simple nested case classes for HTTP limits and timeouts if useful. Pass it directly to `Server.start`; no system-property lookup, environment lookup, stringly typed settings or mutable setters inside the library.

Choose and document defaults deliberately during the first checkpoint. Include bind address/port, maximum admitted connections, request-line/header/trailer limits, decoded request-body limit, idle/head/body-read/write timeouts and shutdown grace. Use typed durations and `Option` for genuinely optional limits; avoid magic zero/negative values meaning disabled. Validate combinations before binding.

Header timeout is an absolute monotonic deadline, not renewed by trickling bytes. Body-read and socket-write timeouts bound blocking I/O rather than handler computation. Precisely define how idle keep-alive timeout hands over to the next request-head deadline. Do not promise write timeouts using socket read timeout alone.

## Reuse inventory

Source: `/home/sake/projects/sake92/scalanative-jdk-sun-httpserver`, inspected at commit `7cd8160eee9702f02a13e273b80cb5391d9283d1`. Recheck its working-tree changes before selecting code. Preserve Apache-2.0 licensing and applicable attribution when copying code or tests.

Paths below are relative to that repository. Production classes are under `src/main/scala/com/sun/net/httpserver/internal`; tests are under `src/test/scala/org/scalanative/testsuite/httpserver`.

| Source | Adaptation |
| --- | --- |
| `HttpSyntax`, `RequestHeadParser` | Preserve byte-level syntax/framing validation; return potateh models and retain raw targets. |
| `FixedLengthInputStream`, `ChunkedInputStream` | Preserve message boundaries, overflow checks, payload budgets and malformed/truncated input handling; expose validated trailers. |
| `FixedLengthOutputStream`, `ChunkedOutputStream`, `ResponseHeadWriter` | Preserve exact-length enforcement and wire serialization; replace JDK length sentinels with response-body variants. |
| `ConnectionPolicy` | Reuse protocol rules; replace property-based configuration and revisit all defaults. |
| `WriteTimeouts` | Reuse bounded socket-write protection and watcher ownership; adapt to case-class configuration. |
| `BlockingHttpServer`, `ServerExchange` | Extract connection loop, admission, timeout and cleanup behavior; replace mutable JDK exchange API and context dispatch. |
| Public `Headers` / `ReadOnlyHeaders` | Reuse validation knowledge and relevant cases, not Java Map inheritance or nullable contracts. |

Port as idiomatic vanilla Scala 3: immutable values where possible, enums/sealed states for framing and lifecycle, `Option` for absence, Scala collections, no null sentinels, unchecked `.get`, Java compatibility scaffolding or effect framework. Normalize nullable Java API results immediately at the boundary. Keep necessary mutable socket/parser state private and narrowly owned. Byte buffers and local loops remain appropriate for parsing.

Do not port contexts, principals, authenticators, filters, provider discovery, Java binary compatibility or JDK 8 null behavior. `OPTIONS *` reaches the callback as a raw target; do not copy the source's automatic context-routing response. Unsupported tunneling/upgrades must not accidentally switch protocols.

## Implementation checkpoints

### 1. Bootstrap and contracts

- Establish Deder JVM/Native library and test modules, formatting and minimal CI.
- Implement public models and validated case-class configuration; settle documented defaults.
- Keep one published library with internal codec, connection and server responsibilities; avoid speculative modules or public extension points.
- Prove both target builds and a shared test run before proceeding.

### 2. HTTP codec and bodies

- Port syntax, request parser, framing streams and response writer with their relevant tests in the same checkpoint.
- Cover strict CRLF/header syntax, Host requirements, target forms, transfer coding, duplicate/conflicting lengths, TE/CL ambiguity, overflow, premature EOF, chunk extensions and trailers.
- Enforce header/trailer and decoded body limits across reads and skips. Preserve terminal failure state: catching a body-limit/protocol exception must not let the callback send success or reuse the connection.
- Handle HEAD/bodyless statuses and response-length underflow/overflow. Validate response splitting attempts before bytes are committed.

### 3. Blocking server and lifecycle

- Bind synchronously; clean up partial startup failures.
- Use a server-owned accept thread and bounded connection workers. Admission must cover accepted/queued/running connections; no unbounded socket queue. Start with platform threads on both targets.
- Process persistent/pipelined exchanges in wire order, with HTTP/1.0 and HTTP/1.1 persistence rules.
- Send `100 Continue` lazily on first body consumption; reject declared oversized bodies before continuation or callback dispatch. Define unsupported expectations explicitly.
- If the request body remains unread after response writing, close the connection. Do not copy the source's automatic unread-body draining policy.
- Separate protocol rejection from callback failure; replace a response only before commitment. Abort the connection on failure after commitment.
- `stop()` stops admission, closes idle connections, prevents another exchange starting, waits up to the grace period for active exchanges, then closes remaining sockets and stops owned infrastructure. Use one bounded shutdown budget; define repeated/concurrent stop calls and stop invoked from a callback without self-join deadlocks. Stopping is terminal.
- Document that arbitrary application computation cannot be forcibly terminated safely; socket closure unblocks network I/O but does not guarantee every callback has returned.

### 4. Security and portability regression gate

- Adapt `RequestHeadParserTest`, `ResponseHeadWriterTest`, both fixed-length tests and both chunked-stream tests directly to potateh.
- Adapt the relevant header assertions, `NativeSocketSmokeTest`, and HTTP portions of `HttpServerTest` / `HttpServerContractTest` to execute against potateh on **both** targets.
- Preserve real-socket cases for framing, ordered pipelining, persistence, lazy continuation, malformed requests, admission/recovery, dripped heads, stalled bodies/chunks/trailers, blocked writes, disconnects and graceful/forced shutdown.
- Replace tests for system properties, caller executors, unread-body draining and JDK exchange lifecycle with assertions for the new contracts. Omit routing/auth/filter/null-contract tests; retain any embedded transport/security assertion separately.
- Add raw-target preservation, immutable duplicate headers, trailer exposure, streaming-writer lifetime and concurrent/repeated stop regressions.
- Use real loopback sockets, bounded waits and deterministic coordination. Keep a compact mapping of retained/adapted/excluded source scenarios so security coverage is not silently lost.
- Run `deder exec -t compile` and `deder exec -t test` with verified selectors covering both targets; also link/run a release-mode Native fixture. Local success is not remote CI evidence.

### 5. External conformance and release readiness

- Adapt `conformance/shared/ConformanceServer.scala`, `conformance/run.scala` and `conformance/SlowClients.scala` to potateh and Deder-built JVM/Native fixtures. Rewrite executor-specific scenarios for the server-owned bounded-worker model.
- Retain pinned h1spec, Http11Probe, slowclients and slowhttptest runs, raw verdict checking, readiness/time limits and owned-process cleanup. External test dependencies remain outside the library.
- Run both potateh targets; JDK server comparisons are optional diagnostics, never the required behavior oracle. RFC 9110/9112 decide protocol requirements.
- Do not copy old result JSON or claim the source's passing results as potateh evidence. Keep raw artifacts under ignored build output; retain concise reproducible commands and qualified findings.
- Account for existing probe limitations: h1spec single-read assumptions, disputed empty-Host expectations, and application-level capabilities outside this core. Do not silently suppress failures to improve scores.
- Document minimal start/stop usage, streaming ownership, defaults, protocol scope and known limitations. Verify CI independently before claiming portability or release readiness.

## Completion criteria

The same core callback API serves HTTP on JVM/Java 21 and Scala Native; relevant source compliance/security coverage is adapted and passing; configured bounds and shutdown behavior are exercised over real sockets; external results are reproduced and qualified. `TODO.md` tracks subsequent work, not inherited claims of implemented protections.

## Progress

- Checkpoint 1: bootstrapped Deder 0.22.2, Scala 3.9.0, Java 21 and Native
  0.5.12 with multithreading enabled. Shared MUnit 1.3.6 contracts/thread tests
  pass on both targets (3 tests per target). Defaults are recorded in README.md.
- Checkpoint 2: strict parser and all four framing streams ported with direct
  MUnit adaptations. Response writer uses body variants and rejects application
  framing/connection headers. Trailer fields are separate and body failures
  remain terminal. JVM and Native MUnit runs pass; Native linking required
  avoiding java.util.Locale (case-insensitive comparisons are shared).
