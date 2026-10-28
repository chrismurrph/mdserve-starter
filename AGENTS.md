# mdserve: notes for a coding agent

You are probably reading this because the person you work with runs mdserve, a
local markdown viewer on `http://localhost:7000` (the port is in their config).
It has two uses for you: **showing them docs**, and **being changed** when they
ask. Each has its own half below.

## Part 1: using it

### Show a doc

When you have written or found a doc they should read, put it in front of them:

```bash
curl -s "http://localhost:7000/show$(realpath FILE.md)"
```

It renders the file and opens it as a browser tab, in the window of the project
the file lives in (or the panel's window). Showing the same file again reloads
that tab rather than opening another, so after editing a doc you have shown,
just show it again.

- **`?ws=<n>`** sends it to the window of the project configured on workspace
  `n`. Use it when they say "put it up on 3".
- **`?at=<heading-id>`** scrolls to a heading. The id is on the rendered
  heading; read it rather than guessing:
  `curl -s "http://localhost:7000/view$(realpath FILE.md)?show=1" | grep -oE '<h[23] id="[^"]*"'`
- **An ordinary web page:**
  `curl -sG --data-urlencode 'url=https://...' "http://localhost:7000/show-link"`.
  Encode the url as shown; an inline `?url=` breaks on its first `&`.
- **Not running?** If curl gets "connection refused", tell them to start
  `mdserve.sh`. Do not start it yourself: they start it on the workspace where
  they want the panel.

Docs written in an agent scratchpad under `/tmp/claude-<uid>/...` still land in
the right project's window, because the scratchpad path encodes the project.

### Before you show a doc: lint it

```bash
bb md_lint.bb FILE.md
```

`md_lint.bb` (beside `mdserve.clj`) catches markdown that looks fine as source
but breaks when rendered: tables in blockquotes, blank lines inside tables,
dead `#anchor` links, links to missing sibling files, underscores eaten as
emphasis. The same checks drive the warning banner at the top of a rendered
doc.

### Other routes worth knowing

| Route | What |
|---|---|
| `/health` | `ok` when it is up |
| `/version` | which `mdserve.clj` is running, and `stale?` if the file has changed since |
| `/view/<abs-path>` | a file or folder, rendered, without opening anything |
| `/commands/<project>/status` | JSON: each command button's state |
| `/tab-show/<abs-path>` | put a doc on the tablet reading list, if one is configured |
| `/sessions/<n>` | workspace n's Claude Code sessions; `/sessions/rows?ws=<n>` is its 30-second poll (not logged); mdserve also refreshes every 3 minutes on its own |
| `/sessions/find?q=<words>` | search the session history (every word, any case); `&format=text` for a shell, as `session-find.sh` does |
| `/tablet/off`, `/tablet/on` (POST), `/tablet/status` | stop or start the tablet server without a restart; off survives restarts (`tablet.edn.off`) |

## Part 2: changing it

### The shape

Everything is in **`mdserve.clj`**, one babashka script, top to bottom:

1. **Config:** `config.edn` is read once at startup (`config`, `projects`,
   `port`, `browser` ...). The command lists are the exception: `commands-of`
   re-reads the file on every dashboard load.
2. **Page shell:** `css`, the button helpers (`action-button`, `status-button`),
   the small head scripts (toast, scroll restore, PageUp/PageDown), and `layout`,
   which wraps every desktop page.
3. **Memory badge and version line** on the panel.
4. **Tabs:** `url->tab`, `tab-category` (which dot a tab has), `tab-project`
   (which project it belongs to), `read-tabs`.
5. **Rendering:** `md-body-html-with-health` and everything above it. This
   handles markdown-clj's weak spots: wrapped emphasis and code spans,
   fences inside blockquotes, backslashes in inline code, indented tables. It
   also adds diff colouring, entry lists, heading anchors and the health
   banner.
6. **The Explorer:** `dir-listing-md` and `view`, which serves every file
   type.
7. **Placement:** `cdp!` (one DevTools call), `open-url-via-opener!`,
   `place-url!`, `show`. This section decides which window a tab goes to.
8. **Terminal and editor buttons**, then **Remember Tabs** and **replay**:
   `remember-tabs`, `window-groups`, `replay-tabs!`.
9. **Command buttons:** `cmd-status`, `cmd-start!`, `cmd-stop!`, the dashboard
   (`commands-page`), the log viewer (`commands-log`), and `commands-route`.
10. **Tablet reading list:** a second server, `tablet-app`, with its own three
    routes.
11. **The panel** (`landing`), the route table (`app`), and startup.

### How the windows work

- mdserve launches Chrome with its own profile and `--remote-debugging-port`
  (`devtools-port`). Everything about tabs goes through that port: listing
  them, reloading one, and opening a new tab in a chosen window.
- **Chrome only honours the DevTools flag on the launch that starts the
  browser.** That is why the first open of a startup is
  `open-url-with-debug-port!` and every later one is not.
- **A new tab goes to a window through `window.open`, run inside a tab already
  in that window** (`open-url-via-opener!`). Chrome's command line puts a URL in
  the last-active window, and Linux compositors refuse to focus a window on
  another workspace, so anything based on focus fails there.
- **Windows are never recorded.** DevTools does not say which window a tab is
  in, so mdserve works it out from what each tab belongs to (`tab-project`):
  a project's dashboard, or a file under its folder.
- **No program can put a window on a given workspace** on GNOME or KDE under
  Wayland, or in macOS Spaces. The `:workspace` numbers in the config are labels the person
  maintains by moving windows themselves; they decide where `?ws=<n>` sends a
  doc, nothing more. Each project window gets `--class=mdserve-<name>`, so a
  window-rule extension can place it, where one exists.

### Config keys

`~/.config/mdserve-starter/config.edn` (or `MDSERVE_CONFIG`). `install.bb` writes
the common ones; all are optional.

| Key | Default | What |
|---|---|---|
| `:port` | 7000 | where mdserve listens (`MDSERVE_PORT`) |
| `:browser` | `google-chrome`; on macOS, the program inside `Google Chrome.app` | browser command, flags allowed (`MDSERVE_BROWSER`); a path with spaces, or a vector of words |
| `:profile` | `~/.local/share/mdserve-starter/browser` | the browser's own profile (`MDSERVE_PROFILE`) |
| `:devtools-port` | 9222 | `0` turns placement and Remember Tabs off (`MDSERVE_DEVTOOLS_PORT`) |
| `:projects` | none | `[{:name :dir :workspace :commands} ...]`; one window each |
| `:panel-workspace` | none | the workspace number that means the panel's window |
| `:commands` | none | command buttons for the panel's window, run from `~` |
| `:path` | `["~/bin"]` | put at the front of PATH for every command |
| `:home-dir` | the launch folder | where the panel window's Explorer starts |
| `:raw-dir` | `~/bin` | every file under it is viewable as text |
| `:state-dir` | `~/.local/state/mdserve-starter` | command pids, logs, exit codes (`MDSERVE_STATE`) |
| `:terminal`, `:terminal-run` | first known terminal | `{dir}` and `{cmd}` templates |
| `:tablet-port` | 0 (off) | the LAN reading list (`MDSERVE_TABLET_PORT`) |
| `:tablet-bind` | `0.0.0.0` | interface for it (`MDSERVE_TABLET_BIND`) |
| `:sessions` | see below | the Sessions tabs |

A command is `{:id :run}` plus optional `:label`, `:ports`, `:port-roles`,
`:dir` and `:once`. Ids and project names are limited to letters, digits and
`. _ -`.

`:sessions` is optional, and read on every refresh: `{:claude-dirs ["~/.claude"]
:history-file "<config dir>/session-history.edn" :context-window 1000000
:context-warn-pct 60 :context-alarm-pct 80}` are the defaults. List more Claude
Code config folders in `:claude-dirs` for a second identity. A project's
`:session-dirs` adds folders whose sessions belong to its window. A session from
no project's folder goes to `:panel-workspace`, so set that to see them. The
history file keeps every session's trail, title and context peak, never deleted:
Claude Code removes transcripts after `cleanupPeriodDays`.

### Rules for editing

- **Never evaluate the file's top-level forms to test something.** The last
  forms start the server and replay the remembered tabs, which opens real
  windows in their browser. Test by running a separate instance (below).
- **Test on your own instance, never theirs:**
  ```bash
  MDSERVE_PORT=7099 MDSERVE_NO_OPEN=1 MDSERVE_DEVTOOLS_PORT=0 MDSERVE_TABLET_PORT=0 \
  MDSERVE_TABS=/tmp/mdserve-test-tabs.edn MDSERVE_STATE=/tmp/mdserve-test-state \
    nohup bb mdserve.clj > /tmp/mdserve-test.log 2>&1 &
  ```
  Each of those variables keeps the test away from something live: their port,
  their browser, their tablet port, their saved tabs, their running commands. To
  test placement, use a separate `MDSERVE_PROFILE` and a `MDSERVE_DEVTOOLS_PORT`
  other than theirs, with `MDSERVE_BROWSER="google-chrome --headless=new"`.
  Headless Chrome opens only the first window, so create the others with
  `Target.createTarget {newWindow: true}`.
- **Stop a test instance by its pid,** found with `ps -eo pid,cmd`. Never
  `pkill -f mdserve`: the pattern also matches their own instance, and your
  shell.
- **Changes are inert until they restart mdserve.** Say so; never imply an edit
  is live. `curl -s localhost:7000/version` shows whether the running copy is
  the file on disk.
- **After every edit, check it still parses:**
  ```bash
  bb -e '(with-open [r (java.io.PushbackReader. (clojure.java.io/reader "mdserve.clj"))] (loop [n 0] (let [f (read {:eof ::eof} r)] (if (= f ::eof) (println "parsed OK" n) (recur (inc n))))))'
  ```
- **Rendering changes:** save `<main>` of a few docs before the change and
  compare after. Every difference should be one you meant. The renderer has
  many interacting fixes, and a change to one shows up in pages you didn't
  look at.

### Traps

- **`layout`'s third argument is pre-rendered HTML.** CSS passed there must be
  a complete `<style>…</style>` element, or it renders as body text.
- **`&gt;` belongs in attributes only.** Inside an `onclick`, `>` has to be
  written `&gt;` (`action-button` does this), and the browser decodes it.
  Copy that code into a `<script>` block and the `&gt;` stays literal: a
  syntax error that silently kills every function in the block. The scripts
  here use plain `function(){}` with no `>` comparisons where possible, so code
  can move between the two safely.
- **Never `alert`, `confirm` or `prompt`** in a page. A native dialog blocks
  the tab, and anything driving the browser over DevTools hangs with it.
- **Every page is a static render.** Nothing updates an open tab unless it was
  written to: dashboards poll only with Auto-refresh ticked. A button rendered
  disabled stays disabled until the page reloads.
- **Action buttons reload the page.** `action-button` and `cmdGo` POST, then go
  to the URL the endpoint returns. Scroll position and a short "✓ Done" note
  survive through `sessionStorage` (`mdserveScrollY`, `mdserveToast`). An
  endpoint that returns a different page must not reuse those keys.
- **The tablet server is the security boundary.** It is reachable from the
  LAN and serves only docs on its list. Never add a route to `tablet-app` that
  takes a path; add LAN-safe features there deliberately, and everything else
  to `app`, which listens on localhost only.
- **`slurp` cannot read `/proc` files under babashka.** They report size 0.
  Use `Files/readAllBytes`, as `proc-read` does.
- **`query-param` splits on `&`**, so a value carrying one must arrive
  percent-encoded.
