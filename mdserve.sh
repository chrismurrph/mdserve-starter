#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# install.sh may have put bb in ~/.local/bin, which isn't always on PATH yet.
bb=$(command -v bb || command -v ~/.local/bin/bb) || {
  echo "mdserve: babashka (bb) not found. Run ./install.sh to install it." >&2
  exit 1
}

exec "$bb" "$here/mdserve.clj" "$@"
