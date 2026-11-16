# HTTP benchmark

This is a small end-to-end fixture for comparing Potateh revisions on the same
machine. The server uses a fixed platform-thread executor with one worker per
keep-alive connection. `run.sh` uses `wrk` with 16 load-generator threads and 16
connections by default.

Build the Scala Native server:

```sh
deder exec -m http-benchmark-native -t nativeLink
```

Run three ten-second measurements:

```sh
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run.sh
```

Override `LOAD_THREADS`, `CONNECTIONS`, `DURATION`, `RUNS`, or `PATH_SUFFIX` to
compare another workload. A fixed pool must be at least as large as the number
of persistent connections; otherwise accepted connections remain queued behind
workers blocked on reads.

Set `SERVER_CPUS` and `LOAD_CPUS` to separate CPU sets for less noisy A/B runs:

```sh
SERVER_CPUS=0-3 \
LOAD_CPUS=4-7 \
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run.sh
```

`wrk` measures one outstanding request per connection. Use `gcannon` when
evaluating pipelined throughput:

```sh
LOAD_TOOL=gcannon \
GCANNON_BIN=/path/to/gcannon \
POTATEH_THREADS=64 \
CONNECTIONS=64 \
LOAD_THREADS=16 \
PIPELINE=16 \
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run.sh
```
