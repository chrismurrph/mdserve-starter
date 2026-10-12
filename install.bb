#!/usr/bin/env bb
;; install.bb — ask a few questions and write ~/.config/mdserve-starter/config.edn.
;;
;;   bb install.bb
;;
;; Safe to run again: an existing config is backed up to config.edn.bak<n>
;; first. Everything it writes can be changed later by editing the file.
(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(def home (System/getProperty "user.home"))
(def repo (.getParent (.getAbsoluteFile (io/file (System/getProperty "babashka.file")))))
(def config-file (or (System/getenv "MDSERVE_CONFIG")
                     (str home "/.config/mdserve-starter/config.edn")))

(defn on-path? [exe]
  (some #(.canExecute (io/file % exe))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn port-free?
  "True when nothing is listening on localhost:port."
  [port]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300)
         false)
       (catch Exception _ true)))

(defn free-port
  "The first port from start up that nothing is listening on. Another mdserve
   may already be running on this machine, and two can't share a port."
  [start]
  (first (filter port-free? (iterate inc start))))

(defn ask
  "Prompt, read a line, and return it trimmed — or default when it is blank."
  [question default]
  (print (str question (when (some? default) (str " [" default "]")) ": "))
  (flush)
  (let [a (some-> (read-line) str/trim)]
    (if (str/blank? a) default a)))

(defn yes? [question default-yes?]
  (let [a (ask (str question (if default-yes? " (Y/n)" " (y/N)")) nil)]
    (if (nil? a) default-yes? (str/starts-with? (str/lower-case a) "y"))))

