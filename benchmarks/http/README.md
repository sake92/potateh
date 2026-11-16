# HTTP benchmark

This is a small end-to-end fixture for comparing Potateh revisions on the same
machine. The server uses a fixed platform-thread executor with one worker per
keep-alive connection. `run-wrk.sh` uses `wrk` with 16 load-generator threads and
16 connections by default.

Build the Scala Native server:

```sh
deder exec -m http-benchmark-native -t nativeLink
```

Run three ten-second measurements:

```sh
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run-wrk.sh
```

Override `WRK_THREADS`, `CONNECTIONS`, `DURATION`, `RUNS`, or `PATH_SUFFIX` to
compare another workload. A fixed pool must be at least as large as the number
of persistent connections; otherwise accepted connections remain queued behind
workers blocked on reads.

Set `SERVER_CPUS` and `WRK_CPUS` to separate CPU sets for less noisy A/B runs:

```sh
SERVER_CPUS=0-3 \
WRK_CPUS=4-7 \
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run-wrk.sh
```

`wrk` measures one outstanding request per connection. Use the HttpArena
pipeline profile or `gcannon -p 16` separately when evaluating pipelined
throughput.
