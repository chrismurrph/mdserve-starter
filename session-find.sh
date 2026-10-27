#!/usr/bin/env bash
# Search the history of Claude Code sessions that mdserve keeps
# (session-history.edn beside config.edn): titles, trails of recaps, last
# requests, folders. Every word must match, in any case. Newest first, each with
# the command that resumes it. The panel's Sessions section has the same search.
#   ./session-find.sh WORDS...
# MDSERVE_PORT picks the mdserve to ask (default 7000).
[ $# -gt 0 ] || { echo "usage: session-find.sh WORDS..." >&2; exit 2; }
port="${MDSERVE_PORT:-7000}"
curl -sfG --data-urlencode "q=$*" --data "format=text" "http://localhost:$port/sessions/find" ||
  { echo "session-find.sh: no mdserve on port $port. The history file itself is session-history.edn beside config.edn (one session per line)." >&2; exit 1; }
