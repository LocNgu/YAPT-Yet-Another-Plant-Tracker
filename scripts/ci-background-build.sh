#!/usr/bin/env bash
# CI helper for the `instrumented` job (#952, technical ADR-0041).
#
# Builds the debug and androidTest APKs while the emulator boots, so
# `connectedDebugAndroidTest` finds them up to date instead of building after boot.
#
#   ci-background-build.sh start <gradle args...>   launch the build in the background, return at once
#   ci-background-build.sh wait                     block until it finishes, print its log, exit with its status
#
# `start` and `wait` run in different workflow steps (`wait` from the emulator runner's
# script), so they share state through files in $RUNNER_TEMP rather than through `wait`.
# A failed background build fails `wait`, which fails the step and the job.
set -euo pipefail

dir="${RUNNER_TEMP:?RUNNER_TEMP is not set}/background-build"

case "${1:-}" in
  start)
    shift
    rm -rf "$dir"
    mkdir -p "$dir"
    # The exit file is written via a rename, so `wait` never reads a half-written one.
    nohup bash -c '
      dir="$1"; shift
      status=0
      gradle "$@" > "$dir/log" 2>&1 || status=$?
      echo "$status" > "$dir/exit.tmp"
      mv "$dir/exit.tmp" "$dir/exit"
    ' _ "$dir" "$@" > /dev/null 2>&1 &
    echo "$!" > "$dir/pid"
    echo "Background build started: gradle $*"
    ;;
  wait)
    if [ ! -d "$dir" ]; then
      echo "::error::No background build was started."
      exit 1
    fi
    while [ ! -f "$dir/exit" ]; do
      # A wrapper killed before it wrote its result (OOM, runner) would otherwise hang the job.
      if ! kill -0 "$(cat "$dir/pid")" 2> /dev/null && [ ! -f "$dir/exit" ]; then
        cat "$dir/log"
        echo "::error::The background build died without reporting a result."
        exit 1
      fi
      sleep 2
    done
    status="$(cat "$dir/exit")"
    echo "::group::Background build log"
    cat "$dir/log"
    echo "::endgroup::"
    if [ "$status" -ne 0 ]; then
      tail -n 40 "$dir/log"
      echo "::error::Background APK build failed (exit $status). The full log is in the group above."
      exit "$status"
    fi
    ;;
  *)
    echo "usage: $0 start <gradle args...> | wait" >&2
    exit 2
    ;;
esac
