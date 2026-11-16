#!/usr/bin/env bash
set -euo pipefail

server_bin=${SERVER_BIN:?Set SERVER_BIN to a built HTTP benchmark server}
wrk_threads=${WRK_THREADS:-16}
connections=${CONNECTIONS:-$wrk_threads}
worker_threads=${POTATEH_THREADS:-$wrk_threads}
duration=${DURATION:-10s}
runs=${RUNS:-3}
path=${PATH_SUFFIX:-/pipeline}
server_cpus=${SERVER_CPUS:-}
wrk_cpus=${WRK_CPUS:-}

if ! command -v wrk >/dev/null 2>&1; then
  echo "wrk is required" >&2
  exit 1
fi

work_dir=$(mktemp -d)
server_log="$work_dir/server.log"
stop_file="$work_dir/stop"
server_pid=
server_command=("${server_bin}")
wrk_command=(wrk)

if [[ -n "${server_cpus}" ]]; then
  server_command=(taskset -c "${server_cpus}" "${server_bin}")
fi
if [[ -n "${wrk_cpus}" ]]; then
  wrk_command=(taskset -c "${wrk_cpus}" wrk)
fi

cleanup() {
  if [[ -n "${server_pid}" ]] && kill -0 "${server_pid}" 2>/dev/null; then
    touch "${stop_file}"
    if ! timeout 10s tail --pid="${server_pid}" -f /dev/null >/dev/null 2>&1; then
      kill "${server_pid}" 2>/dev/null || true
    fi
  fi
  rm -rf "${work_dir}"
}
trap cleanup EXIT

POTATEH_PORT=0 \
POTATEH_THREADS="${worker_threads}" \
POTATEH_MAX_CONNECTIONS="${connections}" \
POTATEH_STOP_FILE="${stop_file}" \
  "${server_command[@]}" >"${server_log}" 2>&1 &
server_pid=$!

port=
for _ in $(seq 1 100); do
  if ! kill -0 "${server_pid}" 2>/dev/null; then
    cat "${server_log}" >&2
    exit 1
  fi
  port=$(sed -n 's/^LISTENING \([0-9][0-9]*\).*/\1/p' "${server_log}" | head -n 1)
  if [[ -n "${port}" ]]; then
    break
  fi
  sleep 0.05
done

if [[ -z "${port}" ]]; then
  echo "Server did not report a listening port" >&2
  cat "${server_log}" >&2
  exit 1
fi

head -n 1 "${server_log}"
echo "wrk threads=${wrk_threads} connections=${connections} duration=${duration} runs=${runs}"

for run in $(seq 1 "${runs}"); do
  echo "=== run ${run}/${runs} ==="
  "${wrk_command[@]}" -t "${wrk_threads}" \
    -c "${connections}" \
    -d "${duration}" \
    --latency \
    "http://127.0.0.1:${port}${path}"
done
