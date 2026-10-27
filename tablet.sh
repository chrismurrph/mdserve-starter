#!/usr/bin/env bash
# Turn the tablet reading list (mdserve's LAN server) off or on, at once and
# without restarting mdserve. Off stays off across restarts until turned on.
# The reading list itself is kept. The panel's Tablet buttons do the same.
#   ./tablet.sh off | ./tablet.sh on | ./tablet.sh   (no argument: on or off?)
# MDSERVE_PORT picks the mdserve to talk to (default 7000).
base="http://localhost:${MDSERVE_PORT:-7000}"
case "${1:-}" in
  off|on) curl -sS -X POST "$base/tablet/$1" ;;
  "")     curl -sS "$base/tablet/status" ;;
  *)      echo "usage: tablet.sh [off|on]" >&2; exit 2 ;;
esac
