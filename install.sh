#!/usr/bin/env bash
# install.sh - run from the folder you cloned. Makes sure Babashka (bb), which
# mdserve runs on, is installed, then runs install.bb to write your config.
set -e
cd "$(dirname "$0")"
# If bb is missing, fetch it with its own official installer into ~/.local/bin
# (no sudo needed). mdserve.sh looks there too, so it needn't be on PATH.
bb=$(command -v bb || command -v ~/.local/bin/bb) || {
  echo "Installing Babashka (bb), which mdserve runs on, into ~/.local/bin ..."
  command -v curl >/dev/null || { echo "Needs curl to fetch Babashka - install curl and run this again."; exit 1; }
  mkdir -p ~/.local/bin
  bash <(curl -fsSL https://raw.githubusercontent.com/babashka/babashka/master/install) --dir ~/.local/bin
  bb=~/.local/bin/bb
}
exec "$bb" install.bb
