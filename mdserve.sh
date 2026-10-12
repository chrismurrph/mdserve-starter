#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v bb >/dev/null 2>&1; then
  echo "mdserve: babashka (bb) not found on PATH." >&2
  exit 1
fi

exec bb "$here/mdserve.clj" "$@"
