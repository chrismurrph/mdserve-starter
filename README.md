# mdserve

A local markdown viewer and control panel, in one [babashka](https://babashka.org)
file. It is built for working alongside a coding agent: the agent writes a doc,
runs one `curl`, and the doc is in front of you, rendered, in the right window.

- **Markdown that renders properly.** Tables, callouts, coloured diffs, syntax
  highlighting, and fixes for the cases markdown parsers usually get wrong. A
  banner at the top of a doc flags anything that will not render as written.
- **`/show` puts a doc in the right window.** Each project gets its own browser
  window. A doc opens in the window of the project it lives in, or wherever
  `?ws=<n>` says. Showing it again reloads the tab instead of opening another.
- **Tabs survive a restart.** Remember Tabs saves the open tabs as EDN, and the
  next start rebuilds the windows from that.
- **An Explorer.** Browse folders, open docs, code, images and PDFs. Open a
  terminal or an editor from any of them.
- **Command buttons.** Each project's dashboard runs your scripts. A service
  shows a live dot and gets Launch, Stop and Restart. A one-shot gets Run, which
  opens its log as a tab and follows it until the run ends.
- **A tablet reading list** (optional). Push a doc to a tablet on your LAN for
  reading on the couch.

## Requirements

- Linux with a desktop. It is untested on macOS; there, the browser path and
  the terminal command would be the parts to change.
- [babashka](https://babashka.org) (`bb`). `install.sh` installs it for you if
  it's missing (into `~/.local/bin`, no sudo).
- Google Chrome or Chromium. mdserve runs it with its own profile, so your
  normal browser is untouched.
- `lsof`, so that ■ Stop can also end a server that was started from a terminal.
- `python3`, for the example projects' web server only.

## Install

```bash
git clone https://github.com/chrismurrph/mdserve-starter ~/code/mdserve
cd ~/code/mdserve
./install.sh
```

The installer asks for a port, your projects and their workspaces, then writes
`~/.config/mdserve-starter/config.edn`. Say yes to the two example projects to see
everything working before you add your own.

Then start it from the workspace you want the panel on:

```bash
./mdserve.sh
```

The windows open. Move each project's window to its workspace. No Linux desktop
lets a program choose a window's workspace, so this is done by hand after each
start (on GNOME: Super+Shift+Page Up/Down).

## Show a doc

```bash
curl "http://localhost:7000/show/ABSOLUTE/PATH/file.md"
curl "http://localhost:7000/show/ABSOLUTE/PATH/file.md?ws=3"       # project on workspace 3
curl "http://localhost:7000/show/ABSOLUTE/PATH/file.md?at=install" # scroll to a heading
curl -sG --data-urlencode 'url=https://example.com' "http://localhost:7000/show-link"
```

## Add a command button

In `config.edn`, under a project:

```clojure
:commands [{:id "web"   :label "Dev server" :run "my-dev-server" :ports [8080]}
           {:id "tests" :label "Unit tests" :run "make test" :once true}]
```

`:run` is a shell command, run in the project's folder with `~/bin` at the front
of PATH. A command with `:ports` counts as up once one of them answers. Reload the
dashboard to see the new button; no restart needed.

## Your coding agent

[`AGENTS.md`](AGENTS.md) is written for it: how to show docs, how the code is
laid out, and the rules for changing it safely. Point your agent at it.

## Licence

MIT. See [`LICENSE`](LICENSE).
