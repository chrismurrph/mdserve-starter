#!/usr/bin/env bb
;; mdserve — a local markdown viewer and control panel, in one babashka file.
;;
;; Start:     ./mdserve.sh          (run it on the workspace you want the panel on)
;; Show doc:  curl "http://localhost:7000/show/ABSOLUTE/PATH/file.md"
;; Config:    ~/.config/mdserve-starter/config.edn — written by `bb install.bb`
;;
;; It opens a dedicated browser (its own Chrome profile, with the DevTools port
;; on), one window for the panel and one per configured project. Shown docs land
;; in the window of the project they live in. Remember Tabs saves the open tabs
;; to EDN and the next start reopens them. Nothing polls in the background:
;; pages have a manual ↻ Refresh, and dashboards an opt-in auto-refresh.
;;
;; Editing this file? Read AGENTS.md first.
(babashka.deps/add-deps '{:deps {markdown-clj/markdown-clj {:mvn/version "1.12.10"}}})

(require '[org.httpkit.server :as srv]
         '[markdown.core :as md]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pprint]
         '[cheshire.core :as json]
         '[babashka.process :as p])

(import '[java.net URLDecoder URLEncoder URI]
        '[java.net.http HttpClient WebSocket$Listener]
        '[java.time LocalTime]
        '[java.time.format DateTimeFormatter])

(def home (System/getProperty "user.home"))

(defn expand-home
  "A leading ~ in a configured path, expanded. Config is hand-edited, and ~ is
   what people type."
  [s]
  (if (and (string? s) (str/starts-with? s "~"))
    (str home (subs s 1))
    s))

(def config-file
  "Where config.edn lives. install.bb writes it; it is plain EDN and meant to be
   hand-edited. Override with MDSERVE_CONFIG."
  (or (System/getenv "MDSERVE_CONFIG")
      (str home "/.config/mdserve-starter/config.edn")))

(defn read-config
  "config.edn as a map, or {} when it is missing or unreadable. mdserve must run
   with no config at all: one window, no projects, no command buttons."
  []
  (let [f (io/file config-file)]
    (if (.isFile f)
      (try (edn/read-string (slurp f))
           (catch Exception e
             (println (str "  config unreadable (" config-file "): " (.getMessage e)))
             {}))
      {})))

(def config
  "config.edn as it was at startup. Ports, browser and projects are read once, so
   changing them needs a restart. Command buttons are the exception: the
   dashboards re-read the file on every render (see commands-of)."
  (read-config))

(defn- normal-dir
  "A configured directory as an absolute path with no trailing slash, so it
   compares equal to the paths /view sees."
  [d]
  (str (.normalize (.toPath (.getAbsoluteFile (io/file (expand-home d)))))))

(def projects
  "The configured projects: [{:name :dir :workspace :commands} ...]. Each gets
   its own browser window, holding its Explorer and its command dashboard.
   Empty is fine: everything then lives in the one panel window. A :name ends
   up in URLs and file names, so it is limited to letters, digits and . _ -."
  (vec (for [p (:projects config)
             :let [nm (str (:name p))]
             :when (or (and (re-matches #"[A-Za-z0-9._-]+" nm) (not= nm "home") (:dir p))
                       (do (println "  skipping project - needs :dir and a :name of letters, digits, . _ - (not \"home\"):"
                                    (pr-str (dissoc p :commands)))
                           false))]
         (-> p
             (assoc :name nm)
             (update :dir normal-dir)))))

(def dev-projects
  "The project roots, in config order. Most of the tab and window code asks
   only 'which root is this under?', so it works from this."
  (mapv :dir projects))

(defn project-by-name [nm]
  (some #(when (= nm (:name %)) %) projects))

(defn project-by-dir [root]
  (some #(when (= root (:dir %)) %) projects))

(def panel-workspace
  "The workspace the panel window lives on, or nil. Only used to answer ?ws=<n>
   for the panel's own number without a warning."
  (:panel-workspace config))

(defn workspace-project
  "The :dir of the project whose window is workspace n, or nil (the panel's
   workspace, or one with no project)."
  [n]
  (some #(when (= n (:workspace %)) (:dir %)) projects))

(defn config-workspaces
  "Every workspace config.edn names, the panel's and each project's, in order."
  []
  (sort (distinct (remove nil? (cons panel-workspace (map :workspace projects))))))

(def port (or (some-> (System/getenv "MDSERVE_PORT") parse-long)
              (:port config)
              7000))

(def base (str "http://localhost:" port))

(def opener-str (System/getenv "MDSERVE_OPENER"))

;; MDSERVE_NO_OPEN (any value) — start the server but DON'T open any browser tabs
;; (useful for restarts: avoids piling up duplicate tabs; refresh existing ones).
(def no-open? (some? (System/getenv "MDSERVE_NO_OPEN")))

(def mac?
  "True on macOS, where Chrome, the terminal, memory figures and detached commands
   each need their own way."
  (str/starts-with? (str (System/getProperty "os.name")) "Mac"))

(def mac-chrome "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

(def browser
  "The browser command, flags allowed. Must be Chrome or Chromium: tab placement
   and Remember Tabs use its DevTools port. On macOS the default is the program
   inside Google Chrome.app."
  (or (System/getenv "MDSERVE_BROWSER") (:browser config) (if mac? mac-chrome "google-chrome")))

(def browser-cmd
  "browser as argv. A vector from config.edn is used as it is. A string is split on
   spaces, unless the whole string is a program on disk: macOS paths such as
   mac-chrome hold spaces."
  (cond
    (sequential? browser)           (vec browser)
    (.canExecute (io/file browser)) [browser]
    :else                           (str/split browser #"\s+")))

(def profile (or (System/getenv "MDSERVE_PROFILE")
                 (some-> (:profile config) expand-home)
                 (str home "/.local/share/mdserve-starter/browser")))

(def devtools-port
  "DevTools port opened on the dedicated browser, so mdserve can ask it what is
   open (Remember Tabs) and place new tabs in the right window (/show). Override
   with MDSERVE_DEVTOOLS_PORT; 0 launches without it, and tabs then land
   wherever the browser puts them. Anything local can drive this browser profile
   while the port is open; it is bound to localhost."
  (or (some-> (System/getenv "MDSERVE_DEVTOOLS_PORT") parse-long)
      (:devtools-port config)
      9222))

(def tabs-file
  "Remembered tab set, replayed on startup. Deliberately outside the browser
   profile: wiping the profile (e.g. to clear a stale service worker) shouldn't
   cost you your tabs. Override with MDSERVE_TABS."
  (or (System/getenv "MDSERVE_TABS")
      (str home "/.config/mdserve-starter/tabs.edn")))

(def css
  (str/join "\n"
            ["*{box-sizing:border-box}"
             ":root{color-scheme:light dark;--bg:#f6f8f7;--fg:#1a2320;--fg-soft:#55635e;--line:#dbe3e0;--accent:#0c6e6c;--code-bg:#e5ebe8;--pre-bg:#eff3f1;--callout-bg:#e6f0ef;--add-bg:#e2f0e2;--add-fg:#1a6d2f;--del-bg:#f6e0e0;--del-fg:#b02020;--sans:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;--serif:'Iowan Old Style','Palatino Linotype',Palatino,'Book Antiqua',Georgia,serif;--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace}"
             "@media (prefers-color-scheme:dark){:root{--bg:#0e1413;--fg:#e7edeb;--fg-soft:#97a8a4;--line:rgba(231,237,235,.14);--accent:#47c7bc;--code-bg:#1c2523;--pre-bg:#131b19;--callout-bg:#173029;--add-bg:#122a19;--add-fg:#6fd68a;--del-bg:#2c1717;--del-fg:#f08a8a}}"
             "body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.7 var(--sans);-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}"
             ;; The navigation tab (reached by browsing, not by /show) is marked by a
             ;; green-dot favicon emitted in layout's <head> (shown-doc tabs get a
             ;; hollow grey dot) — the tab itself, not the page. No page CSS needed.
             "main.md{max-width:60rem;margin:0 auto;padding:48px 24px 140px}"
             "main.md>:first-child{margin-top:0}"
             "main.md>*{max-width:44rem}"
             "main.md>table,main.md>pre{max-width:100%}"
             "h1,h2,h3,h4,h5,h6{font-family:var(--serif);line-height:1.2;margin:2em 0 .55em;font-weight:600;text-wrap:balance;letter-spacing:-.01em}"
             "h1{font-size:2.3em;padding-bottom:.28em;border-bottom:1px solid var(--line)}"
             "h2{font-size:1.6em;padding-bottom:.25em;border-bottom:1px solid var(--line)}"
             "h3{font-size:1.2em;background:var(--code-bg);border-left:3px solid var(--accent);padding:.35em .75em;border-radius:0 6px 6px 0}"
             "h4{font-size:1.08em}"
             "p,ul,ol,blockquote,table,pre{margin:0 0 1.15em}"
             "a{color:var(--accent);text-decoration:none}"
             "a:hover{text-decoration:underline;text-underline-offset:2px}"
             "code{font-family:var(--mono);font-size:.86em;background:var(--code-bg);padding:.12em .38em;border-radius:5px}"
             "pre{background:var(--pre-bg);padding:16px 18px;border-radius:10px;overflow:auto;line-height:1.55}"
             "pre code{background:none;padding:0;font-size:.85em}"
             "blockquote{border-left:3px solid var(--accent);background:var(--callout-bg);color:var(--fg-soft);padding:.7em 1.1em;margin-left:0;border-radius:0 8px 8px 0}"
             "blockquote>:first-child{margin-top:0}"
             "blockquote>:last-child{margin-bottom:0}"
             "ul,ol{padding-left:1.6em}"
             "li{margin:.3em 0}"
             "li::marker{color:var(--accent)}"
             "ul.entries{list-style:none;padding-left:0;display:flex;flex-direction:column;gap:1.15em}"
             "ul.entries li.entry{margin:0;padding-left:.95rem;border-left:2px solid var(--line)}"
             "li.entry .term{display:block;margin-bottom:.12em}"
             "li.entry .term code{background:none;padding:0;color:var(--fg);font-weight:600;font-size:.95em}"
             "li.entry .desc{color:var(--fg-soft)}"
             "table{border-collapse:collapse;display:block;overflow:auto;width:max-content;max-width:100%}"
             "th,td{border:1px solid var(--line);padding:5px 9px;max-width:34rem;overflow-wrap:anywhere;vertical-align:top}"
             "th code,td code{white-space:nowrap;overflow-wrap:normal}"
             "th{font-weight:600}"
             "thead th{border-bottom-width:2px}"
             "tr:nth-child(2n) td{background:var(--pre-bg)}"
             "hr{border:0;border-top:1px solid var(--line);margin:2.4em 0}"
             "img{max-width:100%}"
             ":focus-visible{outline:2px solid var(--accent);outline-offset:2px}"
             "pre.diff{padding:0;line-height:1.5}"
             "pre.diff .ln{display:block;padding:.05em 18px;white-space:pre-wrap}"
             "pre.diff .add{background:var(--add-bg);color:var(--add-fg)}"
             "pre.diff .del{background:var(--del-bg);color:var(--del-fg)}"
             "pre.diff .hunk{background:var(--code-bg);color:var(--fg-soft)}"
             "pre.diff .deleted{color:var(--del-fg);font-style:italic;opacity:.9}"
             ".reload{position:fixed;top:14px;right:14px;z-index:10;font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:6px 12px;opacity:.75}"
             ".reload:hover{opacity:1}"
             ".copy{position:fixed;top:52px;right:14px;z-index:10;font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:6px 12px;opacity:.75}"
             ".copy:hover{opacity:1}"
             ".terminal,.vi,.doc{position:fixed;right:14px;z-index:10;font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:6px 12px;opacity:.75}"
             ".terminal,.vi{top:90px}.doc{top:128px}"
             ".jump{position:fixed;right:14px;z-index:10;font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:6px 12px;opacity:.75}"
             ".jump:hover{opacity:1}.jump-top{top:90px}.jump-bottom{top:128px}"
             ".terminal:hover,.vi:hover,.doc:hover{opacity:1}.terminal:disabled,.vi:disabled{cursor:default}"
             ".mem-line{display:flex;align-items:center;gap:12px;margin:0 0 1.4em}"
             ".mem-badge{font:inherit;font-weight:700;font-size:15px;border-radius:8px;padding:4px 12px;color:#fff}"
             ".mem-ok{background:#2e9e44}.mem-warn{background:#d97706}.mem-bad{background:#d03030}"
             ".mem-unknown{background:#8b9490}"
             ".mem-detail{color:var(--fg-soft);font-size:.9em}"
             ".mem-refresh{font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:4px 10px}"
             ".mem-refresh:hover{opacity:.85}"
             ".crumbs{font-family:var(--mono);font-size:.9em;color:var(--fg-soft)}"
             ;; command dashboards
             ".pact{font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:3px 10px;margin-right:6px;text-decoration:none;display:inline-block}"
             ".pact:hover{opacity:.85}.pact:disabled{cursor:default}"
             ".pact.off,.pact:disabled{opacity:.45;cursor:default}"
             ".dot{font-size:1.05em;margin-right:.4em}"
             ".st-up{color:#2e9e44}.st-boot{color:#d97706}.st-crashed{color:#d03030}.st-down{color:var(--fg-soft)}"
             ;; One-shot commands use their own state names (ok/failed/running/
             ;; idle) rather than a server's up/down. Without these they matched no
             ;; rule and a finished job's ● came out in the default text colour —
             ;; i.e. black, which reads like "no status" rather than "succeeded".
             ".st-ok{color:#2e9e44}.st-running{color:#d97706}.st-failed{color:#d03030}.st-idle{color:var(--fg-soft)}"
             ".portchip{font-family:var(--mono);font-size:.72em;border:1px solid var(--line);border-radius:5px;padding:.02em .35em;margin-left:.3em;color:var(--fg-soft);white-space:nowrap}"
             "table.cmds{width:max-content}table.cmds td{vertical-align:middle}"
             ".cmd-note{font-size:.7em;font-weight:400;color:var(--fg-soft);margin-left:.6em}"
             ".cmd-auto{font-size:.85em;color:var(--fg-soft);margin-left:1em;user-select:none}"
             ".cmd-busy{cursor:progress}.cmd-busy .pact{pointer-events:none;opacity:.4}.dot.dot-busy{color:var(--fg-soft)}"
             ".remember-tabs,.tablet-btn{font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:6px 12px}"
             ".remember-tabs:hover,.tablet-btn:hover{opacity:.85}.remember-tabs:disabled,.tablet-btn:disabled{cursor:default}"]))

(defn escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- status-button
  "A button that loads a panel. onclick runs js; status-id names the panel's
   status element, whose class goes busy → ok/err while it loads (the memory
   badge's mem-detail does this). status-button-script, in every page's head, reads data-status
   and gives the press its feedback — '… Loading', then '✓ Updated' or
   '⚠ Failed', then the label — so a new panel button gets it by being made
   here. js must not contain a double quote. cls is the button's class
   (default \"pact\")."
  ([label js status-id] (status-button "pact" label js status-id))
  ([cls label js status-id]
   (str "<button class=\"" cls "\" data-status=\"" status-id "\" onclick=\"" js "\">" label "</button>")))

(defn- action-button
  "A fixed toolbar button that POSTs to url, showing active-label while working.
   With no done-label it navigates to whatever URL the endpoint returns (to
   refresh the view); with a done-label it stays put, flashes done-label, then
   restores. The &gt; entities decode to > inside the attribute so JS arrows parse."
  ([cls label active-label title url] (action-button cls label active-label title url nil))
  ([cls label active-label title url done-label]
   (let [on-ok (if done-label
                 (str ".then(()=&gt;{this.textContent='" done-label
                      "';setTimeout(()=&gt;{this.disabled=false;this.textContent='" label "'},1400)})")
                 (str ".then(u=&gt;{try{sessionStorage.setItem('mdserveScrollY',String(scrollY));"
                      "sessionStorage.setItem('mdserveToast','✓ Done — " (str/replace label "'" "\\'") "')}"
                      "catch(e){};location.href=u.trim()})"))]
     (str "<button class=\"" cls "\" title=\"" (escape-html title) "\" onclick=\""
          "this.disabled=true;this.textContent='" active-label "';"
          "fetch('" url "',{method:'POST'})"
          ".then(r=&gt;r.ok?r.text():Promise.reject())"
          on-ok
          ".catch(()=&gt;{this.disabled=false;this.textContent='⚠ Failed'})"
          "\">" label "</button>"))))

(defn- doc-button
  "Individual-file view, GREEN (:nav) only: turn this browsed file view into a
   document tab by reloading it as ?show=1 — grey dot, no promise of Back, and
   kept as the doc it is by Remember Tabs. Duplicating the tab can't do this:
   Chrome copies the URL byte-for-byte and the dot is decided by that URL alone
   (layout's :nav? = no show=1), so the copy comes back green too.

   Not an action-button: there is nothing for the server to do. The URL is
   finished at CLICK time from location.pathname, so no round trip and no path
   to re-encode. Shown docs don't get the button — they already are one."
  []
  (str "<button class=\"doc\" title=\""
       (escape-html "Make this a document tab — grey dot, kept as-is by Remember Tabs")
       "\" onclick=\"location.href=location.pathname+'?show=1'\">📄 Doc</button>"))

;; Directory view: open a terminal in this directory; stays on the page.
(defn- terminal-button [url]
  (action-button "terminal" "⌨ Terminal" "… Opening"
                 "Open a terminal in this directory" url "✓ Opened"))

;; Individual-file view: open a terminal editing this file in vi; stays on the page.
(defn- vi-button [url]
  (action-button "vi" "📝 vi" "… Opening"
                 "Open a terminal and edit this file in vi" url "✓ Opened"))

(def dot-colours
  "Filled favicon dots, by what the tab IS. :doc is deliberately absent — it
   falls through to the hollow grey outline, so unmarked shown docs stay quiet
   and only the meaningful categories carry colour.
     :nav     green   — a directory listing you're browsing (the Explorer)
     :dev     blue    — a project's command dashboard
     :tmp     pink    — in a tmp dir: could vanish
     :archive yellow  — parked in an archive/ dir
     :log     black   — a command's log, not a document at all"
  {:nav     "%233fb950"
   :dev     "%232f81f7"
   :tmp     "%23f778ba"
   :archive "%23e3b341"
   :log     "%23000000"})

(defn- dot-favicon
  "Inline-SVG favicon so the tab itself carries the dot — no page CSS involved."
  [kind]
  (if-let [c (dot-colours kind)]
    (str "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='6' fill='" c "'/%3E%3C/svg%3E\">")
    "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='5' fill='none' stroke='%23888888' stroke-width='2'/%3E%3C/svg%3E\">"))

(defn- path-dot
  "Dot for a viewed path, or nil to keep the default green-nav/grey-doc pair.
   Any tmp dir anywhere counts as pink, not just /tmp at the root — a project's
   own tmp/ is just as disposable. Volatility wins: a file in a tmp dir reads
   pink even inside an archive dir, because it could vanish either way. Matched
   on whole path segments, so notes-tmp.md and tmpl/ don't false-positive."
  [path]
  (cond
    (re-find #"/tmp(/|$)" path)     :tmp
    (re-find #"/archive(/|$)" path) :archive))

;; extra: pre-rendered extra-button HTML (archive on file views, flotsam on dirs).
(def scroll-restore-script
  "Put the page back where it was after an action button re-rendered it.

   Every action-button POSTs and then assigns `location.href`, which is a fresh
   navigation even when the URL is the page you are already on - so the browser lands you
   at the top and you scroll down again. The button stashes `scrollY` immediately before
   navigating and this restores it on the way back in.

   Read-and-delete, so it is single-use: only the navigation that just stashed a position
   consumes it. Arriving fresh - a bookmark, a new tab - finds no key and does nothing,
   rather than dropping you at wherever you last were in a different session."
  (str "<script>addEventListener('DOMContentLoaded',function(){"
       "try{var y=sessionStorage.getItem('mdserveScrollY');"
       "if(y!==null){sessionStorage.removeItem('mdserveScrollY');scrollTo(0,parseInt(y,10));}}"
       "catch(e){}});</script>"))

(def toast-script
  "A single-use '✓ done' note that survives a reload, for buttons that re-render
   by navigating (↻ Refresh, action-button, cmdGo): the button itself is
   gone with the old page, so the press leaves a message under `mdserveToast` and
   the new page shows it for a moment. Read-and-delete like
   scroll-restore-script, and its own key so the two never touch each other.
   Only presses write it — cmdPoll's auto-refreshes don't.
   ↻ Refresh also stores a fingerprint of the page text (mdservePageFp) under
   `mdserveRefreshFp`; if the reloaded page's text is the same, the note becomes a
   grey 'Already up to date' instead of the green '✓ Refreshed'."
  (str "<script>function mdservePageFp(){var t=(document.querySelector('main')||document.body).innerText,h=5381;"
       "for(var i=0;i<t.length;i++){h=((h<<5)+h+t.charCodeAt(i))|0}return t.length+':'+h}"
       "addEventListener('DOMContentLoaded',function(){"
       "try{var m=sessionStorage.getItem('mdserveToast'),bg='#2e9e44';"
       "var fp=sessionStorage.getItem('mdserveRefreshFp');"
       "if(fp!==null){sessionStorage.removeItem('mdserveRefreshFp');"
       "if(m!==null&&fp===mdservePageFp()){m='Already up to date';bg='#6b7280'}}"
       "if(m!==null){sessionStorage.removeItem('mdserveToast');"
       "var d=document.createElement('div');d.textContent=m;"
       "d.style.cssText='position:fixed;left:50%;bottom:24px;transform:translateX(-50%);z-index:99999;"
       "background:'+bg+';color:#fff;font:600 14px system-ui,sans-serif;padding:8px 16px;border-radius:8px;"
       "box-shadow:0 2px 8px rgba(0,0,0,.3);transition:opacity .4s';"
       "document.body.appendChild(d);"
       "setTimeout(function(){d.style.opacity='0'},1400);setTimeout(function(){d.remove()},1900);}}"
       "catch(e){}});</script>"))

(def status-button-script
  "Feedback for every status-button: a capture-phase click listener finds the
   button's data-status element and watches its class go busy → ok/err.
   Changes only the button's own text, never the page."
  (str "<script>addEventListener('click',function(ev){"
       "var b=ev.target.closest&&ev.target.closest('button[data-status]');if(!b||b.dataset.fb)return;"
       "var st=document.getElementById(b.dataset.status);if(!st)return;"
       "var lbl=b.textContent,seen=false;b.dataset.fb='1';b.textContent='… Loading';"
       "var end=function(t){mo.disconnect();clearTimeout(to);b.textContent=t;"
       "setTimeout(function(){b.textContent=lbl;delete b.dataset.fb;},1400);};"
       "var mo=new MutationObserver(function(){var c=st.className;"
       "if(/\\bbusy\\b/.test(c)){seen=true;return;}"
       "if(seen)end(/\\berr\\b/.test(c)?'⚠ Failed':'✓ Updated');});"
       "mo.observe(st,{attributes:true,attributeFilter:['class']});"
       "var to=setTimeout(function(){mo.disconnect();b.textContent=lbl;delete b.dataset.fb;},30000);"
       "},true);</script>"))

(def heading-nav-script
  "PageDown/PageUp move heading-to-heading, landing each one just under the top
   of the viewport — the structural move a scrollbar can't make. The ARROWS are
   deliberately left alone: they're the reading keys. Paging is the keypress
   worth stealing, since a native screenful lands mid-paragraph and you lose
   your place; space/shift-space still page raw if you want that. It bows out
   rather than preventDefault-ing whenever there is nowhere to jump (no headings
   on the page, or already at the last/first one), so on a log tail, or past the
   final heading, paging stays native. The 20/4px thresholds sit either side of
   the 12px landing offset, so a heading can't count as its own next target."
  (str "<script>addEventListener('keydown',function(e){"
       "if(e.metaKey||e.ctrlKey||e.altKey||e.shiftKey)return;"
       "var t=e.target,tag=t&&t.tagName;"
       "if(tag==='INPUT'||tag==='TEXTAREA'||tag==='SELECT'||(t&&t.isContentEditable))return;"
       "var down=(e.key==='PageDown'),up=(e.key==='PageUp');"
       "if(!down&&!up)return;"
       "var hs=[].slice.call(document.querySelectorAll('main.md h1,main.md h2,main.md h3'));"
       "if(!hs.length)return;"
       "var to=down?hs.filter(function(h){return h.getBoundingClientRect().top>20;})[0]"
       ":hs.filter(function(h){return h.getBoundingClientRect().top<4;}).pop();"
       "if(!to)return;"
       "e.preventDefault();"
       "scrollTo(0,scrollY+to.getBoundingClientRect().top-12);"
       "});</script>"))

(defn layout
  ([title inner] (layout title inner nil nil))
  ([title inner extra] (layout title inner extra nil))
  ([title inner extra opts]
   (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        ;; Favicon dot marks the tab: green filled circle on the navigation tab
        ;; (browsed, not /show), hollow grey circle on shown-doc tabs. An explicit
        ;; :dot in opts overrides that pair — :dev (blue) for a project's dev-setup
        ;; dashboard, :tmp (pink) for a doc that will vanish under /tmp.
        (dot-favicon (or (:dot opts) (if (:nav? opts) :nav :doc)))
        "<title>" (escape-html title) "</title><style>" css "</style>"
        ;; Syntax highlighting for fenced code (```clojure etc.) via highlight.js.
        ;; Light/dark themes track prefers-color-scheme; hljs bg neutralised so the
        ;; page's own --pre-bg shows and hljs only contributes token colours.
        "<link rel=\"stylesheet\" media=\"(prefers-color-scheme:light)\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css\">"
        "<link rel=\"stylesheet\" media=\"(prefers-color-scheme:dark)\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css\">"
        "<style>pre code.hljs{background:transparent!important;padding:0}</style>"
        "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js\"></script>"
        "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/clojure.min.js\"></script>"
        "<script>addEventListener('DOMContentLoaded',function(){document.querySelectorAll('pre code[class]').forEach(function(e){try{hljs.highlightElement(e)}catch(x){}})});</script>"
        heading-nav-script
        scroll-restore-script
        toast-script
        status-button-script
        "</head>"
        "<body" (when (:nav? opts) " class=\"nav\"") ">"
        (when-not (:no-toolbar opts)
          (str "<button class=\"reload\" onclick=\"try{sessionStorage.setItem('mdserveRefreshFp',mdservePageFp());sessionStorage.setItem('mdserveToast','✓ Refreshed')}catch(e){};this.textContent='… Refreshing';location.reload()\" title=\"Reload this page\">↻ Refresh</button>"
               "<button class=\"copy\" onclick=\"navigator.clipboard.writeText(document.querySelector('" (or (:copy-selector opts) "main.md") "').innerText).then(()=>{this.textContent='✓ Copied';setTimeout(()=>{this.textContent='⧉ Copy'},1200)})\" title=\"Copy " (if (:copy-selector opts) "the log lines" "page text") " to clipboard\">⧉ Copy</button>"
               (when (= :log (:dot opts))
                 (str "<button class=\"jump jump-top\" onclick=\"scrollTo(0,0);var b=this;b.textContent='✓ Top';setTimeout(function(){b.textContent='⤒ Top'},700)\" title=\"Jump to the start of the log\">⤒ Top</button>"
                      "<button class=\"jump jump-bottom\" onclick=\"scrollTo(0,document.body.scrollHeight);var b=this;b.textContent='✓ Bottom';setTimeout(function(){b.textContent='⤓ Bottom'},700)\" title=\"Jump to the end of the log\">⤓ Bottom</button>"))))
        (or extra "")
        "<main class=\"md\">" inner "</main></body></html>")))

(defn suffix [uri prefix]
  (URLDecoder/decode (subs uri (count prefix)) "UTF-8"))

(defn doc-title [path body]
  (or (some-> (re-find #"(?m)^#\s+(.+?)\s*$" body) second not-empty)
      (.getName (io/file path))))

(defn- mac-mem-stats
  "mem-stats on macOS, which has no /proc: total from sysctl hw.memsize, available
   from vm_stat as free + inactive + speculative pages (memory a new process could
   claim), swap from sysctl vm.swapusage. A figure that cannot be read is nil."
  []
  (try
    (let [vm    (:out (p/sh "vm_stat"))
          page  (some-> (re-find #"page size of (\d+) bytes" vm) second parse-long)
          pages (fn [k] (or (some-> (re-find (re-pattern (str "Pages " k ":\\s+(\\d+)")) vm) second parse-long) 0))
          total (some-> (:out (p/sh "sysctl" "-n" "hw.memsize")) str/trim parse-long)
          avail (when page (* page (+ (pages "free") (pages "inactive") (pages "speculative"))))
          swap  (:out (p/sh "sysctl" "-n" "vm.swapusage"))
          mib   (fn [k] (some-> (re-find (re-pattern (str k " = ([\\d.]+)M")) swap) second parse-double (* 1048576) long))]
      {:total total
       :available avail
       :used (when (and total avail) (- total avail))
       :used-pct (when (and total avail (pos? total))
                   (* 100.0 (/ (double (- total avail)) total)))
       :swap-total (mib "total")
       :swap-used (mib "used")})
    (catch Exception _ {})))

(defn mem-stats
  "Machine memory from /proc/meminfo, in bytes plus a used percentage.

   Used is derived from MemAvailable, NOT from MemFree: Linux fills nearly all
   spare RAM with page cache, so MemFree on a healthy machine reads near zero
   and would show a permanent scary red. MemAvailable is the kernel's own
   estimate of what a new process could actually claim — the number that
   answers 'can I start another dev server'.

   Read via Files/readAllBytes rather than slurp: /proc files report a size of
   0, and every reader-based path in babashka — slurp, slurp+io/reader,
   line-seq — dies on that with 'IOException: Invalid argument'.

   macOS has no /proc: mac-mem-stats gives the same keys there."
  []
  (if (.exists (io/file "/proc/meminfo"))
    (let [kv (into {} (for [l (str/split-lines
                               (String. (java.nio.file.Files/readAllBytes
                                         (.toPath (io/file "/proc/meminfo")))))
                            :let [[_ k v] (re-matches #"([^:]+):\s+(\d+) kB" l)]
                            :when k]
                        [k (* 1024 (parse-long v))]))
          total (kv "MemTotal")
          avail (kv "MemAvailable")
          sw-t  (kv "SwapTotal")
          sw-f  (kv "SwapFree")]
      {:total total
       :available avail
       :used (when (and total avail) (- total avail))
       :used-pct (when (and total avail (pos? total))
                   (* 100.0 (/ (double (- total avail)) total)))
       :swap-total sw-t
       :swap-used (when (and sw-t sw-f) (- sw-t sw-f))})
    (mac-mem-stats)))

(defn- proc-read
  "Contents of a /proc file, or nil. Files/readAllBytes rather than slurp for
   the reason mem-stats documents, and nil rather than a throw because a pid
   can exit between listing /proc and reading it — one vanished Chrome tab must
   not 500 the whole route."
  [path]
  (try (String. (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
       (catch Exception _ nil)))

(defn- proc-pss
  "A process's proportional set size in bytes: its private memory plus its
   share of everything it maps in common with others. The honest number here —
   Chrome and Firefox run dozens of processes over shared mappings, so summing
   RSS per group would count the same pages many times over and report far more
   than the machine has. Falls back to VmRSS (marked approximate by the caller)
   for the handful of processes whose smaps_rollup will not open."
  [pid]
  (if-let [pss (some-> (proc-read (str "/proc/" pid "/smaps_rollup"))
                       (->> (re-find #"(?m)^Pss:\s+(\d+) kB"))
                       second parse-long)]
    [(* 1024 pss) false]
    (when-let [rss (some-> (proc-read (str "/proc/" pid "/status"))
                           (->> (re-find #"(?m)^VmRSS:\s+(\d+) kB"))
                           second parse-long)]
      [(* 1024 rss) true])))

(def state-dir
  "Per-command state: <group>/<id>.pid, .log and .exit. Override with
   :state-dir in config.edn or MDSERVE_STATE."
  (or (some-> (System/getenv "MDSERVE_STATE") expand-home)
      (some-> (:state-dir config) expand-home)
      (str home "/.local/state/mdserve-starter")))

(defn- registry-pids
  "pid → \"group/id\", from the pidfiles the command dashboards write. The most
   specific attribution available, so it is consulted first."
  []
  (into {}
        (for [g (some-> (io/file state-dir) .listFiles)
              :when (.isDirectory g)
              f (.listFiles g)
              :when (str/ends-with? (.getName f) ".pid")
              :let [pid (some-> (proc-read (.getPath f)) str/trim parse-long)]
              :when pid]
          [pid (str (.getName g) "/" (str/replace (.getName f) ".pid" ""))])))

(defn- mem-group-of
  "Which bucket a process counts against, most specific match first. cmdline is
   NUL-delimited in /proc, so it is split before matching — regexes run against
   the joined form would only ever see the executable."
  [pid cmdline reg]
  (let [c (str/join " " (remove str/blank? (str/split (or cmdline "") #"\u0000")))]
    (cond
      (reg pid)                                   (str "cmd: " (reg pid))
      (str/includes? c (str "--user-data-dir=" profile)) "Chrome (mdserve)"
      (str/includes? c "chrome")                  "Chrome (default)"
      (str/includes? c "firefox")                 "Firefox"
      (re-find #"(^|/)claude(\s|$)" c)            "Claude Code"
      :else
      (or (some (fn [root]
                  (when (str/includes? c root) (str "proj: " (.getName (io/file root)))))
                dev-projects)
          (some-> (proc-read (str "/proc/" pid "/comm")) str/trim)
          "unknown"))))

(defn mem-groups
  "Memory grouped by what is using it, biggest first. One pass over /proc, one
   smaps_rollup read each — the kernel computes PSS on open, so this costs a few
   hundred milliseconds. That is why it rides on the manual Refresh and nothing
   polls it."
  []
  (let [reg (registry-pids)]
    (->> (for [d (some-> (io/file "/proc") (.listFiles))
               :let [n (.getName d)]
               :when (re-matches #"\d+" n)
               :let [pid (parse-long n)
                     [bytes approx?] (proc-pss pid)]
               :when bytes]
           {:group (mem-group-of pid (proc-read (str "/proc/" pid "/cmdline")) reg)
            :bytes bytes :approx approx?})
         (group-by :group)
         (map (fn [[g rows]]
                {:group g
                 :bytes (reduce + (map :bytes rows))
                 :procs (count rows)
                 :approx (boolean (some :approx rows))}))
         (sort-by :bytes >)
         (take 10)
         vec)))

(defn mem-route
  "GET /mem — the memory badge's data, plus the per-user breakdown behind it.
   Read on request only; nothing polls.

   The groups sum to LESS than :used — they cover this user's processes, PSS
   rather than RSS, and exclude the kernel, tmpfs and other users. It is a
   ranking of what to close, not a reconciliation of the total."
  []
  (let [t0 (System/currentTimeMillis)
        groups (mem-groups)]
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (json/generate-string (assoc (mem-stats)
                                        :ok true
                                        :groups groups
                                        :scan-ms (- (System/currentTimeMillis) t0)))}))

(def mem-section
  "The memory badge, at the very top of the panel: the whole point of keeping
   several projects' dev servers alive is knowing when to stop. A percentage, a
   colour, and a manual Refresh — no polling, per the same rule the dashboards
   follow."
  (str "<p class=\"mem-line\">"
       "<span id=\"mem-badge\" class=\"mem-badge mem-unknown\">MEM —</span>"
       (status-button "pact mem-refresh" "↻ Refresh" "memLoad()" "mem-detail")
       "<span id=\"mem-detail\" class=\"mem-detail\">reading…</span>"
       "</p>"
       "<table id=\"mem-groups\" style=\"display:none\"><tbody id=\"mem-rows\"></tbody></table>"
       "<p id=\"mem-foot\" class=\"mem-detail\" style=\"display:none\"></p>"
       "<script>"
       "function memGib(b){return (b/1073741824).toFixed(1);}"
       "function memLoad(){"
       "var d=document.getElementById('mem-detail');d.textContent='reading…';d.className='mem-detail busy';"
       "fetch('/mem').then(function(r){return r.json();}).then(function(m){"
       "var p=m['used-pct'];"
       ;; 70/85: below 70 there is room for another build, 70-85 is 'finish what
       ;; you are doing', above 85 the next JVM is what starts the swapping.
       "var cls=(p==null)?'mem-unknown':((p<70)?'mem-ok':((p<85)?'mem-warn':'mem-bad'));"
       "var b=document.getElementById('mem-badge');"
       "b.className='mem-badge '+cls;"
       "b.textContent='MEM '+((p==null)?'—':Math.round(p)+'%');"
       "d.className='mem-detail ok';d.textContent=memGib(m.used)+' / '+memGib(m.total)+' GiB used'"
       "+((m['swap-used']>0)?('  ·  swap '+memGib(m['swap-used'])+' GiB'):'')"
       "+'  ·  '+memGib(m.available)+' GiB available';"
       "var g=m.groups||[];"
       "document.getElementById('mem-rows').innerHTML=g.map(function(x){"
       "return '<tr><td>'+x.group+(x.approx?' *':'')+'</td><td class=\"mem-num\">'"
       "+memGib(x.bytes)+' GiB</td><td class=\"mem-num mem-detail\">'+x.procs+'</td></tr>';"
       "}).join('');"
       "document.getElementById('mem-groups').style.display=g.length?'':'none';"
       "var f=document.getElementById('mem-foot');"
       "f.style.display=g.length?'':'none';"
       "f.textContent='PSS per group (shared pages counted once), your processes only —"
       " so these sum to less than the used figure. Scanned in '+m['scan-ms']+' ms.';"
       "}).catch(function(e){d.className='mem-detail err';d.textContent='failed: '+e;});}"
       "memLoad();"
       "</script>"))

(def source-file
  "mdserve.clj itself, as babashka was given it."
  (io/file (System/getProperty "babashka.file")))

(def version-time-format
  (java.time.format.DateTimeFormatter/ofPattern "EEE d MMM HH:mm"))

(defn file-version
  "mdserve.clj as it is on disk now: a short SHA-256 of its bytes and when it was
   last written. Compared with loaded-version, it says whether the running server
   is the code on disk."
  []
  {:hash    (subs (format "%064x" (BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                                          (java.nio.file.Files/readAllBytes (.toPath source-file)))))
                  0 8)
   :written (.format version-time-format
                     (java.time.LocalDateTime/ofInstant
                      (java.time.Instant/ofEpochMilli (.lastModified source-file))
                      (java.time.ZoneId/systemDefault)))})

(def loaded-version
  "The file this server started from, fixed at startup. Several sessions edit
   mdserve and a change is inert until a restart, so 'is the running one the
   code on disk?' is a question worth answering without detective work."
  (assoc (file-version) :started (.format version-time-format (java.time.LocalDateTime/now))))

(defn version-route
  "GET /version - which mdserve.clj this server is running, and whether the file
   on disk has changed since. :stale? true means a restart would load other code."
  []
  (let [now (file-version)]
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:loaded loaded-version
                                  :on-disk now
                                  :stale? (not= (:hash loaded-version) (:hash now))})}))

(def version-section
  "Which code is running, under the memory badge. The line is fixed at startup;
   the warning is filled in from /version each time the panel loads."
  (str "<p class=\"mem-line\"><span class=\"mem-detail\">mdserve " (:hash loaded-version)
       " · file written " (:written loaded-version)
       " · running since " (:started loaded-version) "</span>"
       " <span id=\"version-stale\" class=\"mem-detail err\" style=\"display:none\"></span></p>"
       "<script>"
       "fetch('/version').then(function(r){return r.json();}).then(function(v){"
       "if(v['stale?']){var s=document.getElementById('version-stale');"
       "s.textContent='⚠ mdserve.clj has changed since this started (now '+v['on-disk'].hash"
       "+', written '+v['on-disk'].written+') - restart mdserve to load it.';"
       "s.style.display='';}});"
       "</script>"))

(defn text [status body]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})

(defn open-url! [url]
  (let [cmd (if opener-str
              (str/split opener-str #"\s+")
              (into browser-cmd [(str "--user-data-dir=" profile)
                                 "--no-first-run" "--no-default-browser-check"]))]
    (try
      (p/process (conj (vec cmd) url)
                 {:out (io/file "/dev/null") :err (io/file "/dev/null")})
      (catch Exception _ nil))
    nil))

(defn open-url-with-debug-port!
  "open-url! with --remote-debugging-port. Chrome only honours that flag on the
   process that STARTS the profile's browser instance — later invocations just
   message the running one — so only the first open of a startup uses this.
   Falls back to open-url! under MDSERVE_OPENER or when
   devtools-port is 0."
  [url]
  (if (or opener-str (zero? devtools-port))
    (open-url! url)
    (do
      (try
        (p/process (conj (into browser-cmd
                               [(str "--user-data-dir=" profile)
                                "--no-first-run" "--no-default-browser-check"
                                (str "--remote-debugging-port=" devtools-port)])
                         url)
                   {:out (io/file "/dev/null") :err (io/file "/dev/null")})
        (catch Exception _ nil))
      nil)))

(defn page-targets
  "Open page targets from the dedicated browser's DevTools endpoint, full records
   (:id :url :title :webSocketDebuggerUrl). nil when nothing is listening on
   devtools-port — i.e. the browser was launched without the flag."
  []
  (try
    (->> (json/parse-string (slurp (str "http://localhost:" devtools-port "/json/list")) true)
         (filter #(= "page" (:type %))))
    (catch Exception _ nil)))

(defn live-tabs
  "The open tabs as {:url :title}, in the order DevTools reports them — which
   tracks activation, not left-to-right tab position."
  []
  (some->> (page-targets)
           (remove #(str/starts-with? (or (:url %) "") "devtools://"))
           (mapv #(select-keys % [:url :title]))))

(defn url->tab
  "mdserve's own URLs become port-independent {:path ...} so changing
   MDSERVE_PORT doesn't strand the file; anything else keeps its absolute :url."
  [{:keys [url title]}]
  (if (str/starts-with? url base)
    {:path (let [p (subs url (count base))]
             (if (str/blank? p) "/" p))}
    {:url url :title title}))

(defn tab->url [{:keys [path url]}]
  (or url (str base path)))

(defn- tab-fs-path
  "The filesystem path a /view tab points at, or nil for anything else."
  [{:keys [path]}]
  (when (and path (str/starts-with? path "/view/"))
    (URLDecoder/decode (str/replace (subs path (count "/view")) #"\?.*$" "")
                       "UTF-8")))

(defn tab-gone?
  "True for a /view tab whose file or directory no longer exists — replaying it
   would open mdserve's bare 404. Scratchpad docs under /tmp are the usual case:
   systemd-tmpfiles sweeps /tmp at 10 days, so a remembered set outlives them."
  [t]
  (boolean (some-> (tab-fs-path t) io/file .exists not)))

(def tab-order
  "Replay order, left to right: the panel, the blue command dashboard, the
   browsed listing, then logs, plain docs, tmp, archive, and anything external
   last. A workspace's Sessions tab (:sessions) comes straight after the
   dashboard, or after the panel in the panel's window. :nav sits right after
   them on purpose — in a project window that puts the green Explorer in the
   same place every time."
  [:panel :dev :sessions :nav :log :doc :tmp :archive :external])

(defn- tab-category
  "Which tab-order bucket a remembered tab falls in. Uses the same path-dot
   precedence as the favicon, so a tab's position matches the dot you see on it
   — tmp before archive, and a /view without ?show=1 is a browsed listing."
  [{:keys [path] :as t}]
  (let [route (str/replace (or path "") #"\?.*$" "")]
    (cond
      (nil? path)                           :external
      (= "/" route)                         :panel
      (re-matches #"/sessions/\d+" route)  :sessions
      (re-find #"^/commands/[^/]+/log/" route) :log
      (re-find #"^/commands/" route)        :dev
      :else (or (some-> (tab-fs-path t) path-dot)
                (if (str/includes? (or path "") "show=1") :doc :nav)))))

(defn sort-tabs
  "Order tabs by tab-order. Stable, so hand-picked order within a category is
   preserved — only the grouping is imposed."
  [tabs]
  (let [rank (zipmap tab-order (range))]
    (vec (sort-by #(rank (tab-category %) (count tab-order)) tabs))))

(defn- encoded-project
  "The project a path belongs to by ENCODING rather than by location, or nil.
   Claude Code keeps its scratchpads under
   /tmp/claude-<uid>/-home-you-code-<project>/<session>/scratchpad/, so a doc an
   agent writes there names its project in the path. Matched in encoded form
   (slashes as dashes) against known roots — decoding by replacing - with /
   would split my-app into my/app."
  [path]
  (some (fn [root]
          (when (str/includes? path (str/replace root "/" "-")) root))
        dev-projects))

(defn file-project
  "The project root a filesystem path belongs to: living under the root, or an
   agent scratchpad path that encodes it. nil for anything else."
  [path]
  (or (some (fn [root]
              (when (or (= path root) (str/starts-with? (str path) (str root "/"))) root))
            dev-projects)
      (encoded-project (str path))))

(defn tab-project
  "Which project root a tab belongs to, or nil when it belongs to none — the
   panel, a listing outside every project, an external site. NOT the question
   tab-category answers: that buckets by dot, so a project's dashboard and one
   of its docs share a project but not a category. Three signals:
     0. a Sessions tab             /sessions/<n>: the project whose :workspace is n
     1. the mdserve route          /commands/<name> and its /log/ pages
     2. the path a /view tab shows (file-project)"
  [{:keys [path] :as t}]
  (let [route (str/replace (or path "") #"\?.*$" "")]
    (or (some-> (re-matches #"/sessions/(\d+)" route) second parse-long workspace-project)
        (some-> (re-find #"^/commands/([^/]+)" route) second
                (URLDecoder/decode "UTF-8") project-by-name :dir)
        (some-> (tab-fs-path t) file-project))))

(defn read-tabs
  "The remembered :tabs vector with vanished /view targets dropped (reported on
   the console, not silently), or nil when there's no readable file."
  []
  (let [f (io/file tabs-file)]
    (when (.isFile f)
      (try
        (let [tabs (:tabs (edn/read-string (slurp f)))
              gone (filter tab-gone? tabs)]
          (doseq [t gone]
            (println (str "  skipping vanished tab: " (:path t))))
          (seq (remove tab-gone? tabs)))
        (catch Exception e
          (println (str "  tabs file unreadable (" tabs-file "): " (.getMessage e)))
          nil)))))

(def entry-item-re
  #"(?s)^\s*(<code>.*?</code>)\s*[—–-]\s*(.+?)\s*$")

(defn blockify-entries
  "Render markdown list items of the form `term` — description as block entries
   (term on its own line, description beneath, no bullet). Only converts a <ul>
   when EVERY item matches; other lists are left untouched."
  [html]
  (str/replace html #"(?s)<ul>(.*?)</ul>"
               (fn [[whole inner]]
                 (let [items (re-seq #"(?s)<li>(.*?)</li>" inner)]
                   (if (and (seq items)
                            (every? (fn [[_ c]] (re-find entry-item-re c)) items))
                     (str "<ul class=\"entries\">"
                          (apply str
                                 (for [[_ c] items]
                                   (let [[_ term desc] (re-find entry-item-re c)]
                                     (str "<li class=\"entry\"><span class=\"term\">" term
                                          "</span><span class=\"desc\">" desc "</span></li>"))))
                          "</ul>")
                     whole)))))

(defn- meta-line? [l]
  (some #(str/starts-with? l %)
        ["index " "old mode " "new mode " "deleted file mode " "new file mode "
         "similarity " "rename " "copy " "--- " "+++ "]))

(defn- diff-body-line? [l]
  (or (meta-line? l)
      (str/starts-with? l "@@")
      (and (seq l) (contains? #{\+ \- \space \\} (first l)))))

(defn diff-lines
  "Escaped diff text -> [class text] pairs. Collapse each git deleted-file block
   to a single summary line, and colour +/- only inside @@ hunks (so report text
   and headers that happen to start with +/- aren't tinted)."
  [escaped]
  (let [v (vec (str/split-lines escaped)) n (count v)]
    (loop [i 0 in-hunk false out []]
      (if (>= i n)
        out
        (let [line (nth v i)
              gm (re-matches #"diff --git a/(.*?) b/.*" line)]
          (cond
            (and gm (< (inc i) n) (str/starts-with? (nth v (inc i)) "deleted file mode"))
            (let [j (loop [k (inc i)]
                      (if (and (< k n) (diff-body-line? (nth v k))) (recur (inc k)) k))]
              (recur j false (conj out ["ln deleted" (str (second gm) " — deleted")])))

            gm                              (recur (inc i) false   (conj out ["ln hunk" line]))
            (str/starts-with? line "@@")    (recur (inc i) true    (conj out ["ln hunk" line]))
            (meta-line? line)               (recur (inc i) in-hunk (conj out ["ln hunk" line]))
            ;; A blank line does NOT end a hunk: an unchanged EMPTY context line
            ;; arrives as a single space, which str/blank? calls blank — so
            ;; resetting here switched colouring off for the rest of that hunk,
            ;; and which block looked coloured depended on where its first empty
            ;; context line happened to fall. diff --git and @@ handle the real
            ;; transitions.
            (str/blank? line)               (recur (inc i) in-hunk (conj out ["ln" line]))
            (and in-hunk (str/starts-with? line "+")) (recur (inc i) in-hunk (conj out ["ln add" line]))
            (and in-hunk (str/starts-with? line "-")) (recur (inc i) in-hunk (conj out ["ln del" line]))
            :else                           (recur (inc i) in-hunk (conj out ["ln" line]))))))))

(defn diff->html
  "Render escaped diff text as a colourised <pre.diff> block."
  [escaped]
  (str "<pre class=\"diff\"><code>"
       (str/join
        (for [[cls text] (diff-lines escaped)]
          (str "<span class=\"" cls "\">" text "</span>")))
       "</code></pre>"))

(defn colorize-diff
  "Colour diff fenced blocks in rendered markdown HTML. Matches ```diff and the
   4-backtick ````diff fence (markdown-clj tags the latter class=\"`diff\"), which
   resists an inner ``` in a diffed markdown file. Other code blocks untouched."
  [html]
  (str/replace html #"(?s)<pre><code class=\"`*diff\">(.*?)</code></pre>"
               (fn [[_ body]] (diff->html body))))

(defn format-stacktrace
  "Format Java/Clojure stacktraces with syntax highlighting. Returns HTML."
  [text]
  (let [exception-css "<style>.log-container{font-family:var(--mono);font-size:14px;line-height:1.6;background:var(--pre-bg);padding:16px;border-radius:8px}.log-line{margin:0;padding:2px 0;white-space:pre-wrap;word-break:break-word}.exc-class{color:#f48771;font-weight:bold}.exc-msg{color:#dcdcaa}.stack-at{color:#808080}.stack-method{color:#9cdcfe}.stack-loc{color:#6a9955}.log-data{color:#ce9178}.log-meta{color:#608b4e;font-style:italic}.log-warn{color:#d19a66}.log-error{color:#e06c75;font-weight:600}</style>"
        format-line (fn [line]
                      (cond
                        ;; Exception header: ExceptionClass: message
                        (re-matches #"^([a-zA-Z0-9_.$]+(?:Exception|Error|Info|Throwable)): (.+)$" line)
                        (let [[_ exc-class message] (re-matches #"^([a-zA-Z0-9_.$]+(?:Exception|Error|Info|Throwable)): (.+)$" line)]
                          (str "<div class=\"log-line\"><span class=\"exc-class\">" (escape-html exc-class) "</span>: <span class=\"exc-msg\">" (escape-html message) "</span></div>"))

                        ;; Stack frame: \tat method(location)
                        (re-matches #"^\s+at (.+)\((.+)\)$" line)
                        (let [[_ method location] (re-matches #"^\s+at (.+)\((.+)\)$" line)]
                          (str "<div class=\"log-line\"><span class=\"stack-at\">    at</span> <span class=\"stack-method\">" (escape-html method) "</span><span class=\"stack-loc\">(" (escape-html location) ")</span></div>"))

                        ;; ERROR/WARN lines
                        (str/includes? line " ERROR ")
                        (str "<div class=\"log-line log-error\">" (escape-html line) "</div>")

                        (str/includes? line " WARN ")
                        (str "<div class=\"log-line log-warn\">" (escape-html line) "</div>")

                        ;; Timestamp/worker metadata
                        (re-matches #"^[A-Z][a-z]{2} [A-Z][a-z]{2} \d{2} .*|^\[worker-\d+\].*" line)
                        (str "<div class=\"log-line log-meta\">" (escape-html line) "</div>")

                        ;; Data lines (contains keywords or maps)
                        (or (re-find #":[a-z\-/]+" line) (str/includes? line "{"))
                        (str "<div class=\"log-line log-data\">" (escape-html line) "</div>")

                        ;; Everything else
                        :else
                        (str "<div class=\"log-line\">" (escape-html line) "</div>")))
        lines (str/split-lines text)]
    (str exception-css "<div class=\"log-container\">\n"
         (str/join "\n" (map format-line lines))
         "\n</div>")))

(def table-separator-re
  ;; Second line of a GitHub table: |---|---| with optional alignment colons.
  #"\|(?:\s*:?-+:?\s*\|)+\s*")

(defn- indented-table-line?
  "A line indented 1-3 spaces whose content starts with |: a table row inside a
   list item. 4+ spaces is an indented code block, not a table."
  [line]
  (some? (re-matches #" {1,3}\|.*" line)))

(defn dedent-indented-tables
  "Shift GitHub tables that sit indented inside a list item back to column 0,
   for the table lint only, which looks for rows at column 0. Rendering no longer
   needs it: markdown-clj 1.12.10 parses indented tables itself (1.12.1 turned
   them into a paragraph of pipes, and this used to run before every render).
   Scope is deliberately tight — a run of consecutive 1-3-space-indented
   |-lines whose second line is a |---|---| separator, outside any ``` / ~~~
   fence. 4-space-indented code blocks and pipe-ish prose are untouched."
  [body]
  (let [lines (str/split-lines body)
        n     (count lines)]
    (loop [i 0 in-fence? false out []]
      (if (>= i n)
        ;; split-lines drops a trailing newline; put one back so a table (or
        ;; anything else) on the last line still ends in \n for the parser.
        (str (str/join "\n" out) "\n")
        (let [line (nth lines i)]
          (cond
            ;; Track fences so an indented pipe-drawing inside a code sample
            ;; is never mistaken for a table. Opening and closing fences both
            ;; match; a bare toggle is enough for that.
            (re-matches #"\s*(```|~~~).*" line)
            (recur (inc i) (not in-fence?) (conj out line))

            in-fence?
            (recur (inc i) in-fence? (conj out line))

            ;; Start of an indented table: this line and the next are both
            ;; 1-3-space-indented |-lines and the next is the separator row.
            (and (indented-table-line? line)
                 (< (inc i) n)
                 (indented-table-line? (nth lines (inc i)))
                 (re-matches table-separator-re (str/triml (nth lines (inc i)))))
            (let [j (loop [k i]                  ; end of the run of table lines
                      (if (and (< k n) (indented-table-line? (nth lines k)))
                        (recur (inc k)) k))]
              (recur j in-fence?
                     (into out (map str/triml (subvec (vec lines) i j)))))

            :else
            (recur (inc i) in-fence? (conj out line))))))))

(defn- mask-for-emphasis-scan
  "Same-length copy of chunk S with everything the emphasis scan must not see
   blanked to \\u0000: backslash-escaped characters (both chars) and inline
   code spans — a run of N backticks through the next run of exactly N
   backticks, newlines included, so a newline INSIDE backticks can never be
   picked as a join point. An unclosed backtick run stays literal text, as
   markdown treats it. Length-preserving so indices into the mask are indices
   into the real chunk."
  [^String s]
  (let [n (count s)
        a (char-array s)]
    (loop [i 0]
      (when (< i n)
        (let [c (aget a i)]
          (cond
            (= c \\)
            (do (aset a i (char 0))
                (when (< (inc i) n) (aset a (inc i) (char 0)))
                (recur (+ i 2)))

            (= c \`)
            (let [run-end (loop [k i] (if (and (< k n) (= (aget a k) \`)) (recur (inc k)) k))
                  run-len (- run-end i)
                  close   (loop [k run-end]        ; start of a same-length closing run
                            (cond
                              (>= k n) nil
                              (= (aget a k) \`)
                              (let [e (loop [m k] (if (and (< m n) (= (aget a m) \`)) (recur (inc m)) m))]
                                (if (= (- e k) run-len) k (recur e)))
                              :else (recur (inc k))))]
              (if close
                (do (doseq [k (range i (+ close run-len))] (aset a k (char 0)))
                    (recur (long (+ close run-len))))
                (recur (long run-end))))

            :else (recur (inc i))))))
    (String. a)))

(defn- emphasis-join-boundary?
  "True when the continuation line starting after the newline at IDX in CHUNK
   must never be joined into the line above: doing so would destroy a block
   construct (blockquote, heading, table row, fence, list item, setext
   underline, horizontal rule) instead of un-wrapping prose."
  [^String chunk idx]
  (let [end  (or (str/index-of chunk "\n" (inc idx)) (count chunk))
        t    (str/triml (subs chunk (inc idx) end))]
    (or (str/blank? t)
        (str/starts-with? t ">")
        (str/starts-with? t "#")
        (str/starts-with? t "|")
        (str/starts-with? t "```")
        (str/starts-with? t "~~~")
        (some? (re-matches #"[=-]+\s*" t))              ; setext underline
        (some? (re-matches #"([*_-][ \t]*){3,}" t))     ; horizontal rule
        (some? (re-matches #"[-*+][ \t].*" t))          ; bullet item
        (some? (re-matches #"\d+[.)][ \t].*" t)))))

(defn- wrapped-emphasis-newlines
  "Indices of the newlines in CHUNK that lie strictly inside a matched *...*
   or **...** pair (scanned over MASKED, whose indices equal CHUNK's).
   Simplified CommonMark flanking: an opening run is followed by a non-space,
   a closing run is preceded by one; a run that could be both closes when a
   partner is open. Runs of 3+ asterisks are skipped as too ambiguous to
   rewrite, and an unmatched delimiter pairs with nothing, so a lone * can
   never swallow text. A span any of whose continuation lines is a block
   boundary contributes no newlines at all."
  [^String masked ^String chunk]
  (let [n      (count masked)
        at     (fn [i] (when (and (>= i 0) (< i n)) (.charAt masked i)))
        space? (fn [c] (or (nil? c) (= c \space) (= c \tab) (= c \newline)))
        spans  (loop [i 0 st1 [] st2 [] spans []]
                 (if (>= i n)
                   spans
                   (if (= (at i) \*)
                     (let [e       (loop [k i] (if (and (< k n) (= (.charAt masked k) \*)) (recur (inc k)) k))
                           len     (- e i)
                           opener? (not (space? (at e)))
                           closer? (not (space? (at (dec i))))]
                       (cond
                         (> len 2) (recur (long e) st1 st2 spans)
                         (= len 2) (cond
                                     (and closer? (seq st2)) (recur (long e) st1 (pop st2) (conj spans [(peek st2) i]))
                                     opener?                 (recur (long e) st1 (conj st2 e) spans)
                                     :else                   (recur (long e) st1 st2 spans))
                         :else     (cond
                                     (and closer? (seq st1)) (recur (long e) (pop st1) st2 (conj spans [(peek st1) i]))
                                     opener?                 (recur (long e) (conj st1 e) st2 spans)
                                     :else                   (recur (long e) st1 st2 spans))))
                     (recur (inc i) st1 st2 spans))))]
    (->> spans
         (mapcat (fn [[a b]]
                   (let [nls (filter #(= (at %) \newline) (range a b))]
                     (when (and (seq nls)
                                (not-any? #(emphasis-join-boundary? chunk %) nls))
                       nls))))
         set)))

(defn- join-emphasis-chunk
  "CHUNK (one blank-line/fence-free run of lines) with every newline that sits
   inside a wrapped emphasis span — plus the whitespace hugging it — replaced
   by a single space. Everything else is byte-identical."
  [^String chunk]
  (let [masked (mask-for-emphasis-scan chunk)
        idxs   (sort > (wrapped-emphasis-newlines masked chunk))]
    (reduce (fn [^String s idx]
              (let [a (loop [k (dec (long idx))]
                        (if (and (>= k 0) (contains? #{\space \tab} (.charAt s k))) (recur (dec k)) (inc k)))
                    b (loop [k (inc (long idx))]
                        (if (and (< k (count s)) (contains? #{\space \tab} (.charAt s k))) (recur (inc k)) k))]
                (str (subs s 0 a) " " (subs s b))))
            chunk idxs)))

(defn join-wrapped-emphasis
  "Markdown PRE-processor: un-wrap emphasis spans that straddle a source
   newline. markdown-clj 1.12.10's emphasis pass is line-scoped, so hard-wrapped
   prose like *\"...call\\nsites...\"* renders its asterisks literally even
   though it is perfectly valid GitHub markdown. Here any *...* / **...** pair
   whose markers sit on different lines has the newline(s) between them (and
   surrounding indentation) collapsed to single spaces, making the span
   single-line before the parser sees it. Scope is deliberately tight: fenced
   code, inline code, and escaped characters are invisible to the scan; only
   newlines strictly inside a matched pair change; a span is abandoned whole
   if joining would eat a block construct (list item, blockquote, heading,
   table row, fence, setext underline, hr); 3+ asterisk runs and unmatched
   delimiters join nothing. Idempotent — a joined span is single-line, so a
   second pass finds nothing."
  [body]
  (let [lines       (vec (str/split-lines body))
        n           (count lines)
        fence-line? (fn [l] (some? (re-matches #"\s*(```|~~~).*" l)))
        out (loop [i 0 in-fence? false out []]
              (if (>= i n)
                out
                (let [line (nth lines i)]
                  (cond
                    (fence-line? line) (recur (inc i) (not in-fence?) (conj out line))
                    in-fence?          (recur (inc i) true (conj out line))
                    (str/blank? line)  (recur (inc i) false (conj out line))
                    :else
                    (let [j (loop [k i]
                              (if (and (< k n)
                                       (let [l (nth lines k)]
                                         (and (not (str/blank? l)) (not (fence-line? l)))))
                                (recur (inc k)) k))]
                      (recur j false
                             (into out (str/split-lines
                                        (join-emphasis-chunk
                                         (str/join "\n" (subvec lines i j)))))))))))]
    (str (str/join "\n" out) (when (str/ends-with? body "\n") "\n"))))

(defn- wrapped-code-span-newlines
  "Indices of the newlines in CHUNK that lie strictly inside a paired inline
   code span. A span is a run of N backticks up to the NEXT run of exactly N
   backticks (CommonMark run-length pairing, so `` a`b `` works); an unpaired
   run pairs with nothing and joins nothing. The opener scan skips
   backslash-escaped backticks; the closer scan is raw, because escapes are
   inert inside code. A span any of whose continuation lines is a block
   boundary (emphasis-join-boundary?) contributes no newlines, but the scan
   still resumes after its closer so later pairing matches markdown's view."
  [^String chunk]
  (let [n  (count chunk)
        at (fn [i] (when (and (>= i 0) (< i n)) (.charAt chunk i)))]
    (loop [i 0 nls []]
      (if (>= i n)
        (set nls)
        (let [c (at i)]
          (cond
            (= c \\) (recur (+ i 2) nls)
            (= c \`)
            (let [run-end (loop [k i] (if (and (< k n) (= (at k) \`)) (recur (inc k)) k))
                  run-len (- run-end i)
                  close   (loop [k run-end]        ; start of a same-length closing run
                            (cond
                              (>= k n) nil
                              (= (at k) \`)
                              (let [e (loop [m k] (if (and (< m n) (= (at m) \`)) (recur (inc m)) m))]
                                (if (= (- e k) run-len) k (recur e)))
                              :else (recur (inc k))))]
              (if close
                (let [span-nls (filter #(= (at %) \newline) (range run-end close))]
                  (recur (long (+ close run-len))
                         (if (and (seq span-nls)
                                  (not-any? #(emphasis-join-boundary? chunk %) span-nls))
                           (into nls span-nls)
                           nls)))
                (recur (long run-end) nls)))
            :else (recur (inc i) nls)))))))

(defn- join-code-chunk
  "CHUNK (one blank-line/fence-free run of lines) with every newline that sits
   inside a paired inline code span — plus the whitespace hugging it —
   replaced by a single space. Everything else is byte-identical."
  [^String chunk]
  (reduce (fn [^String s idx]
            (let [a (loop [k (dec (long idx))]
                      (if (and (>= k 0) (contains? #{\space \tab} (.charAt s k))) (recur (dec k)) (inc k)))
                  b (loop [k (inc (long idx))]
                      (if (and (< k (count s)) (contains? #{\space \tab} (.charAt s k))) (recur (inc k)) k))]
              (str (subs s 0 a) " " (subs s b))))
          chunk (sort > (wrapped-code-span-newlines chunk))))

(defn join-wrapped-code-spans
  "Markdown PRE-processor: un-wrap inline code spans that straddle a source
   newline. markdown-clj 1.12.10's inline-code pass is line-scoped, so a
   hard-wrapped `...` span never closes — worse, the orphaned backtick
   MIS-PAIRS with the next backtick in the paragraph and steals the following
   code span. Here any paired backtick run whose closer sits on a later line
   has the newline(s) between them (and surrounding indentation) collapsed to
   single spaces, making the span single-line before the parser sees it. Runs
   pair by exact length (CommonMark), an unpaired backtick joins nothing,
   fenced code and blank lines bound every span, and a span is abandoned
   whole if joining would eat a block construct. Escaped backticks open
   nothing. Idempotent — a joined span is single-line, so a second pass finds
   nothing. Designed to run BEFORE join-wrapped-emphasis: once code spans are
   single-line, mask-for-emphasis-scan masks them normally, so emphasis
   wrapped around a multi-line code span joins too."
  [body]
  (let [lines       (vec (str/split-lines body))
        n           (count lines)
        fence-line? (fn [l] (some? (re-matches #"\s*(```|~~~).*" l)))
        out (loop [i 0 in-fence? false out []]
              (if (>= i n)
                out
                (let [line (nth lines i)]
                  (cond
                    (fence-line? line) (recur (inc i) (not in-fence?) (conj out line))
                    in-fence?          (recur (inc i) true (conj out line))
                    (str/blank? line)  (recur (inc i) false (conj out line))
                    :else
                    (let [j (loop [k i]
                              (if (and (< k n)
                                       (let [l (nth lines k)]
                                         (and (not (str/blank? l)) (not (fence-line? l)))))
                                (recur (inc k)) k))]
                      (recur j false
                             (into out (str/split-lines
                                        (join-code-chunk
                                         (str/join "\n" (subvec lines i j)))))))))))]
    (str (str/join "\n" out) (when (str/ends-with? body "\n") "\n"))))

(defn- quote-prefix-of
  "The blockquote marker prefix of LINE — up to 3 leading spaces then one or
   more `>` each optionally followed by one space/tab (so `> > ` nests) — or
   nil for a non-quote line."
  [line]
  (second (re-find #"^( {0,3}(?:>[ \t]?)+)" line)))

(defn- quote-depth-of
  "Nesting depth of a quote prefix: how many `>` it carries."
  [prefix]
  (count (filter #(= % \>) prefix)))

(defn join-wrapped-in-quotes
  "Markdown PRE-processor: un-wrap emphasis and inline-code spans that
   straddle a newline INSIDE a blockquote — the case join-wrapped-emphasis /
   join-wrapped-code-spans deliberately reject, because there a `>`
   continuation must not be merged into non-quote text. Here a maximal run of
   consecutive quote lines of the SAME nesting depth (outside any outer code
   fence) has its `>` prefixes stripped, the two existing join passes applied
   to the inner text — so inner fences, inner blank `>` lines, inner lists,
   tables, headings and unbalanced delimiters keep exactly the same
   protections they have at top level — and the surviving lines re-prefixed
   with the run's first prefix. Joining happens strictly within one depth:
   depth changes (`>` to `>>`), lazy no-`>` continuations, and anything
   outside quotes are untouched. Idempotent for the same reason the inner
   passes are."
  [body]
  (let [lines       (vec (str/split-lines body))
        n           (count lines)
        fence-line? (fn [l] (some? (re-matches #"\s*(```|~~~).*" l)))
        out (loop [i 0 in-fence? false out []]
              (if (>= i n)
                out
                (let [line (nth lines i)
                      p    (when-not in-fence? (quote-prefix-of line))]
                  (cond
                    (nil? p)
                    (recur (inc i)
                           (if (fence-line? line) (not in-fence?) in-fence?)
                           (conj out line))

                    :else
                    (let [d (quote-depth-of p)
                          j (loop [k i]
                              (if (and (< k n)
                                       (when-let [q (quote-prefix-of (nth lines k))]
                                         (= (quote-depth-of q) d)))
                                (recur (inc k)) k))
                          run    (subvec lines i j)
                          ;; trailing blank-content quote lines (bare `>`) are
                          ;; paragraph boundaries no span can cross; hold them
                          ;; out so split-lines can't swallow them.
                          t      (loop [k (count run)]
                                   (if (and (pos? k)
                                            (str/blank? (subs (nth run (dec k))
                                                              (count (quote-prefix-of (nth run (dec k)))))))
                                     (recur (dec k)) k))
                          inner  (str/join "\n" (map (fn [l] (subs l (count (quote-prefix-of l))))
                                                     (subvec run 0 t)))
                          joined (join-wrapped-emphasis (join-wrapped-code-spans inner))]
                      (recur j in-fence?
                             (-> out
                                 (into (if (= joined inner) ; nothing joined — keep the run byte-identical
                                         (subvec run 0 t)
                                         (map #(str p %) (str/split-lines joined))))
                                 (into (subvec run t)))))))))]
    (str (str/join "\n" out) (when (str/ends-with? body "\n") "\n"))))

(defn- quoted-fence-token
  "A placeholder word for one extracted quoted fence: long, random, plain
   alphanumerics only, so markdown-clj carries it through as inert text and
   document text cannot collide with it."
  []
  (str "MDSERVEQF" (str/upper-case (str/replace (str (java.util.UUID/randomUUID)) "-" "")) "QF"))

(defn- strip-quote-levels
  "LINE minus D levels of blockquote marker (each up to 3 leading spaces, a
   `>`, an optional space/tab), or nil when it carries fewer than D — so a
   fence-content line that itself starts with `>` keeps that text (only the
   enclosing quote's markers come off), and a line outside the quote returns
   nil and bounds the fence."
  [line d]
  (loop [s line k 0]
    (if (= k d)
      s
      (if-let [m (re-find #"^ {0,3}>[ \t]?" s)]
        (recur (subs s (count m)) (inc k))
        nil))))

(defn extract-quoted-fences
  "Markdown PRE-processor, first half of the quoted-fence fix. Returns
   [body' fences] where every fenced code block nested in a blockquote —
   which markdown-clj 1.12.10 renders as mangled quote prose — has been
   replaced by a single quote-prefixed placeholder line, and fences maps each
   placeholder token to {:info \"bash\" :content \"...\"} for the post-parse
   splice. CommonMark rules: the opener is a quote line whose content is
   3+ backticks/tildes (plus optional info string); the closer is a same-depth
   quote line with at least as many of the same character; an UNCLOSED fence
   is bounded at the end of its same-depth quote run, never beyond. Content
   lines keep exactly their text minus the quote prefix. Fences outside
   quotes, and quote-shaped lines inside them, are untouched (outer fences
   are tracked); a placeholder is never minted for anything else, and calling
   this on a body it already processed extracts nothing new from the
   placeholder lines (plain words), so the pipeline stays idempotent."
  [body]
  (let [lines       (vec (str/split-lines body))
        n           (count lines)
        fence-line? (fn [l] (some? (re-matches #"\s*(```|~~~).*" l)))
        opener-of   (fn [s] (re-matches #"\s*(`{3,}|~{3,})[ \t]*([^`~\s]*)[^`~]*" s))
        closer-for  (fn [s marker]
                      (some? (re-matches (re-pattern (str "\\s*" (subs marker 0 1) "{" (count marker) ",}\\s*")) s)))
        result
        (loop [i 0 in-fence? false out [] fences {}]
          (if (>= i n)
            [out fences]
            (let [line (nth lines i)
                  p    (when-not in-fence? (quote-prefix-of line))
                  inner (when p (subs line (count p)))
                  open  (when inner (opener-of inner))]
              (cond
                (nil? p)
                (recur (inc i)
                       (if (fence-line? line) (not in-fence?) in-fence?)
                       (conj out line) fences)

                (nil? open)
                (recur (inc i) in-fence? (conj out line) fences)

                :else
                (let [[_ marker info] open
                      d       (quote-depth-of p)
                      run-end (loop [k (inc i)]           ; first line with fewer than d quote levels
                                (if (and (< k n) (some? (strip-quote-levels (nth lines k) d)))
                                  (recur (inc k)) k))
                      close   (loop [k (inc i)]           ; closing fence line, if any
                                (cond
                                  (>= k run-end) nil
                                  (closer-for (strip-quote-levels (nth lines k) d) marker) k
                                  :else (recur (inc k))))
                      end     (or close (dec run-end))    ; unclosed: bound at run end
                      content (str/join "\n" (map #(strip-quote-levels % d)
                                                  (subvec lines (inc i) (or close run-end))))
                      token   (quoted-fence-token)]
                  (recur (inc end) in-fence?
                         (conj out (str p token))
                         (assoc fences token {:info info :content content})))))))]
    [(str (str/join "\n" (first result)) (when (str/ends-with? body "\n") "\n"))
     (second result)]))

(defn splice-quoted-fences
  "Second half of the quoted-fence fix: HTML POST-processor. Replaces each
   placeholder minted by extract-quoted-fences with its fence rendered
   exactly the way markdown-clj renders an unquoted one —
   <pre><code class=\"INFO\">escaped-content\\n</code></pre> — escaping the
   inner text once via escape-html (&, <, > only; backslashes, pipes, = and +
   pass through, and nothing can autolink because the content never meets the
   markdown parser). Runs BEFORE colorize-diff in the pipeline so a quoted
   ```diff block flows into the same colouriser as an unquoted one. A
   placeholder sitting alone in a <p> is unwrapped for tidy block structure;
   any other occurrence is replaced in place."
  [html fences]
  (reduce (fn [h [token {:keys [info content]}]]
            (let [block (str "<pre><code"
                             (when (seq info) (str " class=\"" info "\""))
                             ">" (escape-html content) "\n</code></pre>")]
              (-> h
                  (str/replace (re-pattern (str "<p>\\s*" token "\\s*</p>")) (str/re-quote-replacement block))
                  (str/replace token block))))
          html fences))

(defn- code-span-regions
  "[[start end] ...] content regions of the paired inline code spans in
   CHUNK — the same scanning discipline as wrapped-code-span-newlines: the
   opener scan skips backslash-escaped backticks, runs pair by exact length
   (CommonMark), the closer scan is raw, an unpaired run matches nothing."
  [^String chunk]
  (let [n  (count chunk)
        at (fn [i] (when (and (>= i 0) (< i n)) (.charAt chunk i)))]
    (loop [i 0 regions []]
      (if (>= i n)
        regions
        (let [c (at i)]
          (cond
            (= c \\) (recur (+ i 2) regions)
            (= c \`)
            (let [run-end (loop [k i] (if (and (< k n) (= (at k) \`)) (recur (inc k)) k))
                  run-len (- run-end i)
                  close   (loop [k run-end]
                            (cond
                              (>= k n) nil
                              (= (at k) \`)
                              (let [e (loop [m k] (if (and (< m n) (= (at m) \`)) (recur (inc m)) m))]
                                (if (= (- e k) run-len) k (recur e)))
                              :else (recur (inc k))))]
              (if close
                (recur (long (+ close run-len)) (conj regions [run-end close]))
                (recur (long run-end) regions)))
            :else (recur (inc i) regions)))))))

(defn- hide-chunk-backslashes
  "CHUNK with every backslash inside a paired inline code span replaced by
   TOKEN. Regions are non-overlapping and ordered, so one right-to-left pass
   keeps the indices valid."
  [^String chunk token]
  (reduce (fn [^String s [a b]]
            (str (subs s 0 a)
                 (str/replace (subs s a b) "\\" token)
                 (subs s b)))
          chunk (reverse (code-span-regions chunk))))

(defn hide-code-span-backslashes
  "Markdown PRE-processor, first half of the inline-code backslash fix.
   Returns [body' token n] where every backslash inside an inline code span
   (outside fenced blocks) has been replaced by the inert TOKEN, and n counts
   the replacements — [body nil 0] when there is nothing to hide, so the
   post-pass can be skipped. Runs AFTER the join passes, so wrapped spans are
   already single-line. Chunks are bounded by fences and blank lines exactly
   as in join-wrapped-code-spans; quote, table and heading lines sit inside
   chunks, so their code spans are covered too. Idempotent: once hidden, a
   chunk holds no backslash inside a span for a second pass to find."
  [body]
  (let [lines       (vec (str/split-lines body))
        n           (count lines)
        fence-line? (fn [l] (some? (re-matches #"\s*(```|~~~).*" l)))
        token       (str "MDSERVEBS" (str/upper-case (str/replace (str (java.util.UUID/randomUUID)) "-" "")) "SB")
        ;; ONE LINE at a time, not chunk-wide: markdown-clj's inline-code
        ;; pairing is line-scoped, and by this point the join passes have
        ;; already put every legitimate wrapped span on a single line — so a
        ;; line-scoped scan pairs exactly the spans the parser will. (Chunk-
        ;; wide scanning let a stray backtick INSIDE one span's content — the
        ;; picomatch [:punct:] class — shift every later pairing on the page.)
        out (loop [i 0 in-fence? false out []]
              (if (>= i n)
                out
                (let [line (nth lines i)]
                  (cond
                    (fence-line? line) (recur (inc i) (not in-fence?) (conj out line))
                    in-fence?          (recur (inc i) true (conj out line))
                    :else              (recur (inc i) false
                                              (conj out (hide-chunk-backslashes line token)))))))
        body' (str (str/join "\n" out) (when (str/ends-with? body "\n") "\n"))
        hits  (count (re-seq (re-pattern token) body'))]
    (if (pos? hits) [body' token hits] [body nil 0])))

(defn restore-code-span-backslashes
  "HTML POST-processor, second half: every TOKEN minted by
   hide-code-span-backslashes becomes a literal backslash again. The token is
   plain alphanumerics, so markdown-clj and the HTML escaping both carried it
   through byte-identical; nothing else in the page can contain it."
  [html token]
  (if token (str/replace html token "\\") html))

(def wide-cell-threshold
  "Characters in one table cell beyond which the page's non-wrapping table CSS
   (width:max-content) yields an unusably wide row. Calibrated against the doc
   corpus: cells read daily without complaint top out near 470, the
   unreadable ones run 600-1500+, so 500 flags the pathology without crying
   wolf on every chatty-but-tolerable table."
  500)

(defn- pipe-row-line?
  "A line markdown-clj could take as a GitHub table row: 0-3 leading spaces
   then |. 4+ spaces is an indented code block, never a table."
  [line]
  (some? (re-matches #" {0,3}\|.*" line)))

(defn- table-row-cells
  "Cell strings of a |-delimited table row, splitting on unescaped pipes."
  [line]
  (let [inner (-> line str/trim
                  (str/replace #"^\|" "")
                  (str/replace #"\|$" ""))]
    (mapv str/trim (str/split inner #"(?<!\\)\|" -1))))

(defn check-doc-health-inline
  "Scan markdown BODY (post-dedent, i.e. as the parser will see it; line
   numbers are unchanged by dedenting) for GitHub-table problems. Returns
   {:structural [..] :legibility [..]} — both empty for a healthy document.
   Structural issues LOSE content in markdown-clj 1.12.10: :blank-split (a
   blank line inside a table detaches the trailing rows, which render as an
   EMPTY table), :no-separator (2+ pipe rows with no |---| line — also an
   empty table), :cell-count (a row WIDER than its header silently drops the
   extra cells; narrower rows lose nothing and are deliberately not flagged —
   the corpus uses them intentionally). Legibility issues are valid tables
   whose cells exceed wide-cell-threshold, aggregated one per table."
  [body]
  (let [lines (vec (str/split-lines (dedent-indented-tables body)))
        n (count lines)
        separator? (fn [i] (and (< i n)
                                (some? (re-matches table-separator-re
                                                   (str/triml (nth lines i))))))
        pipe-run-end (fn [i] (loop [k i]
                               (if (and (< k n) (pipe-row-line? (nth lines k)))
                                 (recur (inc k)) k)))]
    (loop [i 0 in-fence? false structural [] legibility []]
      (if (>= i n)
        {:structural structural :legibility legibility}
        (let [line (nth lines i)]
          (cond
            (re-matches #"\s*(```|~~~).*" line)
            (recur (inc i) (not in-fence?) structural legibility)

            in-fence?
            (recur (inc i) in-fence? structural legibility)

            (and (pipe-row-line? line) (separator? (inc i)))
            (let [j (pipe-run-end i)
                  header-count (count (table-row-cells line))
                  rows (mapv (fn [r] [r (table-row-cells (nth lines r))])
                             (range (+ i 2) j))
                  mismatches (for [[r cells] rows
                                   :when (and (> (count cells) header-count)
                                              (not (separator? r)))]
                               {:type :cell-count :table-line (inc i)
                                :line (inc r) :row-cells (count cells)
                                :header-cells header-count})
                  wide (for [[r cells] (cons [i (table-row-cells line)] rows)
                             :let [len (apply max 0 (map count cells))]
                             :when (> len wide-cell-threshold)]
                         {:line (inc r) :len len})
                  legibility' (if (seq wide)
                                (conj legibility
                                      {:table-line (inc i)
                                       :wide-cells (count wide)
                                       :max-len (apply max (map :len wide))
                                       :max-line (:line (apply max-key :len wide))})
                                legibility)
                  b (loop [k j] (if (and (< k n) (str/blank? (nth lines k)))
                                  (recur (inc k)) k))]
              (if (and (> b j) (< b n)
                       (pipe-row-line? (nth lines b))
                       (not (separator? (inc b))))
                (let [p (pipe-run-end b)]
                  (recur p in-fence?
                         (into structural
                               (concat mismatches
                                       [{:type :blank-split :table-line (inc i)
                                         :blank-line (inc j) :lost-rows (- p b)
                                         :first-lost (inc b)}]))
                         legibility'))
                (recur j in-fence? (into structural mismatches) legibility')))

            (pipe-row-line? line)
            (let [j (pipe-run-end i)]
              (if (>= (- j i) 2)
                (recur j in-fence?
                       (conj structural {:type :no-separator :line (inc i)
                                         :rows (- j i)})
                       legibility)
                (recur j in-fence? structural legibility)))

            :else
            (recur (inc i) in-fence? structural legibility)))))))

(defn- structural-issue-text
  "One-line human description of a structural (content-losing) issue. Issues
   from md_lint.bb arrive with :text pre-rendered (covering issue types this
   case doesn't know); the case below serves the inline fallback checks."
  [{:keys [type table-line line blank-line lost-rows first-lost rows
           row-cells header-cells text]}]
  (or text
      (case type
        :blank-split (str "Table starting line " table-line ": blank line at line "
                          blank-line " cuts the table — the " lost-rows
                          (if (= 1 lost-rows) " row" " rows") " from line "
                          first-lost " will render as an empty table (content lost).")
        :no-separator (str rows " pipe rows starting line " line
                           " have no |---| separator line — they will render as an"
                           " empty table (content lost).")
        :cell-count (str "Table starting line " table-line ": row at line " line
                         " has " row-cells " cells but the header has " header-cells
                         " — the extra cells will be silently dropped."))))

(defn- legibility-issue-text
  "One-line human description of an over-wide (but valid) table. Issues from
   md_lint.bb arrive with :text pre-rendered; the str below serves the inline
   fallback checks."
  [{:keys [table-line wide-cells max-len max-line text]}]
  (or text
      (str "Table starting line " table-line ": " wide-cells
           (if (= 1 wide-cells) " row has a cell" " rows have cells") " over "
           wide-cell-threshold " characters (longest " max-len " chars, line "
           max-line ") — too wide to read without endless sideways scrolling.")))

(def wide-table-mitigation-css
  "Page-level override applied only when a doc has over-wide cells: lets the
   table shrink to the page and cells wrap, instead of one endless sideways
   scroll."
  "<style>table{display:table;width:auto;max-width:100%}td,th{overflow-wrap:anywhere}</style>")

(defn health-banner-html
  "Banner HTML for check-doc-health-inline ISSUES, or \"\" when the doc is healthy.
   Structural breakage (parse really loses content) gets a red banner;
   over-wide cells get a differently-worded amber legibility note plus the
   wrapping-CSS mitigation."
  [path {:keys [structural legibility]}]
  (str
   (when (seq structural)
     (str "<div style=\"background:#c62828;color:#fff;padding:12px 16px;"
          "border-radius:6px;margin:0 0 16px;font-size:15px\">"
          "<strong>&#9888; BROKEN MARKDOWN &mdash; content is missing from this"
          " page</strong><br>" (escape-html path)
          "<ul style=\"margin:8px 0 0\">"
          (str/join (map #(str "<li>" (escape-html (structural-issue-text %)) "</li>")
                         structural))
          "</ul></div>"))
   (when (seq legibility)
     (str wide-table-mitigation-css
          "<div style=\"background:#fff3cd;color:#6b5300;border:1px solid #e0c36a;"
          "padding:10px 16px;border-radius:6px;margin:0 0 16px;font-size:14px\">"
          "<strong>Legibility warning</strong> &mdash; the markdown is valid and"
          " nothing is lost, but some table cells are too long to render as a"
          " normal table. Those tables are shown with wrapped cells."
          "<ul style=\"margin:8px 0 0\">"
          (str/join (map #(str "<li>" (escape-html (legibility-issue-text %)) "</li>")
                         legibility))
          "</ul></div>"))))

(defn github-heading-slug
  "GitHub-style anchor slug for a heading's TEXT content (tags already
   stripped). This is what anyone hand-writing [text](#anchor) against a
   GitHub-rendered doc expects: lowercase; drop every character that is not
   alphanumeric, space, hyphen or underscore (so backticks, em/en dashes,
   colons, parentheses, / and friends simply vanish — an em-dash does NOT
   become a hyphen); then collapse each run of whitespace to a single hyphen.
   The few HTML entities markdown-clj leaves in heading text are unescaped
   first so &amp; contributes nothing rather than the letters \"amp\"."
  [text]
  (-> text
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      str/lower-case
      (str/replace #"[^a-z0-9 _-]" "")
      str/trim
      (str/replace #"\s+" "-")))

(defn- heading-inner-text
  "Text content of a heading's inner HTML: inline markup (<code>, <a>, <em>,
   ...) stripped, leaving only what a reader sees — the string GitHub slugs
   from."
  [inner-html]
  (str/replace inner-html #"<[^>]*>" ""))

(defn anchorize-headings
  "HTML POST-processor: give every attribute-less <h1>-<h6> in rendered
   markdown an id=\"<github-slug>\" so in-document [D24](#d24-...) anchor
   links land somewhere. Duplicate slugs get -1, -2, ... suffixes in document
   order, as GitHub does. Headings that already carry attributes (raw HTML in
   the source, so possibly an id of the author's choosing) don't match the
   bare <hN> pattern and are left byte-identical, as is a heading whose slug
   comes out empty. Code blocks are safe by construction — markdown-clj
   HTML-escapes fence content, so no literal <h3> survives inside one."
  [html]
  (let [seen (atom {})]
    (str/replace html #"(?s)<h([1-6])>(.*?)</h\1>"
                 (fn [[whole level inner]]
                   (let [base (github-heading-slug (heading-inner-text inner))
                         n    (get @seen base 0)
                         slug (if (zero? n) base (str base "-" n))]
                     (swap! seen assoc base (inc n))
                     (if (str/blank? base)
                       whole
                       (str "<h" level " id=\"" slug "\">"
                            inner "</h" level ">")))))))

(def md-lint-file
  "The markdown lint script beside this file — the single implementation of the
   doc health checks (tables plus blockquoted tables, dangling anchors, broken
   sibling links). The inline checks above are the fallback when it is
   missing or broken; mdserve must keep serving either way."
  (str (io/file (.getParentFile (.getAbsoluteFile source-file)) "md_lint.bb")))

(def md-lint-check-doc
  "Delayed load of md_lint.bb's check-doc, or nil when the script cannot be
   loaded (moved, renamed, syntax error). Loaded once per server process, so
   editing md_lint.bb takes effect on the next mdserve restart."
  (delay (try (load-file md-lint-file)
              (some-> (resolve 'md-lint/check-doc) deref)
              (catch Throwable _ nil))))

(defn check-doc-health
  "Doc health via md_lint.bb (one implementation, all six checks; its issues
   carry :text pre-rendered), with check-doc-health-inline's table-only checks
   as the fallback when the lint script is missing, unloadable, or throws. Same
   {:structural [..] :legibility [..]} shape either way."
  [body path]
  (or (when-let [f @md-lint-check-doc]
        (try (f body path) (catch Throwable _ nil)))
      (check-doc-health-inline body)))

(defn- md-render
  "Markdown to HTML by one of three methods, chosen by two switches. nil when
   the fences? method's placeholders fail to survive parsing intact, or would
   remain after splicing — the caller then tries a simpler method, so no
   content is ever lost and no placeholder can leak. Never nil without fences?.

   Always, in the RENDER path only: quote-internal spans join first
   (join-wrapped-in-quotes touches only blockquote runs, which the other two
   passes deliberately leave alone), then code spans, then emphasis.

   fences?: fenced code blocks nested in blockquotes — which markdown-clj
   1.12.10 renders as mangled quote prose — are extracted to placeholder lines
   before parsing and their <pre><code> HTML spliced over the placeholders
   afterwards (extract-quoted-fences / splice-quoted-fences), so a quoted
   ```diff block reaches colorize-diff like an unquoted one.

   backslashes?: markdown-clj runs its escape->entity pass before code spans
   are protected and then HTML-escapes the &, so a backslash escape inside
   inline code rendered as a literal entity. Backslashes in inline code are
   hidden behind an inert token before parsing and restored after
   (hide-code-span-backslashes / restore-code-span-backslashes), before the
   fence splice, whose content never passes through the parser."
  [body fences? backslashes?]
  (let [[body* fences] (if fences? (extract-quoted-fences body) [body {}])
        joined        (join-wrapped-emphasis (join-wrapped-code-spans (join-wrapped-in-quotes body*)))
        [masked tok]  (if backslashes? (hide-code-span-backslashes joined) [joined nil])
        html          (md/md-to-html-string masked)
        html          (if backslashes? (restore-code-span-backslashes html tok) html)]
    (if-not fences?
      html
      (let [ok?     (every? #(str/includes? html %) (keys fences))
            spliced (when ok? (splice-quoted-fences html fences))]
        (when-not (or (not ok?) (some #(str/includes? spliced %) (keys fences)))
          spliced)))))

(defn md-body-html-with-health
  "A markdown document as page HTML: the health banner, then the body rendered
   by the most capable md-render method that succeeds — quoted fences plus the
   inline-code backslash fix, then quoted fences alone, then plain. The health
   check sees the RAW body, so the line numbers it reports match the file on
   disk."
  [path body]
  (str (health-banner-html path (check-doc-health body path))
       (anchorize-headings
        (colorize-diff
         (blockify-entries
          (or (md-render body true true)
              (md-render body true false)
              (md-render body false false)))))))

(def dir-groups
  "Ordered [heading extension-set] pairs for the directory listing. Subdirs is
   handled specially (directories, not an extension). Anything unmatched falls
   under the trailing \"Other files\" heading."
  [["Subdirs"          :dir]
   ["Plain-text notes" #{"txt"}]
   ["Markdown"         #{"md" "markdown"}]
   ["PDFs"             #{"pdf"}]
   ["Clojure code"     #{"clj" "cljs" "cljc" "edn" "bb"}]
   ["Scripts"          #{"sh" "py"}]
   ["Data files"       #{"json"}]])

(def viewable-exts
  "File extensions mdserve renders anywhere — these become clickable links."
  #{"md" "markdown" "txt" "pdf"})

(def code-exts
  "Extensions from the \"Clojure code\", \"Scripts\" and \"Data files\" groups
   — shown raw (as text) anywhere, so they become clickable links too. Derived
   from dir-groups to stay in sync: a group added there is only viewable once
   its heading is named here too."
  (->> dir-groups
       (filter (comp #{"Clojure code" "Scripts" "Data files"} first))
       (mapcat second)
       set))

(def raw-dir
  "Files under this directory may all be viewed raw (not just viewable-exts),
   so every file there becomes a clickable link showing its contents. ~/bin by
   default, since that is where the command buttons' scripts live."
  (or (some-> (:raw-dir config) normal-dir) (str home "/bin")))

(defn- under-raw? [f]
  (str/starts-with? (.getPath f) (str raw-dir "/")))

(defn- ext-of [name]
  (some-> (re-find #"\.([^.]+)$" name) second str/lower-case))

(defn- hljs-lang
  "highlight.js language for a raw-viewed file, or nil when there is no safe
   mapping. Only languages the loaded bundle actually carries belong here:
   layout's head loads hljs core (which covers json, bash and python) plus the
   separate clojure language file. An unmapped file gets a bare <code> and
   renders exactly as it did before — the highlighter only touches
   `pre code[class]`, so no class means no change."
  [name]
  (case (ext-of name)
    ("clj" "cljs" "cljc" "edn" "bb") "clojure"
    "json" "json"
    "sh" "bash"
    "py" "python"
    nil))

(defn- raw-text-file?
  "A regular file mdserve shows as raw text: anything under raw-dir, any
   code/script file (by extension) anywhere, or a hidden dotfile (config files
   like .envrc / .gitignore have no recognised extension but are ~always text)."
  [f]
  (and (.isFile f)
       (or (under-raw? f)
           (contains? code-exts (ext-of (.getName f)))
           (str/starts-with? (.getName f) "."))))

(defn- viewable?
  "Whether a listing entry should be a clickable link."
  [f]
  (or (contains? viewable-exts (ext-of (.getName f)))
      (raw-text-file? f)))

(defn- enc-path
  "URL-encode an absolute path segment-by-segment, preserving slashes."
  [abs]
  (->> (str/split abs #"/")
       (map #(str/replace (URLEncoder/encode % "UTF-8") "+" "%20"))
       (str/join "/")))

(defn- tab-key
  "Identity for merge purposes: the route with any query string dropped, so a
   remembered /commands/x?auto=1 counts as /commands/x being present and doesn't
   produce a second dashboard tab."
  [t]
  (-> (or (:path t) (:url t) "")
      (str/replace #"\?.*$" "")))

(defn- has-nav-tab?
  "True when a tab set already contains a green :nav listing — the Explorer.
   window-groups asks it of each window, so every window gets an Explorer."
  [tabs]
  (boolean (some #(= :nav (tab-category %)) tabs)))

(defn- nearest-dir
  "p itself when it is an existing directory, else its nearest existing ancestor
   directory, else nil."
  [p]
  (loop [f (io/file p)]
    (cond
      (nil? f)         nil
      (.isDirectory f) (.getPath f)
      :else            (recur (.getParentFile f)))))

(defn nav-tab->root
  "A green :nav tab settled for replay. Replay gives every tab a ONE-entry
   history, so a remembered Explorer reopened deep in the tree with Back dead —
   and since tab-category calls ANY /view without show=1 a listing, a file you
   reached by clicking a link counts as one too. Two different cases, so two
   answers:

     a DIRECTORY listing -> the top of its tree: the project root when it has
       one, else the directory itself. At the root the dead Back button costs
       nothing — Back's job was to get you home, and you start home. Collapsing
       deep listings to one root is also what keeps a window to ONE green dot.

     a FILE view -> kept, as ?show=1: it is a document you had open, not an
       Explorer, and normalizing it to a root would silently DELETE it (docs
       vanished that way in testing). It drops to the grey doc
       dot, which promises no Back, and tab-gone? still sees it (query stripped).

   Anything that isn't a :nav tab passes through untouched."
  [t]
  (if-not (= :nav (tab-category t))
    t
    (let [p (tab-fs-path t)]
      (cond
        (some-> p io/file .isFile) {:path (str "/view" (enc-path p) "?show=1")}
        (or (tab-project t) (some-> p nearest-dir))
        {:path (str "/view" (enc-path (or (tab-project t) (nearest-dir p))))}
        :else t))))

(defn commands-of
  "A project's command buttons, re-read from config.edn on every call, so a
   button added to the file shows up on the next page load with no restart.
   group is a project name; \"home\" is the top-level :commands list."
  [group]
  (let [cfg (read-config)]
    (vec (if (= group "home")
           (:commands cfg)
           (some #(when (= group (str (:name %))) (:commands %)) (:projects cfg))))))

(defn always-tabs
  "The tabs every start restores: the panel, and for each project its command
   dashboard when it has commands, else its Explorer, so every configured
   project gets a window, and a Sessions tab for each workspace config.edn
   names. Replay adds an Explorer to any window lacking one."
  []
  (-> [{:path "/"}]
      (into (for [{:keys [name dir]} projects]
              (if (seq (commands-of name))
                {:path (str "/commands/" (enc-path name))}
                {:path (str "/view" (enc-path dir))})))
      (into (for [n (config-workspaces)] {:path (str "/sessions/" n)}))))

(defn merge-tabs
  "The remembered set merged with always-tabs: the dashboards are always restored, but the
   launch-dir listing arrives only when NOTHING in the set is a listing — a set
   with no Explorer anywhere would otherwise replay without one."
  [tabs]
  (let [have   (set (map tab-key tabs))
        merged (into (vec (remove #(have (tab-key %)) (always-tabs))) tabs)]
    (if (has-nav-tab? merged)
      merged
      (conj merged {:path (str "/view" (enc-path (System/getProperty "user.dir")))}))))

(defn- classify [f]
  (if (.isDirectory f)
    (if (str/starts-with? (.getName f) ".") "Hidden subdirs" "Subdirs")
    (if (str/starts-with? (.getName f) ".")
      "Hidden files"
      (let [e (ext-of (.getName f))]
        (or (some (fn [[h m]] (when (and (set? m) (m e)) h)) dir-groups)
            "Other files")))))

(defn- md-name
  "Escape a filename for display: HTML-escape (<>&), then backslash-escape
   underscores. markdown-clj re-runs its emphasis pass over inline text even
   inside raw HTML, so a name like FOO_BAR_BAZ.md would otherwise have its
   paired underscores turned into <em> and vanish."
  [text]
  (str/replace (escape-html text) "_" "\\_"))

(defn- link-html
  "An <a> to href, with the shown text made safe via md-name."
  [href text]
  (str "<a href=\"" href "\">" (md-name text) "</a>"))

(defn- entry-line [f]
  (let [name  (.getName f)
        href  (str base "/view" (enc-path (.getPath f)))]
    (cond
      (.isDirectory f)                (str "- " (link-html href (str name "/")))
      (and (.isFile f) (viewable? f)) (str "- " (link-html href name))
      :else                           (str "- `" name "`"))))

(defn- breadcrumb
  "The path rendered as clickable ancestors: a leading root plus every segment
   except the current dir links to that ancestor's listing."
  [path]
  (let [segs   (vec (remove str/blank? (str/split path #"/")))
        last-i (dec (count segs))
        href   (fn [p] (str base "/view" (enc-path p)))]
    (str "<span class=\"crumbs\"><a href=\"" base "/view/\">/</a>"
         (str/join "/"
                   (map-indexed
                    (fn [i seg]
                      (if (= i last-i)
                        (md-name seg)
                        (link-html (href (str "/" (str/join "/" (subvec segs 0 (inc i))))) seg)))
                    segs))
         "</span>")))

(defn dir-listing-md
  "Render a directory as grouped markdown: a heading per non-empty category,
   in dir-groups order (Hidden subdirs directly below Subdirs; Hidden files
   last). Hidden (dot-prefixed) entries are shown too — hidden subdirs in their
   own group, hidden files in a Hidden files group — so they can be navigated
   into / viewed."
  [path]
  (let [visible (->> (or (.listFiles (io/file path)) [])
                     ;; most-recently-modified first within each group
                     (sort-by #(.lastModified %) >))
        by-group (group-by classify visible)]
    (str "# " (.getName (io/file path)) "\n\n"
         (breadcrumb path) "  ·  " (count visible) " items\n\n"
         (str/join "\n"
                   (for [g (concat ["Subdirs" "Hidden subdirs"]
                                   (map first (next dir-groups))
                                   ["Other files" "Hidden files"])
                         :let [items (by-group g)]
                         :when (seq items)]
                     (str "## " g "\n"
                          (str/join "\n" (map entry-line items))
                          "\n"))))))

(def image-content-types
  "Raw-serve Content-Types by extension. A doc renders at /view/<dir>/doc.md, so
   a relative ![](pic.png) resolves to /view/<dir>/pic.png — serving these raw
   makes markdown image references work without inlining base64 data URIs."
  {"png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "webp" "image/webp"
   "svg"  "image/svg+xml"})

(defn view [path show?]
  ;; nav? = reached by browsing (dir listings, crumbs, startup), NOT via /show.
  ;; Only the navigation tab gets the " FILE"/" DIR" title suffix and the green tint.
  (let [nav? (not show?)
        f    (io/file path)
        term (str base "/terminal" (enc-path path))     ; terminal button target (dir views)
        vi-url (str base "/vi" (enc-path path))         ; vi-edit button target (file views)
        ;; File views only, and only while GREEN: a shown doc is already one, and
        ;; on a pink/tmp or yellow/archive view the button would promise a grey
        ;; dot it can't deliver — layout's (or (:dot opts) ...) keeps those
        ;; colours whatever the query says, and tab-category buckets them by dot
        ;; before the show=1 fall-through, so Remember Tabs keeps them as-is
        ;; already.
        dbtn (when (and nav? (nil? (path-dot path))) (doc-button))]
    (cond
      (not (.exists f))
      (text 404 (str "No such file or directory:\n" path "\n"))

      (.isDirectory f)
      (let [body (dir-listing-md path)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout (str (doc-title path body) (when nav? " DIR")) (md/md-to-html-string body)
                       (terminal-button term)
                       {:nav? nav? :dot (path-dot path)})})

      (re-find #"(?i)\.(diff|patch)$" path)
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout (str (.getName f) (when nav? " FILE")) (diff->html (escape-html (slurp f))) (str (vi-button vi-url) dbtn)
                     {:nav? nav? :dot (path-dot path)})}

      (re-find #"(?i)\.pdf$" path)
      {:status 200
       :headers {"Content-Type" "application/pdf"}
       :body f}

      (image-content-types (ext-of (.getName f)))
      {:status 200
       :headers {"Content-Type" (image-content-types (ext-of (.getName f)))}
       :body f}

      (re-find #"(?i)\.(md|markdown)$" path)
      (let [body (slurp f)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout (str (doc-title path body) (when nav? " FILE"))
                       (md-body-html-with-health path body)
                       (str (vi-button vi-url) dbtn)
                       {:nav? nav? :dot (path-dot path)})})

      (re-find #"(?i)\.(txt|log)$" path)
      (let [body (slurp f)
            has-exception? (re-find #"(?m)^[a-zA-Z0-9_.$]+(?:Exception|Error|Info|Throwable):" body)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout (str (.getName f) (when nav? " FILE"))
                       (if has-exception?
                         (format-stacktrace body)
                         (md-body-html-with-health path body))
                       (str (vi-button vi-url) dbtn)
                       {:nav? nav? :dot (path-dot path) :copy-selector (when has-exception? ".exception")})})

      ;; code/script files, or any file under raw-dir — show raw contents
      (raw-text-file? f)
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (layout (str (.getName f) (when nav? " FILE"))
                     (str "<pre><code"
                          (when-let [lang (hljs-lang (.getName f))]
                            (str " class=\"language-" lang "\""))
                          ">" (escape-html (slurp f)) "</code></pre>")
                     (str (vi-button vi-url) dbtn)
                     {:nav? nav? :dot (path-dot path)})}

      :else
      (text 404 (str "Not a readable file:\n" path "\n")))))

(defn- cdp!
  "One CDP request/reply over a target's WebSocket, returning :result or nil.
   Frames are buffered until `last?`: CDP splits larger messages, and ignoring
   that flag yields truncated JSON — which looks exactly like a dead browser."
  [ws-url method params]
  (try
    (let [replied  (promise)
          buf      (atom (StringBuilder.))
          listener (reify WebSocket$Listener
                     (onOpen [_ ws] (.request ws 1))
                     (onText [_ ws data last?]
                       (.append @buf (str data))
                       (when last? (deliver replied (str @buf)))
                       (.request ws 1)
                       nil))
          ws       (-> (HttpClient/newHttpClient)
                       .newWebSocketBuilder
                       (.buildAsync (URI/create ws-url) listener)
                       .join)]
      (.join (.sendText ws (json/generate-string {:id 1 :method method :params (or params {})}) true))
      (let [r (deref replied 6000 nil)]
        (.join (.sendClose ws 1000 "done"))
        (some-> r (json/parse-string true) :result)))
    (catch Exception e
      (println "  cdp!" method "failed:" (.getMessage e))
      nil)))

(defn- activate-target! [id]
  (try (slurp (str "http://localhost:" devtools-port "/json/activate/" id)) true
       (catch Exception _ false)))

(defn- reload-target!
  "Reload an open tab in place and bring it to the front. Page.reload only exists
   over the CDP WebSocket; activation has a plain HTTP endpoint. Returns true on
   success, false if anything went wrong — the caller then opens a new tab."
  [{:keys [id webSocketDebuggerUrl]}]
  (if (cdp! webSocketDebuggerUrl "Page.reload" {:ignoreCache true})
    (do (activate-target! id) true)
    false))

(defn open-url-beside-tab!
  "Open url as a tab in home's window, home handed in directly (the workspace
   flow already holds the marker tab) rather than found by project as
   open-url-in-window! does. The steer:
   activate a tab in the target window, open, hand focus back. Plain open when
   home is nil."
  [url home]
  (if-not home
    (open-url! url)
    (let [came-from (first (page-targets))]
      (activate-target! (:id home))
      (Thread/sleep 400)
      (open-url! url)
      (Thread/sleep 1200)
      (when (and came-from (not= (:id came-from) (:id home)))
        (activate-target! (:id came-from))))))

(defn open-url-via-opener!
  "Open url as a tab in home's window WITHOUT depending on focus. The open-url-beside-tab! steer
   (activate a tab, exec chrome, hand focus back) only works when the
   compositor actually grants the activated window focus — Chrome puts a
   command-line url in its last-ACTIVE window, and GNOME/Wayland refuses to
   focus a window parked on another workspace, so the tab lands in whatever
   window IS focused instead. This opener does Runtime.evaluate of
   window.open on home's own socket (:userGesture true defeats the popup
   blocker): the new tab is created adjacent to its opener, in the opener's
   window, wherever that window sits, with no activation, no sleeps and no
   focus theft. Returns true when the opener confirmed a window handle, else
   falls back to the open-url-beside-tab! steer and returns false; plain open
   when home is nil."
  [url home]
  (if-not home
    (do (open-url! url) false)
    (let [r (cdp! (:webSocketDebuggerUrl home) "Runtime.evaluate"
                  {:expression (str "window.open(" (json/generate-string url) ",'_blank')!==null")
                   :userGesture true
                   :returnByValue true})]
      (if (true? (get-in r [:result :value]))
        true
        (do (println "  open-url-via-opener!: window.open refused on" (:url home)
                     "- falling back to activate-steer")
            (open-url-beside-tab! url home)
            false)))))

(defn- open-window-with!
  "Launch a NEW window in the dedicated profile carrying urls as its tabs.
   --class gives that window its own app_id, which is the hook a GNOME
   auto-move-windows rule keys on; harmless if no such rule exists."
  [cls urls]
  (try
    (p/process (into (into browser-cmd
                           [(str "--user-data-dir=" profile)
                            "--no-first-run" "--no-default-browser-check"
                            (str "--class=" cls)
                            "--new-window"])
                     urls)
               {:out (io/file "/dev/null") :err (io/file "/dev/null")})
    true
    (catch Exception e
      (println "  open-window-with! failed:" (.getMessage e))
      false)))

(defn- query-param
  "The value of one query-string parameter, URL-decoded; nil when absent.
   The inline re-find idiom used elsewhere (declare-ws!) is left as it was."
  [query-string k]
  (some-> (re-find (re-pattern (str "(?:^|&)" k "=([^&]*)")) (or query-string ""))
          second
          (URLDecoder/decode "UTF-8")))

(defn- strip-fragment
  "A target URL without its #fragment. Chrome's /json/list reports fragments,
   so tab-reuse comparisons must strip them or a doc shown with ?at= would
   never match the tab already on it — the duplicate-tab bug an earlier show fixed."
  [u]
  (str/replace (or u "") #"#.*$" ""))

(defn- navigate-target!
  "Point an open tab at `url` and bring it forward. Navigating to a URL that
   differs only in its fragment scrolls to that anchor without reloading, which
   is what makes ?at= land on a heading. Returns true on success."
  [{:keys [id webSocketDebuggerUrl]} url]
  (if (cdp! webSocketDebuggerUrl "Page.navigate" {:url url})
    (do (activate-target! id) true)
    false))

(defn- home-tab
  "An open tab living in root's window, to open new tabs beside — or, with root
   nil, a tab in the panel's window (the panel itself first, else any tab that
   belongs to no project). nil when no such window is open."
  [root]
  (let [targets (remove #(str/starts-with? (or (:url %) "") "devtools://") (page-targets))]
    (if root
      (first (filter #(= root (tab-project (url->tab %))) targets))
      (or (first (filter #(= (str base "/") (:url %)) targets))
          (first (filter #(nil? (tab-project (url->tab %))) targets))))))

(defn- place-url!
  "Open url as a new tab in root's window (nil = the panel's window).

   Placement goes through open-url-via-opener!: window.open evaluated in a tab
   that already lives in the target window. A new tab opens beside its opener,
   in the opener's window, wherever that window sits — on another workspace
   included — with no focus games. Chrome on its own puts a command-line url in
   the last-active window, which is why this exists.

   A project whose window is not open gets a fresh window (its Explorer, then
   the url) rather than having the url land among another project's tabs.

   Returns once DevTools lists the new tab (after 3s at most, at once when
   DevTools is off). A tab takes a moment
   to appear there, and a second /show of the same doc arriving in that moment
   would not see it and would open a duplicate."
  [url root]
  (if-let [home (home-tab root)]
    (open-url-via-opener! url home)
    (if-let [{:keys [name dir]} (some-> root project-by-dir)]
      (open-window-with! (str "mdserve-" name)
                         [(str base "/view" (enc-path dir)) url])
      (if-let [panel (home-tab nil)]
        (open-url-via-opener! url panel)
        (open-url! url))))
  (loop [tries 30]
    (when-let [targets (when (pos? tries) (page-targets))]
      (when-not (some #(= url (strip-fragment (:url %))) targets)
        (Thread/sleep 100)
        (recur (dec tries))))))

(def placing
  "Held while a tab is being looked for and placed, so two requests for the
   same doc cannot both decide it is not open yet."
  (Object.))

(defn- ws-target
  "Resolve ?ws=<n> to [root warning]. n names the project configured on that
   workspace; the panel's own number means the panel window (root nil). Any
   other n is not honoured — warning says so, and the caller falls back to
   placing by path."
  [query-string]
  (when-let [ws (some-> (query-param query-string "ws") parse-long)]
    (cond
      (some #(= ws (:workspace %)) projects)
      [(:dir (first (filter #(= ws (:workspace %)) projects))) nil]

      (= ws panel-workspace)
      [nil nil]

      :else
      [:none (str "WARNING: ?ws=" ws " NOT honoured - no project is configured on workspace "
                  ws " (see " config-file "); placed by path instead")])))

(defn show
  "GET /show/<path>: render the doc and put it in front of you.

   Placement, first match wins:
     1. a tab already on the doc   reloaded in place and brought forward
     2. ?ws=<n>                    the window of the project on workspace n
     3. the doc's own path         the window of the project it lives under
     4. otherwise                  the panel's window

   With ?at=<anchor> the doc then scrolls to that heading. The anchor is the
   rendered heading's id — read it from the page (`<h2 id=...>`) rather than
   deriving it from the heading text. It is accepted only as [A-Za-z0-9._-]+;
   anything else is dropped and the request behaves as if no ?at= were given.
   The tab is polled for briefly, since a new tab takes a moment to appear;
   failing to find it is not an error — the doc is shown, just not scrolled."
  [uri query-string]
  (let [path (suffix uri "/show")
        at   (some-> (query-param query-string "at")
                     (as-> v (when (re-matches #"[A-Za-z0-9._-]+" v) v)))
        url  (str base "/view" (subs uri (count "/show")) "?show=1")]
    (if-not (.exists (io/file path))
      (text 404 (str "No such file or directory:\n" path "\n"))
      (let [wt         (ws-target query-string)
            [ws-root warn] wt
            ;; ws-target answers nil without ?ws=, and [nil nil] for the panel
            root       (if (and wt (not= :none ws-root)) ws-root (file-project path))]
        (when warn (println " " warn))
        (locking placing
          (if-let [open-on-it (first (filter #(= url (strip-fragment (:url %))) (page-targets)))]
            (do (println "  reusing tab for" url)
                (when-not (reload-target! open-on-it) (place-url! url root)))
            (place-url! url root)))
        (when at
          (loop [tries 6]
            (if-let [t (first (filter #(= url (strip-fragment (:url %))) (page-targets)))]
              (navigate-target! t (str url "#" at))
              (when (pos? tries)
                (Thread/sleep 250)
                (recur (dec tries))))))
        (text 200 (str url (when at (str "#" at)) "\n" (when warn (str warn "\n"))))))))

(def terminal-templates
  "Known terminals, as [shell-template run-template], tried in order when
   config.edn names none. {dir} is replaced by the directory; the single token
   {cmd} by the command's words. On macOS, Terminal.app comes last: `open` for a
   plain terminal, and AppleScript to run a command in one (its words quoted one
   by one). The first time, macOS asks whether bb may control Terminal."
  (into [["ptyxis"         ["ptyxis --new-window --working-directory={dir}"
                            "ptyxis --new-window --working-directory={dir} -- {cmd}"]]
         ["gnome-terminal" ["gnome-terminal --working-directory={dir}"
                            "gnome-terminal --working-directory={dir} -- {cmd}"]]
         ["konsole"        ["konsole --workdir {dir}"
                            "konsole --workdir {dir} -e {cmd}"]]
         ["kitty"          ["kitty --directory {dir}"
                            "kitty --directory {dir} {cmd}"]]
         ["alacritty"      ["alacritty --working-directory {dir}"
                            "alacritty --working-directory {dir} -e {cmd}"]]
         ["xfce4-terminal" ["xfce4-terminal --working-directory={dir}"
                            "xfce4-terminal --working-directory={dir} -x {cmd}"]]]
        (when mac?
          [["osascript" ["open -a Terminal {dir}"
                         ["osascript"
                          "-e" "on run argv"
                          "-e" "set s to \"cd \" & quoted form of (item 1 of argv) & \" && exec\""
                          "-e" "repeat with w in (rest of argv)"
                          "-e" "set s to s & \" \" & quoted form of (w as text)"
                          "-e" "end repeat"
                          "-e" "tell application \"Terminal\" to do script s"
                          "-e" "tell application \"Terminal\" to activate"
                          "-e" "end run"
                          "{dir}" "{cmd}"]]]])))

(defn- on-path? [exe]
  (some #(.canExecute (io/file % exe))
        (str/split (or (System/getenv "PATH") "") #":")))

(def terminal-config
  "[shell-template run-template]: from config.edn's :terminal / :terminal-run
   (or MDSERVE_TERMINAL / MDSERVE_TERMINAL_RUN), else the first known terminal
   on PATH, else nil — the ⌨ Terminal and 📝 vi buttons then report failure."
  (let [detected (some (fn [[exe tmpls]] (when (on-path? exe) tmpls)) terminal-templates)]
    [(or (System/getenv "MDSERVE_TERMINAL") (:terminal config) (first detected))
     (or (System/getenv "MDSERVE_TERMINAL_RUN") (:terminal-run config) (second detected))]))

(defn- fill-template
  "argv from a template: a space-separated string, or a vector of tokens when a
   token must hold spaces (macOS's AppleScript lines). {dir} is replaced inside
   any token, and the token {cmd} expanded to cmd-argv."
  [tmpl dir cmd-argv]
  (into []
        (mapcat (fn [tok]
                  (if (= tok "{cmd}")
                    cmd-argv
                    [(str/replace tok "{dir}" dir)])))
        (if (string? tmpl) (str/split (str/trim tmpl) #"\s+") tmpl)))

(defn- terminal-argv
  "argv to open a terminal in dir, or nil when no terminal is known."
  [dir]
  (some-> (first terminal-config) (fill-template dir nil)))

(defn terminal
  "Open a terminal in a directory (launched detached). Body is unused — the
   button stays on the page."
  [path]
  (let [f    (io/file path)
        argv (terminal-argv (.getPath f))]
    (cond
      (not (.isDirectory f)) (text 404 (str "Not a directory:\n" path "\n"))
      (nil? argv)            (text 500 "No terminal known: set :terminal in config.edn\n")
      :else
      (do
        (try
          (p/process argv {:out (io/file "/dev/null") :err (io/file "/dev/null")})
          (catch Exception e (println "  terminal error:" (.getMessage e))))
        (text 200 "opened")))))

(defn saved-tabs
  "The :tabs of tabs-file exactly as written, vanished /view targets included, or
   nil when there's no readable file. read-tabs drops vanished targets for replay;
   a merge must not, or a save would quietly prune them."
  []
  (let [f (io/file tabs-file)]
    (when (.isFile f)
      (try (:tabs (edn/read-string (slurp f)))
           (catch Exception _ nil)))))

(defn carried-over-tabs
  "Saved tabs of every dev project with no tab open now. Writing only the live set
   meant a project window closed by accident was forgotten on the next save, so an
   absent project keeps what was saved for it. Tabs that belong to no project go
   with the panel window, which is always open, so the live set replaces them.
   Forgetting a project is deliberate: delete its tabs from tabs-file by hand."
  [saved live]
  (let [open (set (keep tab-project live))]
    (filterv #(when-let [proj (tab-project %)] (not (open proj))) saved)))

(defn bak-file!
  "Copy f to the next free f.bak<n>, never overwriting an existing backup.
   Returns the backup's path."
  [f]
  (let [b (first (remove #(.exists %)
                         (map #(io/file (str (.getPath f) ".bak" %)) (iterate inc 1))))]
    (io/copy f b)
    (.getPath b)))

(defn remember-tabs
  "Save the open tabs to tabs-file, merging rather than replacing: a project with
   no tab open keeps its saved tabs (carried-over-tabs), and the previous
   tabs-file is backed up (bak-file!) before it is overwritten."
  [_]
  (if-let [tabs (seq (live-tabs))]
    (let [f    (io/file tabs-file)
          live (mapv url->tab tabs)
          kept (carried-over-tabs (saved-tabs) live)]
      (io/make-parents f)
      (if (and (.isFile f) (not (try (bak-file! f) (catch Exception _ nil))))
        (text 500 (str "Not saved: could not back up " tabs-file " first.\n"))
        (do
          (spit f (str ";; mdserve remembered tabs — replayed on startup, in order.\n"
                       ";; Written by the Remember Tabs button; safe to hand-edit.\n"
                       ";; :path = an mdserve route (port-independent); :url = anything else.\n"
                       ";; Green Explorer tabs are saved at the root of their tree, and the\n"
                       ";; set is replayed as one window per project (see window-groups).\n"
                       ";; A project with no window open when saved keeps its tabs from the\n"
                       ";; previous save. To forget a project, delete its tabs here by hand.\n"
                       (with-out-str
                         (pprint/pprint {:mdserve/tabs-version 2
                                         :saved-at (str (java.time.LocalDateTime/now))
                                         :source :devtools
                                         :tabs (sort-tabs
                                                (vec (distinct (map nav-tab->root
                                                                    (into live kept)))))}))))
          (text 200 (str "remembered " (count tabs) " open tabs"
                         (when (seq kept)
                           (str "; kept " (count kept) " saved tabs for closed "
                                (str/join ", " (distinct (map #(:name (project-by-dir (tab-project %))) kept)))))
                         "\n")))))
    (text 503 (str "No DevTools port on " devtools-port ". Restart mdserve so the "
                   "browser launches with --remote-debugging-port, then try again.\n"))))

(defn window-groups
  "Remembered tabs grouped into the windows they should be replayed AS: one per
   project, plus a leftover window for everything belonging to none. Each group
   is [window-class tabs], sorted by tab-order and topped up with an Explorer
   when it has none — so every window gets its Explorer. The leftover group
   comes first and always exists (the panel belongs to no project), which is
   what replay-tabs! needs: its first url is the one that starts the browser.

   Windows are reconstructed rather than recorded: DevTools' /json/list reports
   no window, so the grouping comes from what each tab belongs to."
  [tabs]
  (let [by-proj (group-by tab-project tabs)
        top-up  (fn [dir es]
                  (let [es (vec (distinct (map nav-tab->root es)))]
                    (sort-tabs (cond-> es
                                 (not (has-nav-tab? es))
                                 (conj {:path (str "/view" (enc-path dir))})))))]
    (into [["mdserve" (top-up (or (some-> (:home-dir config) normal-dir)
                                  (System/getProperty "user.dir"))
                              (or (get by-proj nil) [{:path "/"}]))]]
          (for [{:keys [name dir]} projects
                :let [es (seq (get by-proj dir))]
                :when es]
            [(str "mdserve-" name) (top-up dir es)]))))

(defn replay-tabs!
  "Open a remembered set as the WINDOWS it came from rather than as one flat pile
   of tabs: the first group (the panel's) starts the browser, and every other
   group opens as its own window through open-window-with!.

   The very first url must go through open-url-with-debug-port!: Chrome honours
   --remote-debugging-port only on the process that STARTS the profile, and
   open-window-with! does not pass it. That first window therefore carries no
   --class, which only costs it a GNOME auto-move rule it never had."
  [groups]
  (let [[[_ first-tabs] & more] groups]
    (open-url-with-debug-port! (tab->url (first first-tabs)))
    ;; brief pause so the first launch establishes the browser profile before
    ;; the next ones arrive as tabs (rather than racing into a separate window).
    (Thread/sleep 600)
    (doseq [t (rest first-tabs)]
      (open-url! (tab->url t))
      (Thread/sleep 400))
    (doseq [[cls ts] more]
      (open-window-with! cls (mapv tab->url ts))
      (Thread/sleep 800))))

(defn- port-up?
  "True if localhost:port is accepting TCP connections (short timeout)."
  [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300)
      true)
    (catch Exception _ false)))

(defn- pid-alive? [pid]
  (try (.isAlive (.orElseThrow (java.lang.ProcessHandle/of pid)))
       (catch Exception _ false)))

(defn- wait-for
  "Poll pred every 500ms until true or timeout-ms elapses. Returns pred's verdict."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred)                                     true
        (> (System/currentTimeMillis) deadline)    false
        :else (do (Thread/sleep 500) (recur))))))

(defn- port-owner-pids
  "Pids listening on localhost:port (lsof; own processes only, no sudo)."
  [port]
  (try
    (->> (:out (p/sh "lsof" "-t" (str "-iTCP:" port) "-sTCP:LISTEN"))
         str/split-lines
         (keep parse-long))
    (catch Exception _ [])))

(def ansi-fg
  "SGR fg codes 30-37 / bright 90-97 → hex, picked to read on light AND dark."
  {30 "#6b7672" 31 "#d03030" 32 "#2e9e44" 33 "#b08800"
   34 "#2f7fd0" 35 "#c04ad0" 36 "#1fa8a0" 37 "#9aa5a1"
   90 "#7d8884" 91 "#f06a6a" 92 "#5ecb78" 93 "#d0a520"
   94 "#66a8e8" 95 "#d883e0" 96 "#54c8c0" 97 "#c8d2ce"})

(defn- sgr-apply
  "Fold SGR codes into a {:fg hex :bold true} style state. Empty codes = reset
   (ESC[m). Unknown codes ignored."
  [state codes]
  (if (empty? codes)
    {}
    (reduce (fn [st c]
              (cond
                (= 0 c)               {}
                (= 1 c)               (assoc st :bold true)
                (= 22 c)              (dissoc st :bold)
                (= 39 c)              (dissoc st :fg)
                (contains? ansi-fg c) (assoc st :fg (ansi-fg c))
                :else                 st))
            state codes)))

(defn- sgr-span-open [{:keys [fg bold]}]
  (when (or fg bold)
    (str "<span style=\"" (when fg (str "color:" fg ";"))
         (when bold "font-weight:bold") "\">")))

(defn ansi->html
  "HTML-escape raw log text, translate SGR colour/bold escapes to styled spans,
   and strip all other escape sequences (cursor moves, OSC titles, ...)."
  [raw]
  (let [txt (-> (escape-html raw)
                (str/replace #"\u001b\][^\u0007\u001b]*(\u0007|\u001b\\)" "") ; OSC
                (str/replace #"\u001b\[([0-9;?]*)([A-Za-z])"                  ; CSI: keep only SGR
                             (fn [[whole _ final]] (if (= final "m") whole "")))
                (str/replace #"\u001b(?!\[)." ""))]                           ; stray ESC-x
    (loop [pieces (str/split txt #"(?=\u001b\[[0-9;]*m)")
           state  {}
           out    (StringBuilder.)]
      (if-let [piece (first pieces)]
        (let [[_ codes body*] (re-matches #"(?s)\u001b\[([0-9;]*)m(.*)" piece)
              state' (if codes
                       (sgr-apply state (keep parse-long (str/split codes #";")))
                       state)
              body   (or body* piece)]
          (when (seq body)
            (if-let [open (sgr-span-open state')]
              (doto out (.append open) (.append body) (.append "</span>"))
              (.append out body)))
          (recur (rest pieces) state' out))
        (str out)))))

;; ---------------------------------------------------------------------------
;; Command buttons. Each project (and the panel, as group "home") lists
;; commands in config.edn; its dashboard at /commands/<group> runs them.
;;   {:id "web" :label "Dev server" :run "my-server --port 8777" :ports [8777]}
;;   {:id "build" :label "Build" :run "make build" :once true}
;; A SERVICE (the default) keeps running: Launch / Stop / Restart, its dot taken
;; from its ports. A ONE-SHOT (:once true) runs to an exit code: Run, and its
;; dot says running / ok / failed. Both are launched detached (setsid), so they
;; outlive an mdserve restart, and both write a log you open as its own tab.
;; Status is computed on request — nothing polls unless you tick Auto-refresh.

(def id-re
  "What a group or command id may contain. They end up in URLs, file names and
   onclick attributes, so the set is kept small rather than escaped everywhere."
  #"[A-Za-z0-9._-]+")

(def cmd-path
  "Directories put at the front of PATH for every command: config.edn's :path,
   default [\"~/bin\"]."
  (str/join ":" (map normal-dir (or (:path config) ["~/bin"]))))

(defn- cmd-file [group id ext]
  (io/file state-dir group (str id "." ext)))

(defn- cmd-pid
  "The pid recorded when this command was last started from here, or nil."
  [group id]
  (let [f (cmd-file group id "pid")]
    (when (.exists f)
      (parse-long (str/trim (slurp f))))))

(defn- cmd-find [group id]
  (some #(when (= id (str (:id %))) %) (commands-of group)))

(defn- valid-commands
  "group's commands with a usable :id and :run. The rest are reported on the
   console rather than rendered half-working."
  [group]
  (filterv (fn [{:keys [id run] :as c}]
             (or (and (re-matches id-re (str id)) (string? run))
                 (do (println "  skipping command in" group "- needs :id (letters, digits, . _ -) and :run:" (pr-str c))
                     false)))
           (commands-of group)))

(defn- group-dir
  "Where a group's commands run unless a command names its own :dir: the
   project's root, or the home directory for the panel's commands."
  [group]
  (or (:dir (project-by-name group)) home))

(defn- one-shot? [cmd] (boolean (:once cmd)))

(defn cmd-status
  "A service: \"up\" (a port answers, or with no ports, its pid is alive) |
   \"boot\" (pid alive, no port yet) | \"crashed\" (started here, pid dead) |
   \"down\". A one-shot: \"running\" | \"ok\" | \"failed\" | \"idle\" (never run)."
  [group {:keys [id ports] :as cmd}]
  (let [pid    (cmd-pid group id)
        alive? (boolean (and pid (pid-alive? pid)))]
    (if (one-shot? cmd)
      (let [ex (cmd-file group id "exit")]
        (cond
          alive?       "running"
          (.exists ex) (if (= "0" (str/trim (slurp ex))) "ok" "failed")
          :else        "idle"))
      (cond
        (some port-up? ports) "up"
        alive?                (if (seq ports) "boot" "up")
        pid                   "crashed"
        :else                 "down"))))

(defn- shq
  "s single-quoted for bash."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(def setsid-argv
  "Prefix that runs a command in a new session of its own. Linux has setsid(1);
   macOS does not, so there perl calls setsid(2) and execs the command, which
   keeps the pid."
  (if (on-path? "setsid")
    ["setsid"]
    ["perl" "-MPOSIX=setsid" "-e" "setsid() or die $!; exec @ARGV or die $!"]))

(defn- cmd-start!
  "Run cmd DETACHED: setsid gives it its own session (setsid-argv: perl on
   macOS), so it survives an mdserve restart and Stop can signal its whole process group. The recorded pid is the
   wrapping shell's own ($$), which for a service then execs the command, so the
   pid IS the service. Output goes to the log; a one-shot also records its exit
   code. The command text travels in an environment variable, never spliced
   into the script, so its quoting is its own business."
  [group {:keys [id run dir] :as cmd}]
  (let [log (cmd-file group id "log")
        wd  (if dir (normal-dir dir) (group-dir group))]
    (.mkdirs (io/file state-dir group))
    (.delete (cmd-file group id "exit"))
    (spit log (str "$ " run "\n  (in " wd ", started " (java.time.LocalDateTime/now) ")\n\n"))
    (p/process (into setsid-argv
                     ["bash" "-c"
                      (str "echo $$ > " (shq (cmd-file group id "pid"))
                           "; export PATH=" (shq cmd-path) ":\"$PATH\""
                           "; cd " (shq wd) " || exit 1; "
                           (if (one-shot? cmd)
                             (str "bash -c \"$MDSERVE_RUN\" >> " (shq log) " 2>&1; code=$?"
                                  "; echo $code > " (shq (cmd-file group id "exit"))
                                  "; echo \"--- exit $code\" >> " (shq log))
                             (str "exec bash -c \"$MDSERVE_RUN\" >> " (shq log) " 2>&1")))])
               {:extra-env {"MDSERVE_RUN" run}
                :out (io/file "/dev/null") :err (io/file "/dev/null")})
    nil))

(defn- cmd-stop!
  "SIGTERM the recorded process GROUP (setsid made our pid its leader), then any
   process still listening on the command's ports — so a server started by hand
   from a terminal is stopped too. Removes the pidfile so a service reads down."
  [group {:keys [id ports]}]
  (when-let [pid (cmd-pid group id)]
    ;; bash's own kill: macOS's /bin/kill cannot be relied on for "-- -pgid"
    (try @(p/process ["bash" "-c" (str "kill -TERM -- -" pid)]) (catch Exception _ nil))
    (wait-for #(not (pid-alive? pid)) 5000))
  (when (seq ports)
    (wait-for #(not (some port-up? ports)) 3000)
    (doseq [port ports, pid (port-owner-pids port)]
      (try @(p/process ["kill" "-TERM" (str pid)]) (catch Exception _ nil)))
    (wait-for #(not (some port-up? ports)) 3000))
  (.delete (cmd-file group id "pid"))
  nil)

(defn- cmd-ensure-up!
  "Start a service unless it is already up or booting, then wait (≤40s) for a
   port. A one-shot is started unless it is already running, and not waited for."
  [group cmd]
  (let [st (cmd-status group cmd)]
    (if (one-shot? cmd)
      (when-not (= "running" st) (cmd-start! group cmd))
      (do (when (#{"down" "crashed"} st) (cmd-start! group cmd))
          (when (seq (:ports cmd))
            (wait-for #(some port-up? (:ports cmd)) 40000))))))

(defn- commands-url [group] (str base "/commands/" group))

(defn- group-title [group]
  (if (= group "home") "Home" group))

(def cmd-dot-chars
  {"up" "●" "boot" "◐" "crashed" "✕" "down" "○"
   "ok" "●" "running" "◐" "failed" "✕" "idle" "○"})

(defn- cmd-log-url [group id] (str base "/commands/" group "/log/" id))

(defn- cmd-btn
  "A dashboard button → cmdGo(this, url, active-label[, log tab]). With a log
   tab, the tab is opened at click time — window.open only counts as the user's
   own while the click is being handled — and pointed at the log once the
   command has started."
  [label active title url & [log-url log-name]]
  (str "<button class=\"pact\" title=\"" (escape-html title) "\" onclick=\"cmdGo(this,'"
       url "','" active "'"
       (when log-url (str ",{url:'" log-url "?follow=1',name:'" log-name "'}"))
       ")\">" label "</button>"))

(defn- cmd-row [group {:keys [id label ports port-roles] :as cmd}]
  (let [st     (cmd-status group cmd)
        u      (fn [verb] (str base "/commands/" group "/" verb "/" id))
        chips  (apply str (map-indexed
                           (fn [i pt]
                             (let [role (nth (vec port-roles) i nil)]
                               (str "<span class=\"portchip\">" pt
                                    (when role (str " " (escape-html role))) "</span>")))
                           ports))
        stop   (cmd-btn "■ Stop" "… Stopping" (str "Stop " id) (u "stop"))
        btns   (if (one-shot? cmd)
                 (if (= "running" st)
                   stop
                   (cmd-btn "▶ Run" "… Starting" (str "Run " id ", opening its log")
                            (u "launch") (cmd-log-url group id) (str "mdserve-log-" group "-" id)))
                 (case st
                   "up"   (str stop (cmd-btn "↻ Restart" "… Restarting" (str "Restart " id) (u "restart")))
                   "boot" stop
                   (cmd-btn "▶ Launch" "… Launching"
                            (str "Launch " id (when (seq ports) " (waits until a port answers, ≤40s)"))
                            (u "launch"))))
        log    (if (.exists (cmd-file group id "log"))
                 (str "<a class=\"pact\" href=\"" (cmd-log-url group id)
                      "\" target=\"_blank\" title=\"View " id "'s log\">👁 log</a>")
                 "<span class=\"pact off\" title=\"No log yet — not started from here\">👁 log</span>")]
    (str "<tr>"
         "<td><span class=\"dot st-" st "\" id=\"dot-" id "\">" (cmd-dot-chars st) "</span>" chips "</td>"
         "<td><code>" id "</code>"
         (when label (str "<span class=\"cmd-note\">" (escape-html label) "</span>"))
         "</td>"
         "<td>" btns log "</td></tr>")))

(defn- cmd-script
  "The dashboard's buttons and opt-in auto-refresh. An action freezes the whole
   board until the page reloads, so nothing else can be pressed mid-action;
   success reloads (fresh dots), failure unfreezes. Auto-refresh (?auto=1) polls
   the status JSON every 2s and reloads only when a dot would change.
   Plain functions, no arrows: code here and in onclick attributes moves back
   and forth, and an attribute needs > written as &gt;, which a <script>
   block does not decode."
  [group]
  (str "<script>"
       "function cmdKeep(){try{sessionStorage.setItem('mdserveScrollY',String(scrollY))}catch(e){}}"
       "function cmdGoAuto(on){location.href='" (commands-url group) "'+(on?'?auto=1':'');}"
       "function cmdGo(b,u,a,log){var lbl=b.textContent.trim();document.body.classList.add('cmd-busy');"
       "var r=b.closest('tr'),d=r&&r.querySelector('.dot');if(d)d.classList.add('dot-busy');b.textContent=a;"
       "var w=log?window.open('',log.name):null;"
       "fetch(u,{method:'POST'}).then(function(x){return x.ok?x.text():Promise.reject();})"
       ".then(function(x){if(w){w.location.href=log.url;}cmdKeep();"
       "try{sessionStorage.setItem('mdserveToast','✓ Done — '+lbl)}catch(e){};location.href=x.trim();})"
       ".catch(function(){if(w){w.close();}document.body.classList.remove('cmd-busy');"
       "if(d)d.classList.remove('dot-busy');b.textContent='⚠ Failed';});}"
       "function cmdPoll(){fetch('" (commands-url group) "/status').then(function(r){return r.json();})"
       ".then(function(m){for(var id in m){var d=document.getElementById('dot-'+id);"
       "if(d){var cur=(d.className.match(/st-(\\w+)/)||[])[1];"
       "if(cur!==m[id]){cmdKeep();location.href='" (commands-url group) "?auto=1';return;}}}}).catch(function(){});}"
       "var _cb=document.querySelector('.cmd-auto input');"
       "if(_cb&&_cb.checked){setInterval(cmdPoll,2000);}"
       "</script>"))

(defn commands-page [group auto?]
  (let [cmds     (valid-commands group)
        services (remove one-shot? cmds)
        active   (count (filter #(#{"up" "boot"} (cmd-status group %)) services))
        dir      (group-dir group)
        inner    (str "<h1>" (escape-html (group-title group)) "</h1>"
                      "<p><a href=\"" base "/view" (enc-path dir) "\">" (escape-html dir) "</a>"
                      (when (seq services)
                        (str " · "
                             (if (= active (count services))
                               "<button class=\"pact off\" disabled title=\"Every service is up\">▶ Start all</button>"
                               (action-button "pact" "▶ Start all" "… Starting"
                                              "Start every service that is down, in order"
                                              (str (commands-url group) "/start-all")))
                             (if (zero? active)
                               "<button class=\"pact off\" disabled title=\"Nothing is running\">■ Stop all</button>"
                               (action-button "pact" "■ Stop all" "… Stopping" "Stop every service"
                                              (str (commands-url group) "/stop-all")))))
                      "<label class=\"cmd-auto\"><input type=\"checkbox\" " (when auto? "checked ")
                      "onchange=\"cmdGoAuto(this.checked)\"> Auto-refresh (2s)</label></p>"
                      (if (seq cmds)
                        (str "<table class=\"cmds\"><tbody>" (apply str (map #(cmd-row group %) cmds)) "</tbody></table>")
                        (str "<p>No commands yet. Add a <code>:commands</code> list to this "
                             (if (= group "home") "file's top level" "project") " in <code>"
                             (escape-html config-file) "</code> and reload; no restart needed.</p>"))
                      (cmd-script group))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (layout (str (group-title group) " — commands") inner nil {:no-toolbar true :dot :dev})}))

(defn commands-status-json [group]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string (into {} (map (juxt :id #(cmd-status group %))) (valid-commands group)))})

(defn commands-log
  "Render a command's log: the last 200 lines by default (?full=1 for all),
   terminal colours as HTML, scrolled to the bottom. ?follow=1 reloads every 2s;
   for a one-shot it stops by itself once the command has finished."
  [group id query-string]
  (let [params  (set (str/split (or query-string "") #"&"))
        full?   (contains? params "full=1")
        follow? (contains? params "follow=1")
        f       (cmd-file group id "log")
        cmd     (cmd-find group id)
        self    (cmd-log-url group id)]
    (if-not (.exists f)
      (text 404 (str "No log for " group "/" id " (not started from here):\n" (.getPath f) "\n"))
      (let [lines  (str/split-lines (slurp f))
            shown  (if full? lines (take-last 200 lines))
            toggle (fn [label on? qs] (if on?
                                        (str "<strong>" label ": on</strong> · <a href=\"" self "\">off</a>")
                                        (str label ": off · <a href=\"" self "?" qs "\">on</a>")))
            inner  (str "<h1><code>" id "</code> — log</h1>"
                        "<p>" (count lines) " lines"
                        (when-not full? (str " (last " (count shown) " shown · <a href=\"" self "?full=1\">full log</a>)"))
                        " · " (toggle "Follow" follow? (str "follow=1" (when full? "&full=1")))
                        " · <a href=\"" (commands-url group) "\">← " (escape-html (group-title group)) "</a></p>"
                        "<pre id=\"cmd-log\"><code>" (ansi->html (str/join "\n" shown)) "</code></pre>"
                        "<script>window.scrollTo(0,document.body.scrollHeight);"
                        (cond
                          (not follow?) nil
                          (and cmd (one-shot? cmd))
                          (str "fetch('" (commands-url group) "/status')"
                               ".then(function(r){return r.json();})"
                               ".then(function(m){if(m['" id "']==='running')"
                               "{setTimeout(function(){location.reload();},2000);}"
                               "else{location.replace('" self (when full? "?full=1") "');}})"
                               ".catch(function(){setTimeout(function(){location.reload();},2000);});")
                          :else "setTimeout(function(){location.reload();},2000);")
                        "</script>")]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (layout (str id " — log") inner nil {:copy-selector "#cmd-log" :dot :log})}))))

(defn commands-route
  "Everything under /commands/<group>. Actions are POST-only; each returns the
   dashboard's URL, which the button then loads."
  [uri request-method query-string]
  (let [[_ group verb id] (re-matches #"/commands/([^/]+)(?:/([a-z-]+)(?:/([^/]+))?)?" uri)
        group (some-> group (URLDecoder/decode "UTF-8"))
        known? (or (= group "home") (project-by-name group))
        cmd   (when id (cmd-find group id))
        act   (fn [f] (if (= request-method :post)
                        (do (f) (text 200 (str (commands-url group) "\n")))
                        (text 405 "POST only\n")))]
    (cond
      (not known?)                 (text 404 (str "No such project: " group "\n"))
      (nil? verb)                  (commands-page group (boolean (and query-string (str/includes? query-string "auto=1"))))
      (= verb "status")            (commands-status-json group)
      (= verb "start-all")         (act #(doseq [c (remove one-shot? (valid-commands group))] (cmd-ensure-up! group c)))
      (= verb "stop-all")          (act #(doseq [c (reverse (remove one-shot? (valid-commands group)))] (cmd-stop! group c)))
      (and (= verb "log") id)      (commands-log group id query-string)
      (nil? cmd)                   (text 404 (str "No such command: " group "/" id "\n"))
      (= verb "launch")            (act #(cmd-ensure-up! group cmd))
      (= verb "stop")              (act #(cmd-stop! group cmd))
      (= verb "restart")           (act #(do (cmd-stop! group cmd) (cmd-ensure-up! group cmd)))
      :else                        (text 404 "not found\n"))))

(defn- terminal-run-argv
  "argv to open a terminal in dir RUNNING cmd-argv, or nil when no terminal is
   known."
  [dir cmd-argv]
  (some-> (second terminal-config) (fill-template dir cmd-argv)))

(defn vi
  "Open a terminal editing this file, in the file's own directory (detached).
   The editor is $VISUAL, else $EDITOR, else vi. Body unused — the button stays
   on the page, like the directory Terminal button."
  [path]
  (let [f      (io/file path)
        editor (or (System/getenv "VISUAL") (System/getenv "EDITOR") "vi")
        argv   (when (.isFile f)
                 (terminal-run-argv (.getParent f)
                                    (conj (str/split (str/trim editor) #"\s+") (.getPath f))))]
    (cond
      (not (.isFile f)) (text 404 (str "Not a file:\n" path "\n"))
      (nil? argv)       (text 500 "No terminal known: set :terminal-run in config.edn\n")
      :else
      (do
        (try
          (p/process argv {:out (io/file "/dev/null") :err (io/file "/dev/null")})
          (catch Exception e (println "  editor terminal error:" (.getMessage e))))
        (text 200 "opened")))))

(def tablet-port
  "LAN-facing port for the tablet reading list, or 0 for none (the default).
   Set :tablet-port in config.edn, or MDSERVE_TABLET_PORT."
  (or (some-> (System/getenv "MDSERVE_TABLET_PORT") parse-long)
      (:tablet-port config)
      0))

(def tablet-bind
  "Interface the tablet listener binds. 0.0.0.0 = reachable from the LAN, which
   is the entire point of it; narrow it with :tablet-bind or MDSERVE_TABLET_BIND."
  (or (System/getenv "MDSERVE_TABLET_BIND") (:tablet-bind config) "0.0.0.0"))

(def tablet-file
  "The reading list, newest-first. Deliberately outside the browser profile for
   the same reason as tabs-file: wiping the profile shouldn't cost the list."
  (or (System/getenv "MDSERVE_TABLET")
      (str home "/.config/mdserve-starter/tablet.edn")))

(def lan-ip
  "This machine's LAN address, for printing the tablet bookmark. Asks the
   routing table which local interface would reach the outside world — a UDP
   connect only binds, no packet leaves — and falls back to the hostname lookup."
  (or (try
        (with-open [s (java.net.DatagramSocket.)]
          (.connect s (java.net.InetAddress/getByName "8.8.8.8") 9)
          (.getHostAddress (.getLocalAddress s)))
        (catch Exception _ nil))
      (try (.getHostAddress (java.net.InetAddress/getLocalHost))
           (catch Exception _ nil))
      "localhost"))

(def tablet-base (str "http://" lan-ip ":" tablet-port))

(defn read-tablet-list
  "The pushed docs, newest first. Never throws: a missing or corrupt file reads
   as empty, since a broken list must not take the tablet page down."
  []
  (try
    (let [f (io/file tablet-file)]
      (if (.exists f)
        (vec (filter string? (edn/read-string (slurp f))))
        []))
    (catch Exception _ [])))

(defn tablet-push!
  "Put `path` at the head of the reading list, de-duplicated, capped at 50 so an
   afternoon of pushes can't grow it without bound. Returns the new list."
  [path]
  (let [lst (vec (take 50 (cons path (remove #(= % path) (read-tablet-list)))))]
    (io/make-parents tablet-file)
    (spit tablet-file (pr-str lst))
    lst))

(defn tablet-layout
  "layout's couch-side sibling: the same css, none of the desktop chrome.
   `layout` bakes `base` (localhost:7000) into the toolbar — and localhost on
   a tablet is the TABLET, so those buttons would silently fail there. Nothing here points anywhere but this server."
  [title inner]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (escape-html title) "</title><style>" css "</style>"
       "<style>.tl{list-style:none;padding:0}.tl li{margin:0 0 14px}"
       ".tl a{display:block;padding:14px 16px;border:1px solid var(--line);"
       "border-radius:8px;text-decoration:none;font-size:18px}"
       ".tl small{display:block;opacity:.6;font-size:13px;margin-top:4px}"
       ".back{display:inline-block;margin:0 0 18px;font-size:17px}</style>"
       "</head><body><main class=\"md\">" inner "</main></body></html>"))

(defn tablet-url?
  "Whether a reading-list entry is a remote page rather than a local file."
  [entry]
  (boolean (re-find #"^https?://" (str entry))))

(defn tablet-index
  "The bookmark page: every doc put up for the tablet, newest first."
  []
  (let [lst   (read-tablet-list)
        items (for [p lst
                    :let [url? (tablet-url? p)
                          f (when-not url? (io/file p))
                          there? (boolean (and f (.exists f)))]]
                (if url?
                  (str "<li><a href=\"" (escape-html p) "\" rel=\"noreferrer\">"
                       (escape-html p)
                       "<small>the page itself, not a copy</small></a></li>")
                  (str "<li><a href=\"/doc" (enc-path p) "\">"
                       (escape-html
                        (if there?
                          (doc-title p (try (slurp f) (catch Exception _ "")))
                          (.getName f)))
                       "<small>" (escape-html p)
                       (when-not there? " — MISSING") "</small></a></li>")))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (tablet-layout
            "Reading list"
            (str "<h1>Reading list</h1>"
                 (if (seq lst)
                   (str "<ul class=\"tl\">" (str/join items) "</ul>")
                   "<p>Nothing put up for the tablet yet.</p>")))}))

(defn tablet-doc
  "Render one doc off the reading list. The allowlist check is the security
   boundary of this whole feature: the server is LAN-facing, so rendering an
   arbitrary absolute path would hand anything on the wifi a read of the entire
   home directory. Only paths actually on the list render."
  [uri]
  (let [path (suffix uri "/doc")
        f    (io/file path)]
    (cond
      (not (some #(and (= % path) (not (tablet-url? %))) (read-tablet-list)))
      (text 403 "Not on the reading list.\n")

      (not (.exists f))
      (text 404 (str "No such file:\n" path "\n"))

      :else
      (let [body (slurp f)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (tablet-layout
                (doc-title path body)
                (str "<a class=\"back\" href=\"/\">&larr; Reading list</a>"
                     (md-body-html-with-health path body)))}))))

(defn tab-show
  "\"put it up on the tab\": queue a doc for the tablet reading list. A sibling
   of `show`, not a branch inside it — this one opens NOTHING on the desktop,
   which is the whole point of saying it."
  [uri]
  (let [path (suffix uri "/tab-show")]
    (if (.exists (io/file path))
      (do (tablet-push! path)
          (text 200 (str tablet-base "/doc" (enc-path path) "\n")))
      (text 404 (str "No such file or directory:\n" path "\n")))))

(defn tab-link
  "\"put the URL up on the tab\": queue a remote page for the tablet reading list.

   The sibling of `tab-show` for something that is not a file. Nothing is fetched and
   nothing is converted - the entry IS the URL, and the tablet's browser goes straight to
   the site when it is tapped. `/doc` is therefore untouched: its allowlist still admits
   only local paths, so this adds an outbound link to a LAN page without widening one
   byte of what the LAN can read.

   Called as /tab-link?url=<the url>."
  [query-string]
  (let [url (some-> (->> (str/split (or query-string "") #"&")
                         (map #(str/split % #"=" 2))
                         (filter #(= "url" (first %)))
                         first
                         second)
                    (java.net.URLDecoder/decode "UTF-8"))]
    (if (tablet-url? url)
      (do (tablet-push! url)
          (text 200 (str tablet-base "/ -> " url "\n")))
      (text 400 (str "Not an http(s) URL:\n" url "\n")))))

(defn show-link
  "Open an ordinary http(s) URL as a tab in the mdserve browser — the sibling of
   show for something that is not a file. Nothing is fetched or converted; the
   tab goes to the real site. Remember Tabs keeps it like any other external tab.

   Called as /show-link?url=<url>[&ws=<n>]. query-param splits the query string
   on &, so a url carrying one must arrive percent-encoded:

     curl -sG --data-urlencode 'url=https://example.com' 'http://localhost:7000/show-link?ws=1'

   A tab already on the url is ACTIVATED, not reloaded (a link did not change,
   unlike a re-shown doc). Otherwise ?ws=<n> picks the window as for /show, and
   without it the link goes to the panel's window. Reuse matches the url as
   given, so a site that redirects never matches the tab it opened.

   The url is validated here: scheme http or https, a host, no whitespace or
   quotes. It reaches a Runtime.evaluate (open-url-via-opener! JSON-encodes it),
   so this is defence in depth rather than the only guard."
  [query-string]
  (let [url (query-param query-string "url")
        ok? (boolean (and url
                          (not (re-find #"[\s\"']" url))
                          (let [u (try (java.net.URI. url) (catch Exception _ nil))]
                            (and u
                                 (#{"http" "https"} (.getScheme u))
                                 (some? (.getHost u))))))]
    (if-not ok?
      (text 400 (str "Not an http(s) URL:\n" url "\n"))
      (let [[ws-root warn] (ws-target query-string)]
        (when warn (println " " warn))
        (locking placing
          (if-let [open-on-it (first (filter #(= url (:url %)) (page-targets)))]
            (do (println "  reusing tab for" url)
                (activate-target! (:id open-on-it)))
            (place-url! url (when-not (= :none ws-root) ws-root))))
        (text 200 (str url "\n" (when warn (str warn "\n"))))))))

(defn tablet-app
  "The tablet server's whole route table. Two routes and a 404 — nothing here
   runs a command, reads a key, or touches the browser."
  [{:keys [uri request-method]}]
  (println (str (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss"))
                "  tablet " (name request-method) " " uri))
  (cond
    (= uri "/") (tablet-index)
    (= uri "/health") (text 200 "ok\n")
    (str/starts-with? uri "/doc") (tablet-doc uri)
    :else (text 404 "not found\n")))

(def tablet-off-file
  "Present means the tablet server is turned off, and it stays off across
   restarts until turned on again. Beside the reading list, so MDSERVE_TABLET
   moves both."
  (str tablet-file ".off"))

(def tablet-server
  "The running tablet server's stop function, or nil when it is not running."
  (atom nil))

(defn tablet-start!
  "Start the tablet server unless it is running, disabled for this run by
   MDSERVE_TABLET_PORT=0, or turned off. True when it is running."
  []
  (locking tablet-server
    (when (and (pos? tablet-port) (nil? @tablet-server)
               (not (.exists (io/file tablet-off-file))))
      (reset! tablet-server (srv/run-server tablet-app {:ip tablet-bind :port tablet-port})))
    (some? @tablet-server)))

(defn tablet-off!
  "Stop the tablet server at once, so the tablet cannot connect, and mark it
   off so a restart does not bring it back. The reading list is kept."
  []
  (locking tablet-server
    (io/make-parents tablet-off-file)
    (spit tablet-off-file "Tablet server turned off. Delete this file, or press Tablet on in the panel, to turn it on.\n")
    (when-let [stop @tablet-server]
      (stop :timeout 100)
      (reset! tablet-server nil))
    (println "  tablet server OFF")
    (text 200 "off\n")))

(defn tablet-on!
  "Clear the off mark and start the tablet server again."
  []
  (io/delete-file tablet-off-file true)
  (if (tablet-start!)
    (do (println "  tablet server ON:" tablet-base)
        (text 200 "on\n"))
    (text 409 "not started: MDSERVE_TABLET_PORT=0 for this run\n")))

(defn tablet-status []
  (text 200 (cond @tablet-server                     "on\n"
                  (.exists (io/file tablet-off-file)) "off\n"
                  :else                               "disabled for this run (MDSERVE_TABLET_PORT=0)\n")))

(defn- project-lines
  "The panel's list of projects: name, workspace, folder, dashboard."
  []
  (if (empty? projects)
    (str "No projects configured, so everything opens in this one window. "
         "Add some with `bb install.bb`, or by hand in `" config-file "`.\n")
    (str/join "\n"
              (for [{:keys [name dir workspace]} projects]
                (str "- **" name "**"
                     (when workspace (str " — workspace " workspace))
                     " — [" dir "](" base "/view" (enc-path dir) ")"
                     " · [commands](" base "/commands/" name ")")))))

;; --- /sessions (Claude Code sessions, one tab per workspace window) ---
;; Design and data sources: MDSERVE_SESSIONS.md. Everything read here is Claude
;; Code's own internal files, not a documented API, so every field is optional.

(defn- sessions-config
  "The :sessions map from config.edn over the defaults, plus the :projects and
   :panel-workspace that place a session. Re-read on every refresh, so a change
   needs no restart. Paths may start with ~."
  []
  (let [c (read-config)
        s (merge {:claude-dirs       ["~/.claude"]
                  :history-file      (str (.getParent (io/file config-file)) "/session-history.edn")
                  :context-window    1000000
                  :context-warn-pct  60
                  :context-alarm-pct 80}
                 (:sessions c))]
    (assoc s
           :claude-dirs     (mapv expand-home (:claude-dirs s))
           :history-file    (expand-home (:history-file s))
           :projects        (mapv #(-> %
                                       (update :dir expand-home)
                                       (update :session-dirs (partial mapv expand-home)))
                                  (:projects c))
           :panel-workspace (:panel-workspace c))))

(defn- under-dir? [dir path]
  (and dir path (or (= path dir) (str/starts-with? path (str dir "/")))))

(defn session-workspace
  "The workspace a session belongs to: the :workspace of the project whose
   :dir or :session-dirs holds its cwd, else :panel-workspace."
  [{:keys [projects panel-workspace]} cwd]
  (or (some (fn [{:keys [dir workspace session-dirs]}]
              (when (some #(under-dir? % cwd) (cons dir session-dirs)) workspace))
            projects)
      panel-workspace))

(defn- ->date [x]
  (try
    (cond
      (number? x) (java.util.Date. (long x))
      (string? x) (java.util.Date/from (java.time.Instant/parse x)))
    (catch Exception _ nil)))

(defn- session-pid-alive?
  "True when pid is running and is the same process that wrote the session file:
   a pid can be reused after a session exits. On Linux, procStart is field 22 of
   /proc/<pid>/stat, counted after the ')' that ends the command name, since the
   name itself can hold spaces. Where there is no /proc (macOS) the check is that
   the process started no later than the session (startedAt, ms, with a minute's
   slack): a reused pid belongs to a process that started after it."
  [pid proc-start started-at]
  (if (.exists (io/file "/proc/self/stat"))
    (when-let [stat (proc-read (str "/proc/" pid "/stat"))]
      (or (nil? proc-start)
          (let [after (subs stat (inc (str/last-index-of stat ")")))
                fields (str/split (str/trim after) #"\s+")]
            (= (str proc-start) (nth fields 19 nil)))))
    (when-let [ph (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
      (and (.isAlive ph)
           (let [start (.orElse (.startInstant (.info ph)) nil)]
             (or (nil? start) (not (number? started-at))
                 (<= (.toEpochMilli start) (+ (long started-at) 60000))))))))

(defn- live-session-files
  "Every running session's file, as maps with :claude-dir added. A file whose
   process is gone is a dead session and is left out."
  [claude-dirs]
  (for [d     claude-dirs
        f     (sort (.listFiles (io/file d "sessions")))
        :when (str/ends-with? (.getName f) ".json")
        :let  [m (try (json/parse-string (slurp f) true) (catch Exception _ nil))]
        :when (and (:sessionId m) (:pid m) (session-pid-alive? (:pid m) (:procStart m) (:startedAt m)))]
    (assoc m :claude-dir d)))

(defn- transcript-file
  "The session's transcript: projects/<cwd with every non-alphanumeric as ->/
   <sessionId>.jsonl, else the same name in any project folder."
  [claude-dir cwd sid]
  (let [f (io/file claude-dir "projects" (str/replace (str cwd) #"[^A-Za-z0-9]" "-") (str sid ".jsonl"))]
    (if (.isFile f)
      f
      (some #(let [g (io/file % (str sid ".jsonl"))] (when (.isFile g) g))
            (.listFiles (io/file claude-dir "projects"))))))

(defn- parse-line [s]
  (try (json/parse-string s true) (catch Exception _ nil)))

(defn- usage-tokens
  "The context size an assistant line reports: all three input counts."
  [m]
  (let [u (get-in m [:message :usage])]
    (when u
      (+ (or (:input_tokens u) 0)
         (or (:cache_read_input_tokens u) 0)
         (or (:cache_creation_input_tokens u) 0)))))

(defn scan-transcript
  "Read a transcript from byte offset from up to its last complete line. Only
   the new bytes are read, so after the first pass a refresh costs what was
   added. Lines are picked out by a substring check before any JSON parse.
   Returns the recaps and compactions found, the newest title, prompt, context
   size and activity time (nil when that part held none), and :read-to."
  [^java.io.File f from]
  (let [len  (.length f)
        from (if (> from len) 0 from)
        hold (fn [v s] (if (>= (count v) 5) (conj (subvec v 1) s) (conj v s)))]
    (with-open [in (java.io.FileInputStream. f)]
      (.skip in from)
      (let [rdr (java.io.BufferedReader. (java.io.InputStreamReader. in "UTF-8") (* 1024 1024))
            avail (- len from)]
        (loop [used 0 acc {:recaps [] :compactions [] :assistants [] :users []}]
          (let [line (when (< used avail) (.readLine rdr))
                n    (when line (inc (alength (.getBytes ^String line "UTF-8"))))]
            (if (or (nil? line) (> (+ used n) avail))
              (let [mains  (keep parse-line (rseq (:assistants acc)))
                    main   (first (filter #(and (= "assistant" (:type %))
                                                (not (:isSidechain %))
                                                (pos? (or (usage-tokens %) 0)))
                                          mains))
                    user   (some->> (rseq (:users acc)) (keep parse-line)
                                    (filter #(= "user" (:type %))) first)
                    ai     (some-> (:ai-title acc) parse-line :aiTitle)
                    custom (some-> (:custom-title acc) parse-line
                                   ((fn [m] (or (:customTitle m) (:title m)))))
                    ts     (->> [(:timestamp main) (:timestamp user)] (keep ->date) sort last)]
                {:read-to      (+ from used)
                 :recaps       (:recaps acc)
                 :compactions  (:compactions acc)
                 :ai-title     ai
                 :custom-title custom
                 :last-prompt  (some-> (:last-prompt acc) parse-line :lastPrompt)
                 :context      (some-> main usage-tokens)
                 :model        (get-in main [:message :model])
                 :last-activity ts
                 :cwd          (or (:cwd main) (:cwd user))})
              (recur
               (+ used n)
               (cond
                 (str/includes? line "\"subtype\":\"away_summary\"")
                 (let [m (parse-line line)]
                   (if (:content m)
                     (update acc :recaps conj {:at (->date (:timestamp m)) :text (str (:content m))})
                     acc))

                 (str/includes? line "\"subtype\":\"compact_boundary\"")
                 (let [m (parse-line line)]
                   (update acc :compactions conj {:at         (->date (:timestamp m))
                                                  :trigger    (get-in m [:compactMetadata :trigger])
                                                  :pre-tokens (get-in m [:compactMetadata :preTokens])}))

                 (str/includes? line "\"type\":\"ai-title\"")     (assoc acc :ai-title line)
                 (str/includes? line "\"type\":\"custom-title\"") (assoc acc :custom-title line)
                 (str/includes? line "\"type\":\"last-prompt\"")  (assoc acc :last-prompt line)
                 (str/includes? line "\"type\":\"assistant\"")    (update acc :assistants hold line)
                 (str/includes? line "\"type\":\"user\"")         (update acc :users hold line)
                 :else acc)))))))))

(defn- add-new
  "old with the items of new whose :at it lacks. A restart rescans from the
   last :read-to written, so the same recap can be read twice."
  [old new]
  (let [seen (set (map :at old))]
    (into (vec old) (remove #(seen (:at %)) new))))

(defn- merge-scan
  "A history entry with one scan-transcript result folded in."
  [entry {:keys [read-to recaps compactions ai-title custom-title last-prompt context last-activity cwd]}]
  (cond-> (assoc entry
                 :read-to read-to
                 :trail (add-new (:trail entry) recaps)
                 :compactions (add-new (:compactions entry) compactions))
    ai-title      (assoc :ai-title ai-title)
    custom-title  (assoc :custom-title custom-title)
    last-prompt   (assoc :last-prompt last-prompt)
    context       (assoc :context-last context :context-peak (max context (or (:context-peak entry) 0)))
    last-activity (assoc :last-activity last-activity)
    (and cwd (not (:cwd entry))) (assoc :cwd cwd)))

(defn- load-history
  "[history backfill? error]. No file yet means a first run, so backfill. A
   file that will not read is an error, and nothing is written over it."
  [file]
  (let [f (io/file file)]
    (if-not (.isFile f)
      [{} true nil]
      (try (let [h (edn/read-string (slurp f))]
             (if (map? h) [h false nil] [{} false (str file ": not a map")]))
           (catch Exception e [{} false (str file ": " (.getMessage e))])))))

(defn- history-sig
  "What a write is for: everything but the fields that move on every refresh.
   The context counts only when it crosses a whole percent of the window."
  [window entry]
  (-> entry
      (dissoc :read-to :last-seen :context-last :last-activity)
      (assoc :context-pct (quot (* 100 (or (:context-last entry) 0)) window))))

(defn- write-history!
  "One entry per line, oldest session first. Written to a .new file and moved
   over the old one, so a crash never leaves half a file."
  [file h]
  (let [f   (io/file file)
        tmp (io/file (str file ".new"))]
    (io/make-parents f)
    (with-open [w (io/writer tmp)]
      (.write w (str ";; mdserve session history: one entry per Claude Code session, keyed by\n"
                     ";; session id. Written by mdserve (see ~/programs/MDSERVE_SESSIONS.md).\n"
                     ";; Entries are never deleted.\n{"))
      (doseq [[sid e] (sort-by (fn [[_ e]] (or (:started-at e) (:first-seen e))) h)]
        (.write w (str "\n" (pr-str sid) "\n " (pr-str e))))
      (.write w "}\n"))
    (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                           java.nio.file.StandardCopyOption/ATOMIC_MOVE]))))

(defn- live-entry
  "A history entry updated from a running session's file."
  [old s c now]
  (assoc old
         :name       (:name s)
         :cwd        (:cwd s)
         :workspace  (session-workspace c (:cwd s))
         :identity   (:claude-dir s)
         :pid        (:pid s)
         :version    (:version s)
         :started-at (->date (:startedAt s))
         :first-seen (or (:first-seen old) now)
         :last-seen  now
         :ended-at   nil))

(defn- scan-into
  "entry with its transcript's new lines folded in; unchanged when the
   transcript cannot be found."
  [entry claude-dir sid]
  (if-let [f (or (some-> (:transcript entry) io/file (#(when (.isFile ^java.io.File %) %)))
                 (transcript-file claude-dir (:cwd entry) sid))]
    (-> entry
        (assoc :transcript (.getPath ^java.io.File f))
        (merge-scan (scan-transcript f (or (:read-to entry) 0))))
    entry))

(defn- backfill
  "Entries for every transcript on disk that the history lacks, ended sessions
   included. Run on every refresh, so a session that started and ended between
   two refreshes is still recorded; the first run picks up all Claude Code keeps."
  [h c now]
  (reduce (fn [h [d ^java.io.File f]]
            (let [sid (str/replace (.getName f) #"\.jsonl$" "")]
              (if (contains? h sid)
                h
                (let [e (merge-scan {:identity d :transcript (.getPath f) :first-seen now}
                                    (scan-transcript f 0))]
                  (assoc h sid (assoc e
                                      :workspace (session-workspace c (:cwd e))
                                      :ended-at  (or (:last-activity e) now)))))))
          h
          (for [d     (:claude-dirs c)
                p     (.listFiles (io/file d "projects"))
                :when (.isDirectory ^java.io.File p)
                f     (.listFiles ^java.io.File p)
                :when (str/ends-with? (.getName ^java.io.File f) ".jsonl")]
            [d f])))

(def sessions-state
  "The latest refresh: the history map, the running sessions (their history
   entries plus the live :status fields), and when it was taken."
  (atom {:loaded? false :history {} :live [] :refreshed-at 0}))

(def sessions-running (atom false))

(defn- sessions-refresh!
  "Read every running session, fold each transcript's new lines into the
   history, mark sessions that have ended, add any transcript not yet seen, and write
   the history when something worth keeping changed."
  []
  (let [c      (sessions-config)
        now    (java.util.Date.)
        st     @sessions-state
        [hist backfill? herr] (if (:loaded? st)
                                [(:history st) false (:history-error st)]
                                (load-history (:history-file c)))
        live   (vec (live-session-files (:claude-dirs c)))
        ids    (set (map :sessionId live))
        h1     (reduce (fn [h s]
                         (let [sid (:sessionId s)]
                           (assoc h sid (-> (get h sid {})
                                            (live-entry s c now)
                                            (scan-into (:claude-dir s) sid)))))
                       hist live)
        h2     (reduce-kv (fn [h sid e]
                            (if (and (:pid e) (nil? (:ended-at e)) (not (ids sid)))
                              (assoc h sid (assoc e :ended-at now))
                              h))
                          h1 h1)
        h3     (backfill h2 c now)
        sig    (fn [h] (update-vals h #(history-sig (:context-window c) %)))]
    (when (and (nil? herr) (or backfill? (not= (sig hist) (sig h3))))
      (write-history! (:history-file c) h3))
    (swap! sessions-state assoc
           :loaded? true
           :history h3
           :history-error herr
           :error nil
           :live (mapv #(merge (get h3 (:sessionId %)) (select-keys % [:status :statusUpdatedAt :sessionId]))
                       live)
           :refreshed-at (System/currentTimeMillis))))

(defn- sessions-kick!
  "Start a refresh in the background unless one is running or the last is
   under 4s old. Requests never wait for it: the page shows the last refresh,
   and its 30-second poll picks up the next."
  []
  (when (and (> (- (System/currentTimeMillis) (:refreshed-at @sessions-state)) 4000)
             (compare-and-set! sessions-running false true))
    (future
      (try (sessions-refresh!)
           (catch Exception e
             (println "  sessions refresh failed:" (.getMessage e))
             (swap! sessions-state assoc :error (.getMessage e)))
           (finally (reset! sessions-running false))))))

(def sessions-timer
  "Refreshes every 3 minutes whether or not a Sessions tab is looking, so the
   history keeps up on its own. The first runs at startup."
  (future
    (loop []
      (sessions-kick!)
      (Thread/sleep (* 3 60 1000))
      (recur))))

(defn- ss-attr [s]
  (str/replace (escape-html s) "\"" "&quot;"))

(defn- ss-ago
  "A duration in ms as '12 min', '3 h' or '5 days'."
  [ms]
  (let [m (quot (max 0 ms) 60000)]
    (cond
      (< m 1)    "under a min"
      (< m 60)   (str m " min")
      (< m 1440) (str (quot m 60) " h")
      :else      (let [d (quot m 1440)] (str d (if (= d 1) " day" " days"))))))

(defn- ss-when [^java.util.Date d]
  (when d
    (.format (.atZone (.toInstant d) (java.time.ZoneId/systemDefault))
             (DateTimeFormatter/ofPattern "d MMM HH:mm"))))

(defn- recap-text [s]
  (str/replace (str s) #"\s*\(disable recaps in /config\)\s*$" ""))

(defn- first-sentence [s]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if-let [i (str/index-of s ". ")] (subs s 0 (inc i)) s)))

(defn- clip [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(def ^:private status-rank {"waiting" 0 "busy" 1 "idle" 2})

(defn- session-row-html [e c now-ms]
  (let [status (or (:status e) "unknown")
        ctx    (:context-last e)
        pct    (when ctx (Math/round (/ (* 100.0 ctx) (:context-window c))))
        level  (cond (nil? pct) ""
                     (>= pct (:context-alarm-pct c)) " alarm"
                     (>= pct (:context-warn-pct c)) " warn"
                     :else "")
        recap  (some-> (:trail e) peek :text recap-text)
        auto   (last (filter #(= "auto" (:trigger %)) (:compactions e)))
        proj   (let [cwd (str (:cwd e))]
                 (if (= cwd (System/getProperty "user.home")) "~" (.getName (io/file cwd))))
        ident  (let [d (some-> (:identity e) io/file .getName)]
                 (when (and d (not= d ".claude")) (str/replace d #"^\.claude-?" "")))]
    (str "<details class=\"ss\" data-sid=\"" (ss-attr (:sessionId e)) "\"><summary>"
         "<span class=\"ss-dot " (ss-attr status) "\" title=\"" (ss-attr status) "\"></span>"
         "<span class=\"ss-proj\">" (escape-html proj)
         (when ident (str "<span class=\"ss-tag\">" (escape-html ident) "</span>")) "</span>"
         "<span class=\"ss-main\"><span class=\"ss-title\">"
         (escape-html (or (:custom-title e) (:ai-title e) (:name e))) "</span>"
         (when recap
           (str "<span class=\"ss-now\" title=\"" (ss-attr recap) "\">" (escape-html (first-sentence recap)) "</span>"))
         (when auto
           (str "<span class=\"ss-compact\">auto-compacted " (escape-html (ss-when (:at auto)))
                (when (:pre-tokens auto) (str " at " (quot (:pre-tokens auto) 1000) "k")) "</span>"))
         "</span>"
         "<span class=\"ss-ctx" level "\" title=\"" (if ctx (str ctx " tokens") "no reading yet") "\">"
         (if pct (str "<span class=\"ss-bar\"><i style=\"width:" (min pct 100) "%\"></i></span>" pct "%") "–")
         "</span>"
         "<span class=\"ss-state\">" (escape-html status)
         (when (:statusUpdatedAt e) (str " " (ss-ago (- now-ms (:statusUpdatedAt e))))) "</span>"
         "<span class=\"ss-age\">" (if-let [d (:started-at e)] (str (ss-ago (- now-ms (.getTime ^java.util.Date d))) " old") "") "</span>"
         "</summary><div class=\"ss-more\">"
         (when (:last-prompt e)
           (str "<p class=\"ss-last\"><b>Last request:</b> " (escape-html (clip (:last-prompt e) 400)) "</p>"))
         (if (seq (:trail e))
           ;; Newest first, numbered down from the highest: read back in time.
           (str "<ol class=\"ss-trail\" reversed>"
                (apply str (for [{:keys [at text]} (rseq (vec (:trail e)))]
                             (str "<li><time>" (escape-html (ss-when at)) "</time> " (escape-html (recap-text text)) "</li>")))
                "</ol>")
           "<p class=\"ss-none\">No trail yet.</p>")
         "<p class=\"ss-id\">" (escape-html (:name e)) " · " (escape-html (:sessionId e)) " · " (escape-html (:version e)) "</p>"
         "</div></details>")))

(defn sessions-rows-html
  "The live part of a workspace's Sessions tab, re-fetched every 30s."
  [n]
  (sessions-kick!)
  (let [c    (sessions-config)
        st   @sessions-state
        now  (System/currentTimeMillis)
        rows (->> (:live st)
                  (filter #(= n (:workspace %)))
                  (sort-by (juxt #(status-rank (:status %) 3) #(- (or (:statusUpdatedAt %) 0)))))
        cnt  (frequencies (map :status rows))]
    (str "<div class=\"ss-head\" data-waiting=\"" (get cnt "waiting" 0) "\">"
         (if (:loaded? st)
           (str (get cnt "waiting" 0) " waiting · " (get cnt "busy" 0) " busy · " (get cnt "idle" 0) " idle")
           "Reading the session files…")
         (when-let [e (:history-error st)]
           (str "<div class=\"ss-err\">History not written: " (escape-html e) "</div>"))
         (when-let [e (:error st)]
           (str "<div class=\"ss-err\">Refresh failed: " (escape-html e) "</div>"))
         "</div>"
         (when (and (:loaded? st) (empty? rows))
           "<p class=\"ss-none\">No sessions running in this workspace.</p>")
         (apply str (map #(session-row-html % c now) rows)))))

(def sessions-style
  (str/join "\n"
            ["main.md:has(#ss){max-width:84rem}"
             "#ss{max-width:none;font-size:.9em;line-height:1.45}"
             ".ss-head{color:var(--fg-soft);margin:.2em 0 1em}"
             ".ss-err{color:#d1242f}"
             ".ss summary{display:grid;grid-template-columns:12px 8rem minmax(0,1fr) 8.5rem 9rem 6rem;gap:.9em;align-items:baseline;cursor:pointer;list-style:none;padding:.55em .2em;border-bottom:1px solid var(--line)}"
             ".ss summary::-webkit-details-marker{display:none}"
             ".ss-dot{width:10px;height:10px;border-radius:50%;background:#8b949e;align-self:start;margin-top:.45em}"
             ".ss-dot.waiting{background:#e5534b}.ss-dot.busy{background:#d29922}"
             ".ss-tag{font-size:.75em;margin-left:.5em;padding:0 .4em;border:1px solid var(--line);border-radius:4px;color:var(--fg-soft)}"
             ".ss-main{display:flex;flex-direction:column;min-width:0}"
             ".ss-title{font-weight:600}"
             ".ss-now{color:var(--fg-soft);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
             ".ss-compact{color:#d1242f;font-size:.9em}"
             ".ss-ctx{white-space:nowrap;font-variant-numeric:tabular-nums}"
             ".ss-bar{display:inline-block;width:4.5rem;height:.55em;margin-right:.5em;border-radius:3px;background:var(--line);overflow:hidden;vertical-align:middle}"
             ".ss-bar i{display:block;height:100%;background:#8b949e}"
             ".ss-ctx.warn .ss-bar i{background:#d29922}.ss-ctx.warn{color:#b07d10}"
             ".ss-ctx.alarm .ss-bar i{background:#e5534b}.ss-ctx.alarm{color:#d1242f;font-weight:600}"
             ".ss-state,.ss-age{color:var(--fg-soft);white-space:nowrap}"
             ".ss-more{padding:.4em 1em 1em calc(12px + 8rem + 1.8em)}"
             ".ss-trail{margin:.4em 0;padding-left:1.2em}"
             ".ss-trail li{margin:.25em 0}"
             ".ss-trail time{color:var(--fg-soft);font-variant-numeric:tabular-nums;margin-right:.5em}"
             ".ss-none,.ss-id{color:var(--fg-soft)}"
             ".ss-id{font-size:.85em}"]))

(defn- sessions-js [n]
  (str "<script>(function(){"
       "var box=document.getElementById('ss');"
       "function title(){var h=box.querySelector('[data-waiting]');var w=h?+h.dataset.waiting:0;"
       "document.title=(w?'('+w+' waiting) ':'')+'Sessions · ws " n "';}"
       "function load(){if(document.visibilityState!=='visible')return;"
       "fetch('/sessions/rows?ws=" n "').then(function(r){return r.text();}).then(function(h){"
       "var open=[].map.call(box.querySelectorAll('details[open]'),function(d){return d.dataset.sid;});"
       "box.innerHTML=h;"
       "open.forEach(function(s){var d=box.querySelector('details[data-sid=\"'+s+'\"]');if(d)d.open=true;});"
       "title();}).catch(function(){});}"
       "title();setTimeout(load,1000);setInterval(load,30000);"
       "document.addEventListener('visibilitychange',load);"
       "})();</script>"))

(defn sessions-page [n]
  (sessions-kick!)
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout (str "Sessions · ws " n)
                 (str "<h1>Sessions · workspace " n "</h1>"
                      "<div id=\"ss\">" (sessions-rows-html n) "</div>"
                      (sessions-js n))
                 (str "<style>" sessions-style "\n</style>")
                 {:no-toolbar true})})

(defn- entry-text
  "Everything a search looks at in one history entry, lower-cased."
  [sid e]
  (str/lower-case
   (str/join "\n" (concat [sid (:name e) (:ai-title e) (:custom-title e) (:last-prompt e) (:cwd e)]
                          (map :text (:trail e))))))

(defn- entry-time ^long [e]
  (or (some-> (or (:last-activity e) (:last-seen e) (:started-at e) (:first-seen e))
              (#(.getTime ^java.util.Date %)))
      0))

(defn sessions-find
  "The history entries holding every word of q, case-insensitive, most recently
   active first, at most 50. Each gets :sessionId. Reads the history as the last
   refresh left it, or the file when no refresh has run yet."
  [q]
  (let [words (remove str/blank? (str/split (str/lower-case (str q)) #"\s+"))
        st    @sessions-state
        h     (if (:loaded? st) (:history st) (first (load-history (:history-file (sessions-config)))))]
    (when (seq words)
      (->> h
           (keep (fn [[sid e]]
                   (let [t (entry-text sid e)]
                     (when (every? #(str/includes? t %) words) (assoc e :sessionId sid)))))
           (sort-by #(- (entry-time %)))
           (take 50)))))

(defn- resume-command
  "The shell line that reopens a session: claude --resume from its folder, with
   CLAUDE_CONFIG_DIR when it belongs to an identity other than ~/.claude."
  [{:keys [cwd identity sessionId]}]
  (str "cd " cwd " && "
       (when (and identity (not= ".claude" (.getName (io/file identity))))
         (str "CLAUDE_CONFIG_DIR=" identity " "))
       "claude --resume " sessionId))

(defn- find-snippet
  "Where the first word of q appears: the newest recap holding it, else the
   last request, else nil."
  [e q]
  (let [w (first (remove str/blank? (str/split (str/lower-case (str q)) #"\s+")))]
    (when w
      (some #(when (str/includes? (str/lower-case (str %)) w) (recap-text %))
            (concat (rseq (vec (map :text (:trail e)))) [(:last-prompt e)])))))

(defn- find-dates [e]
  (let [from (or (:started-at e) (:first-seen e))
        to   (or (:last-activity e) (:last-seen e))]
    (str (or (ss-when from) "?") " – " (or (ss-when to) "?")
         (if (and (:pid e) (nil? (:ended-at e))) " · running" " · ended"))))

(defn sessions-find-text
  "sessions-find as plain text, for session-find in a shell."
  [q]
  (let [es (sessions-find q)]
    (if (empty? es)
      (str "No session in the history matches \"" q "\".\n")
      (apply str
             (for [e es]
               (str (:name e) " · " (.getName (io/file (str (:cwd e)))) " · ws " (:workspace e) " · " (find-dates e) "\n"
                    "  " (or (:custom-title e) (:ai-title e) "(no title)") "\n"
                    (when-let [r (some-> (:trail e) peek :text recap-text first-sentence)] (str "  now on: " r "\n"))
                    (when-let [s (find-snippet e q)] (str "  match:  " (clip s 240) "\n"))
                    "  resume: " (resume-command e) "\n\n"))))))

(defn sessions-find-html
  "sessions-find as rows for the panel's search box: open one for its last
   request, trail and resume command."
  [q]
  (let [es (sessions-find q)]
    (if (empty? es)
      (str "<p class=\"ss-none\">No session in the history matches \"" (escape-html q) "\".</p>")
      (str "<p class=\"ss-head\">" (count es) (if (= 1 (count es)) " session" " sessions") ", most recent first.</p>"
           (apply str
                  (for [e es]
                    (str "<details class=\"ss sf\"><summary>"
                         "<span class=\"ss-proj\">" (escape-html (:name e)) "</span>"
                         "<span class=\"ss-main\"><span class=\"ss-title\">"
                         (escape-html (or (:custom-title e) (:ai-title e) "(no title)")) "</span>"
                         (when-let [s (find-snippet e q)]
                           (str "<span class=\"ss-now\" title=\"" (ss-attr s) "\">" (escape-html (clip s 160)) "</span>"))
                         "</span>"
                         "<span class=\"ss-state\">" (escape-html (find-dates e)) "</span>"
                         "</summary><div class=\"ss-more\">"
                         "<p class=\"ss-id\">" (escape-html (:cwd e)) " · workspace " (:workspace e) "</p>"
                         (when (:last-prompt e)
                           (str "<p class=\"ss-last\"><b>Last request:</b> " (escape-html (clip (:last-prompt e) 400)) "</p>"))
                         (when (seq (:trail e))
                           (str "<ol class=\"ss-trail\" reversed>"
                                (apply str (for [{:keys [at text]} (rseq (vec (:trail e)))]
                                             (str "<li><time>" (escape-html (ss-when at)) "</time> " (escape-html (recap-text text)) "</li>")))
                                "</ol>"))
                         "<p><b>Resume:</b> <code>" (escape-html (resume-command e)) "</code></p>"
                         "</div></details>")))))))

(def sessions-panel-section
  "The panel's Sessions section: what the Sessions tabs are, and a search box
   over the history (sessions-find-html, through /sessions/find)."
  (str "<h2>Sessions</h2>"
       "<p>Each window has a <b>Sessions</b> tab, second from the left: its Claude Code sessions, "
       "which ones are waiting for you, what each is on now, and how full its context is. "
       "The address is the window's workspace number: <code>/sessions/1</code>, <code>/sessions/2</code> and so on.</p>"
       "<p>Every session is also kept in a history, ended ones too, with its title, last request and trail "
       "of recaps. Search it here: every word must match, in any case.</p>"
       "<form id=\"sf-form\" class=\"sf-form\"><input id=\"sf-q\" type=\"search\" placeholder=\"words to find\" autocomplete=\"off\"> "
       "<button class=\"sf-btn\" type=\"submit\">Find</button></form>"
       "<div id=\"sf-out\"></div>"
       "<style>" sessions-style "\n"
       ".sf-form{display:flex;gap:.5em;margin:.6em 0 1em}"
       ".sf-form input{flex:1;max-width:28rem;font:inherit;font-size:15px;padding:5px 10px;border:1px solid var(--line);border-radius:8px;background:var(--pre-bg);color:var(--fg)}"
       ".sf-btn{font:inherit;font-size:13px;cursor:pointer;border:1px solid var(--line);background:var(--pre-bg);color:var(--fg);border-radius:8px;padding:6px 12px}"
       "#sf-out{max-width:none;font-size:.9em;line-height:1.45}"
       ".ss.sf summary{grid-template-columns:8rem minmax(0,1fr) 16rem}"
       ".ss.sf .ss-more{padding-left:calc(8rem + .9em)}"
       "</style>"
       "<script>(function(){var f=document.getElementById('sf-form'),q=document.getElementById('sf-q'),"
       "out=document.getElementById('sf-out');"
       "f.addEventListener('submit',function(e){e.preventDefault();if(!q.value.trim())return;"
       "out.innerHTML='<p class=\"ss-head\">… Searching</p>';"
       "fetch('/sessions/find?q='+encodeURIComponent(q.value)).then(function(r){return r.text();})"
       ".then(function(h){out.innerHTML=h;}).catch(function(){out.innerHTML='<p class=\"ss-err\">⚠ Search failed</p>';});});"
       "})();</script>"))
(defn sessions-route
  "The Sessions routes, or nil when uri is not one: /sessions/<n>, workspace n's
   tab, /sessions/rows?ws=<n>, its 30-second poll, and /sessions/find?q=<words>
   (&format=text for a shell), the history search."
  [uri query-string]
  (cond
    (= uri "/sessions/rows")
    (if-let [n (some-> (query-param query-string "ws") parse-long)]
      {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (sessions-rows-html n)}
      (text 400 "ws=<n> needed\n"))
    (= uri "/sessions/find")
    (if (= "text" (query-param query-string "format"))
      (text 200 (sessions-find-text (query-param query-string "q")))
      {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (sessions-find-html (query-param query-string "q"))})
    (re-matches #"/sessions/\d+" uri) (sessions-page (parse-long (subs uri (count "/sessions/"))))))
;; --- end /sessions section ---

(def landing
  (layout "mdserve"
          (str
           (md/md-to-html-string
            (str "# mdserve\n\nLocal markdown viewer on `" base "`. "
                 "This is the panel window: docs that belong to no project open here.\n"))
           mem-section
           version-section
           (md/md-to-html-string
            (str "\n## Projects\n\n" (project-lines) "\n\n"
                 "Each project has its own window: its command dashboard (blue dot), "
                 "its Explorer (green dot), and the docs shown from inside it. "
                 "[Commands for this window](" base "/commands/home) run from your home directory.\n"))
           (md/md-to-html-string
            (str "\n## Remember Tabs\n\n"
                 "Save every open tab to `" tabs-file "`. The next start reopens them, "
                 "one window per project, each in dot order. A project with no window "
                 "open keeps what was saved for it last time.\n"))
           (action-button "remember-tabs" "💾 Remember Tabs" "… Saving"
                          (str "Write the currently open tabs to " tabs-file)
                          (str base "/remember-tabs") "✓ Saved")
           (when (pos? tablet-port)
             (str (md/md-to-html-string
                   (str "\n## Tablet\n\n"
                        "The tablet reading list, on " tablet-base "/. **Off** stops that "
                        "server at once, so the tablet cannot connect, and it stays off across "
                        "restarts until turned **on**. The list is kept. From a shell: "
                        "`./tablet.sh off`, `./tablet.sh on`, `./tablet.sh` (the state).\n"))
                  "<p>Now: <b id=\"tablet-state\">…</b></p>"
                  (action-button "tablet-btn" "⏻ Tablet off" "… Stopping" "Stop the tablet server now"
                                 (str base "/tablet/off") "✓ Off")
                  " "
                  (action-button "tablet-btn" "Tablet on" "… Starting" "Start the tablet server again"
                                 (str base "/tablet/on") "✓ On")
                  "<script>function tabletState(){fetch('/tablet/status').then(function(r){return r.text();})"
                  ".then(function(t){document.getElementById('tablet-state').textContent=t.trim();});}"
                  "tabletState();document.addEventListener('click',function(e){"
                  "if(e.target.classList.contains('tablet-btn'))setTimeout(tabletState,800);});</script>"))
           sessions-panel-section
           (md/md-to-html-string
            (str "\n## Show a doc\n\n```\ncurl \"" base "/show/ABSOLUTE/PATH/file.md\"\n```\n\n"
                 "It opens in the window of the project the file lives in, or here. "
                 "`?ws=<n>` sends it to the window of the project on workspace n instead; "
                 "`?at=<heading-id>` scrolls to a heading. An ordinary web page goes up the same way:\n\n"
                 "```\ncurl -sG --data-urlencode 'url=https://example.com' \"" base "/show-link\"\n```\n\n"
                 (when (pos? tablet-port)
                   (str "## Tablet reading list\n\nBookmark `" tablet-base "/` on the tablet, "
                        "then put a doc on the list with\n\n```\ncurl \"" base
                        "/tab-show/ABSOLUTE/PATH/file.md\"\n```\n\n"
                        "Only docs on the list can be read from the LAN.\n\n"))
                 "Use **↻ Refresh** (top-right on a doc) to re-render after editing a file.\n")))
          nil
          {:no-toolbar true}))

(defn app [{:keys [uri request-method query-string]}]
  ;; The Sessions tabs poll every 30s; those requests are left out of the log.
  (when-not (= uri "/sessions/rows")
    (println (str (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss"))
                  "  " (name request-method) " " uri)))
  (cond
    (= uri "/") {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body landing}
    (= uri "/health") (text 200 "ok\n")
    (= uri "/version") (version-route)
    (str/starts-with? uri "/sessions/") (or (sessions-route uri query-string) (text 404 "not found\n"))
    (= uri "/tablet/status") (tablet-status)
    (= uri "/tablet/off") (if (= request-method :post) (tablet-off!) (text 405 "POST only\n"))
    (= uri "/tablet/on") (if (= request-method :post) (tablet-on!) (text 405 "POST only\n"))
    (= uri "/mem") (mem-route)
    (= uri "/favicon.ico") {:status 204}
    (= uri "/remember-tabs") (remember-tabs nil)
    (str/starts-with? uri "/commands/") (commands-route uri request-method query-string)
    (str/starts-with? uri "/view/") (view (suffix uri "/view")
                                          (boolean (and query-string (str/includes? query-string "show=1"))))
    (str/starts-with? uri "/show/") (show uri query-string)
    (= uri "/show-link") (show-link query-string)
    (str/starts-with? uri "/tab-show/") (tab-show uri)
    (= uri "/tab-link") (tab-link query-string)
    (str/starts-with? uri "/terminal/") (terminal (suffix uri "/terminal"))
    (str/starts-with? uri "/vi/") (vi (suffix uri "/vi"))
    :else (text 404 "not found\n")))

(srv/run-server app {:ip "127.0.0.1" :port port})

;; The tablet listener: a separate server with its own three routes, so nothing
;; added to `app` above can ever become reachable from the LAN by accident.
(when (pos? tablet-port)
  (if (tablet-start!)
    (println (str "tablet reading list on " tablet-base "  (bind " tablet-bind ", list " tablet-file ")"))
    (println (str "tablet reading list OFF (" tablet-off-file " exists); turn it on with the panel's Tablet on button"))))

(println (str "mdserve listening on " base))
(println (str "  config:  " (if (.isFile (io/file config-file)) config-file (str config-file " (none — run bb install.bb)"))))
(doseq [{:keys [name dir workspace]} projects]
  (println (str "  project: " name (when workspace (str " (workspace " workspace ")")) "  " dir)))
(if opener-str
  (println (str "  opener:  " opener-str))
  (println (str "  browser: " browser "   (profile: " profile ")")))
(println (str "  show a doc:  curl \"" base "/show/<absolute-path>.md\""))

;; Open the windows: the remembered set when there is one, else the panel plus a
;; window per project. Either way the FIRST open starts the browser, and it is
;; the only launch that can set the DevTools port.
(when (and (not opener-str) (not no-open?))
  (let [groups (window-groups (or (some-> (read-tabs) merge-tabs sort-tabs seq)
                                  (merge-tabs [])))]
    (println (str "  opening " (count groups) " window(s): "
                  (str/join ", " (map (fn [[cls ts]] (str cls " (" (count ts) " tabs)")) groups))))
    (replay-tabs! groups)))

@(promise)
