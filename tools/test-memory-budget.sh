#!/usr/bin/env bash
# Host-wide guard in addition to the test client's own cgroup limit.
set -euo pipefail
available_kib() { awk '/^MemAvailable:/ { print $2; found=1; exit } END { if (!found) exit 1 }' "${1:-/proc/meminfo}"; }
case "${1:-}" in
  check)
    # Optional read-only snapshot for regression tests; live launchers omit it.
    available=$(available_kib "${2:-/proc/meminfo}")
    if (( available < 12 * 1024 * 1024 )); then
      printf 'FAIL (memory preflight): %s MiB available; an isolated game test needs at least 12288 MiB. No game started and no AI service stopped.\n' "$((available / 1024))" >&2
      exit 1
    fi
    ;;
  watch)
    unit=${2:?test unit required}
    case "$unit" in
      simpleclouds-devclient|simpleclouds-realgame|simpleclouds-refclient) ;;
      *) echo 'Refusing to monitor a non-test unit' >&2; exit 2 ;;
    esac
    low=0
    while sleep 2; do
      available=$(available_kib)
      if (( available < 2 * 1024 * 1024 )); then low=$((low + 1)); else low=0; fi
      if (( available < 1024 * 1024 || low >= 3 )); then
        printf 'MEMORY-GUARD: stopping owned test %s, host has %s MiB available.\n' "$unit" "$((available / 1024))" >&2
        systemctl --user stop --no-block "$unit"
        exit 1
      fi
    done
    ;;
  *) echo 'Usage: test-memory-budget.sh check | watch OWNED_TEST_UNIT' >&2; exit 2 ;;
esac