(defn ask-int [question default]
  (loop []
    (let [a (ask question default)]
      (cond
        (or (nil? a) (= "" a))       nil
        (re-matches #"\d+" (str a))  (parse-long (str a))
        :else (do (println "  a number, please (or leave it blank)") (recur))))))

(defn tilde [path]
  (if (str/starts-with? path (str home "/")) (str "~" (subs path (count home))) path))

(defn edn-str [s] (pr-str (str s)))

(defn command-edn [{:keys [id label run ports once]}]
  (str "{:id " (edn-str id) " :label " (edn-str label) " :run " (edn-str run)
       (when (seq ports) (str " :ports " (pr-str ports)))
       (when once " :once true")
       "}"))

(defn project-edn [{:keys [name dir workspace commands]}]
  (str "  {:name " (edn-str name) "\n"
       "   :dir " (edn-str (tilde dir)) "\n"
       (when workspace (str "   :workspace " workspace "\n"))
       "   :commands ["
       (str/join "\n              " (map command-edn commands))
       "]}"))

(def example-projects
  [{:name "alpha" :dir (str repo "/examples/alpha") :workspace 1
    :commands [{:id "web" :label "Example web server" :run "example-web 8701" :ports [8701]}
               {:id "check" :label "Example check" :run "example-check" :once true}]}
   {:name "beta" :dir (str repo "/examples/beta") :workspace 3
    :commands [{:id "web" :label "Example web server" :run "example-web 8703" :ports [8703]}
               {:id "check" :label "Example check, set up to fail" :run "example-check fail" :once true}]}])

(defn ask-project []
  (let [nm (loop []
             (let [n (ask "  Project name (letters, digits, . _ -)" nil)]
               (if (and n (re-matches #"[A-Za-z0-9._-]+" n) (not= n "home"))
                 n
                 (do (println "  letters, digits, . _ - only (and not \"home\")") (recur)))))
        dir (loop []
              (let [d (some-> (ask "  Its folder" nil) (str/replace #"^~" home))]
                (if (and d (.isDirectory (io/file d)))
                  (.getCanonicalPath (io/file d))
                  (do (println "  no such folder") (recur)))))
        ws  (ask-int "  Workspace for its window (blank for none)" nil)]
    {:name nm :dir dir :workspace ws :commands []}))

(defn config-text [{:keys [port browser profile devtools-port projects panel-ws tablet-port path]}]
  (str ";; mdserve config — written by install.bb, meant to be edited by hand.\n"
       ";; Port, browser and projects are read at startup: restart mdserve after\n"
       ";; changing them. :commands are re-read on every dashboard load.\n"
       "{:port " port "\n"
       " :browser " (edn-str browser) "\n"
       "\n"
       " ;; The browser's own profile, and the DevTools port mdserve drives it\n"
       " ;; through. Both must differ from any other mdserve on this machine.\n"
       " :profile " (edn-str (tilde profile)) "\n"
       " :devtools-port " devtools-port "\n"
       "\n"
       " ;; Put at the front of PATH for every command. Your scripts go in ~/bin.\n"
       " :path " (pr-str (mapv tilde path)) "\n"
       "\n"
       " ;; The panel's window: docs outside every project open there.\n"
       (if panel-ws (str " :panel-workspace " panel-ws "\n") " ;; :panel-workspace 2\n")
       "\n"
       " ;; Each project gets its own window. A command is a service by default\n"
       " ;; (Launch/Stop, dot from its :ports); :once true makes it a one-shot\n"
       " ;; (Run, dot from its exit code). :run is a shell command, run in :dir.\n"
       " :projects\n [" (str/trim (str/join "\n\n" (map project-edn projects))) "]\n"
       "\n"
       " ;; Commands for the panel's window, run from your home directory.\n"
       " :commands []\n"
       "\n"
       " ;; The tablet reading list, served to your LAN. 0 = off.\n"
       " :tablet-port " tablet-port "\n"
       "\n"
       " ;; Uncomment to choose a terminal for the ⌨ Terminal and 📝 vi buttons;\n"
       " ;; otherwise the first known one on PATH is used. {dir} is the folder,\n"
       " ;; {cmd} the command to run.\n"
       " ;; :terminal \"gnome-terminal --working-directory={dir}\"\n"
       " ;; :terminal-run \"gnome-terminal --working-directory={dir} -- {cmd}\"\n"
       "}\n"))

(println "mdserve setup — writes" config-file)
(println)

(when-not (on-path? "bb")
  (println "Note: babashka (bb) is not on PATH, and mdserve.sh needs it there."))

(def browser
  (let [found (first (filter on-path? ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser"]))]
    (when-not found
      (println "No Chrome or Chromium found on PATH. mdserve needs one of them."))
    (ask "Browser command" (or found "google-chrome"))))

(def port (or (ask-int "Port for mdserve" (free-port 7000)) 7000))

(def devtools-port
  (let [p (free-port 9222)]
    (when-not (= p 9222)
      (println (str "  (DevTools port 9222 is in use, so this mdserve's browser will use " p ")")))
    p))

(println)
(println "Projects each get their own window, on a workspace you choose.")
(def use-examples?
  (yes? "Add the two example projects (alpha on workspace 1, beta on workspace 3)?" true))

(def own-projects
  (loop [acc []]
    (if (yes? (if (seq acc) "Add another project of your own?" "Add a project of your own?") false)
      (recur (conj acc (ask-project)))
      acc)))

(def panel-ws
  (ask-int "Workspace for the panel window (blank for none)"
           (when (or use-examples? (seq own-projects)) 2)))

(println)
(def tablet-port
  (if (yes? "Serve a reading list to a tablet on your LAN?" false)
    (or (ask-int "  Port for it" (free-port 7001)) 7001)
    0))

(def cfg
  {:port port
   :browser browser
   :profile (str home "/.local/share/mdserve-starter/browser")
   :devtools-port devtools-port
   :projects (into (if use-examples? example-projects []) own-projects)
   :panel-ws panel-ws
   :tablet-port tablet-port
   :path (cond-> [(str home "/bin")]
           use-examples? (conj (str repo "/examples/bin")))})

(let [f (io/file config-file)]
  (io/make-parents f)
  (when (.exists f)
    (let [b (first (remove #(.exists %) (map #(io/file (str config-file ".bak" %)) (iterate inc 1))))]
      (io/copy f b)
      (println "Backed up the old config to" (.getPath b))))
  (spit f (config-text cfg))
  (println)
  (println "Wrote" config-file)
  (println)
  (println "Start mdserve on the workspace you want the panel on:")
  (println (str "  " repo "/mdserve.sh"))
  (when (or use-examples? (seq own-projects))
    (println)
    (println "Every window opens on whichever workspace is current: nothing on Linux")
    (println "lets a program put a window on a given workspace. Move each project's")
    (println "window to its workspace after a start (on GNOME: Super+Shift+Page Up/Down).")))
