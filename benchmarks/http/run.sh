#!/usr/bin/env bash
set -euo pipefail

server_bin=${SERVER_BIN:?Set SERVER_BIN to a built HTTP benchmark server}
load_tool=${LOAD_TOOL:-wrk}
load_threads=${LOAD_THREADS:-${WRK_THREADS:-16}}
connections=${CONNECTIONS:-$load_threads}
worker_threads=${POTATEH_THREADS:-$load_threads}
executor=${POTATEH_EXECUTOR:-fixed}
duration=${DURATION:-10s}
runs=${RUNS:-3}
path=${PATH_SUFFIX:-/pipeline}
pipeline=${PIPELINE:-16}
server_cpus=${SERVER_CPUS:-}
load_cpus=${LOAD_CPUS:-${WRK_CPUS:-}}

case "${load_tool}" in
  wrk)
    load_bin=${WRK_BIN:-wrk}
    ;;
  gcannon)
    load_bin=${GCANNON_BIN:-gcannon}
    ;;
  *)
    echo "LOAD_TOOL must be wrk or gcannon" >&2
    exit 1
    ;;
esac

if ! command -v "${load_bin}" >/dev/null 2>&1; then
  echo "${load_tool} is required (set ${load_tool^^}_BIN if needed)" >&2
  exit 1
fi

work_dir=$(mktemp -d)
server_log="$work_dir/server.log"
stop_file="$work_dir/stop"
server_pid=
if [[ "${server_bin}" == *.jar ]]; then
  server_command=("${JAVA_BIN:-java}" -jar "${server_bin}")
else
  server_command=("${server_bin}")
fi
load_command=("${load_bin}")

if [[ -n "${server_cpus}" ]]; then
  server_command=(taskset -c "${server_cpus}" "${server_command[@]}")
fi
if [[ -n "${load_cpus}" ]]; then
  load_command=(taskset -c "${load_cpus}" "${load_bin}")
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
POTATEH_EXECUTOR="${executor}" \
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
echo "${load_tool} threads=${load_threads} connections=${connections} duration=${duration} runs=${runs}"

for run in $(seq 1 "${runs}"); do
  echo "=== run ${run}/${runs} ==="
  case "${load_tool}" in
    wrk)
      "${load_command[@]}" -t "${load_threads}" \
        -c "${connections}" \
        -d "${duration}" \
        --latency \
        "http://127.0.0.1:${port}${path}"
      ;;
    gcannon)
      "${load_command[@]}" "http://127.0.0.1:${port}${path}" \
        -c "${connections}" \
        -t "${load_threads}" \
        -d "${duration}" \
        -p "${pipeline}"
      ;;
  esac
done
