# Performance findings

These are local, directional measurements from 2026-11-13, not release
qualification. The target was Scala 3.9.0 and Scala Native 0.5.12 with
`release-fast` and LTO disabled. The server ran on CPUs 0-3 and h2load on CPUs
4-7. Raw output and benchmark helpers are intentionally not committed.

## 64-connection pipelined baseline

Workload: HTTP/1.1, 64 connections, pipeline depth 16, `/pipeline`, three
10-second runs per entry.

| Entry | Median |
| --- | ---: |
| Potateh | 138.1k req/s |
| Sharaf on Potateh | 106.3k req/s |
| Rack with Puma | 97.9k req/s |
| Rust veil-api | 444.7k req/s |

The first cross-layer win was disabling Sharaf's CORS middleware by default.
Previously, every request paid for CORS path checks, origin lookup, response
header updates, and extra headers even when no origin policy was configured.

## Connection scaling

These runs used fresh servers, listener backlog 8192, and a sufficiently high
load-generator file-descriptor limit. Throughput remained flat through 4096
active connections.

| Connections | Potateh p16 | Sharaf p16 | Rust p16 |
| ---: | ---: | ---: | ---: |
| 256 | 151.5k | 136.5k | 469.1k |
| 512 | 147.1k | 134.3k | 458.0k |
| 1024 | 144.7k | 141.6k | 484.5k |
| 2048 | 153.0k | 138.3k | 477.8k |
| 4096 | 141.6k | 135.8k | 466.6k |

At pipeline depth 1, Sharaf was slightly faster than Potateh in these runs:
roughly 90k-94k versus 84k-88k req/s. The remaining pipelined gap is therefore
primarily per-request CPU and cache cost, not connection-count collapse.

`maxConnections` does not configure the listen backlog. Applications that
expect large synchronized connection bursts should raise both settings, for
example:

```scala
val config = ServerConfig.Default
  .withMaxConnections(4096)
  .withBacklog(4096)
```

## Bounded character loops

The request and response header paths used generic `String.forall` calls,
lambda dispatch, character boxing, and temporary substrings. The request-line
parser also used `String.lastIndexOf`, which was 8.4% of sampled cycles under
the Sharaf workload.

The replacement scans strings with bounded primitive-character loops, validates
header names without a temporary substring, and finds the request-line version
separator with a backward scan.

Matched user-space counters over eight seconds:

| Per request | Potateh before | Potateh after | Sharaf before | Sharaf after |
| --- | ---: | ---: | ---: | ---: |
| Throughput | 143.0k | 180.4k | 125.6k | 154.8k |
| Instructions | 142,966 | 85,934 | 138,515 | 90,507 |
| Branches | 35,680 | 20,741 | 34,024 | 21,449 |
| Branch misses | 54.1 | 43.0 | 64.9 | 47.2 |
| Cache references | 265.7 | 255.6 | 427.9 | 403.5 |
| Cache misses | 80.6 | 78.0 | 126.8 | 125.2 |

Five fresh-server 64-connection runs put the candidate medians at 164.5k req/s
for Potateh and 149.5k req/s for Sharaf.

The gain is larger than the lambda call alone suggests because it removes
several multiplicative costs:

- `StringOps.forall` generic iteration and `Function1` calls;
- `Character` and Boolean boxing in the validation callback;
- a temporary header-name substring allocated only for validation;
- repeated string scans for punctuation token characters;
- runtime module loads and generic dispatch;
- a request-line `lastIndexOf` call that LLVM could not inline into the parser.

The portability lesson is not that collection methods are generally slow. It
is that short, pure, extremely hot loops can pay for explicit primitive
implementations on Scala Native. Apply this only in measured hot paths.

## Possible automation

A Scalafix rule could flag generic character traversals such as `forall`,
`exists`, and `indexWhere` inside opt-in annotated hot-path code. Diagnostics
should come before autofixes. An automatic rewrite is safe only when the
predicate is recognized as pure and side-effect-free, because `forall` normally
preserves arbitrary predicate side effects.

## Remaining hotspots

After the loop change, allocation, request-head `StringBuilder` construction,
`String.charAt`, module loading, case-insensitive header handling, and Sharaf
response adaptation remain prominent.

The next candidates are:

1. investigate the intermittent Scala Native `SIGSEGV`/GC-trap failures seen
   during repeated connection teardown;
2. parse request-head spans directly from the connection buffer instead of
   appending one character at a time to `StringBuilder`;
3. construct Potateh's final response-header vector once instead of repeated
   `remove`/`add` operations;
4. add a known-length response path for small `String` and byte-array bodies;
5. move remaining case-insensitive header comparisons to allocation-light
   ASCII paths.

## Measurement traps

- Pin the server and load generator to disjoint CPU sets.
- Verify the resolved artifact graph; a same-version remote snapshot can hide
  the candidate.
- Raise the load generator's file-descriptor limit for thousands of clients.
- Raise the listener backlog for synchronized connection bursts.
- Allow connection teardown to settle between sequential full-load runs.
- Treat one throughput run as directional; use hardware counters and repeated
  fresh-server runs for optimization decisions.
