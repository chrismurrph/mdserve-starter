#!/usr/bin/env bb
;; md_lint — lint markdown for the seven failure modes that look fine in the
;; source and only break when mdserve (markdown-clj 1.12.1) renders them, plus
;; safe-edit helpers that refuse to write on an unexpected match count.
;;
;; Lint:  bb md_lint.bb FILE-OR-DIR ...        (exit 1 on hard errors, 0 else)
;; Edit:  bb md_lint.bb replace-once FILE NEEDLE REPLACEMENT
;;        bb md_lint.bb replace-n FILE N NEEDLE REPLACEMENT
;;        bb md_lint.bb delete-line FILE REGEX
;;        bb md_lint.bb replace-line FILE REGEX NEW-LINE
;;        bb md_lint.bb insert-before FILE ANCHOR TEXT
;;        bb md_lint.bb insert-after FILE ANCHOR TEXT
;; Also load-file-able: (load-file ".../md_lint.bb") then md-lint/check-doc etc.

(ns md-lint
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Table checks — copied from mdserve.clj so behaviour is identical there.
;; mdserve.clj delegates here at render time (with an inline fallback), so a
;; behaviour change in this section changes the viewer's banner too.
;; ---------------------------------------------------------------------------

(def table-separator-re
  "Second line of a GitHub table: |---|---| with optional alignment colons."
  #"\|(?:\s*:?-+:?\s*\|)+\s*")

(defn- indented-table-line?
  "A line indented 1-3 spaces whose content starts with |. Exactly the lines
   markdown-clj refuses to see as a table: at column 0 it parses them fine, and
   4+ spaces is an indented code block which must stay one."
  [line]
  (some? (re-matches #" {1,3}\|.*" line)))

(defn dedent-indented-tables
  "Markdown PRE-processor: shift GitHub tables that sit indented inside a list
   item back to column 0 so markdown-clj recognises them. Mirrors mdserve.clj's
   pre-processor exactly, so the lint sees the same text the parser will."
  [body]
  (let [lines (str/split-lines body)
        n     (count lines)]
    (loop [i 0 in-fence? false out []]
      (if (>= i n)
        (str (str/join "\n" out) "\n")
        (let [line (nth lines i)]
          (cond
            (re-matches #"\s*(```|~~~).*" line)
            (recur (inc i) (not in-fence?) (conj out line))

            in-fence?
            (recur (inc i) in-fence? (conj out line))

            (and (indented-table-line? line)
                 (< (inc i) n)
                 (indented-table-line? (nth lines (inc i)))
                 (re-matches table-separator-re (str/triml (nth lines (inc i)))))
            (let [j (loop [k i]
                      (if (and (< k n) (indented-table-line? (nth lines k)))
                        (recur (inc k)) k))]
              (recur j in-fence?
                     (into out (map str/triml (subvec (vec lines) i j)))))

            :else
            (recur (inc i) in-fence? (conj out line))))))))

(def wide-cell-threshold
  "Characters in one table cell beyond which a non-wrapping rendered table is
   unusably wide. Calibrated against the doc corpus: cells Chris reads daily
   without complaint top out near 470, the unreadable ones run 600-1500+, so
   500 flags the pathology without crying wolf on every chatty table."
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

(defn check-doc-health
  "Scan markdown BODY (post-dedent, i.e. as the parser will see it; line
   numbers are unchanged by dedenting) for GitHub-table problems. Returns
   {:structural [..] :legibility [..]} — both empty for a healthy document.
   Structural issues LOSE content in markdown-clj 1.12.1: :blank-split (a
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

(defn structural-issue-text
  "One-line human description of a structural (content-losing) table issue."
  [{:keys [type table-line line blank-line lost-rows first-lost rows
           row-cells header-cells]}]
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
                     " — the extra cells will be silently dropped.")))

(defn legibility-issue-text
  "One-line human description of an over-wide (but valid) table."
  [{:keys [table-line wide-cells max-len max-line]}]
  (str "Table starting line " table-line ": " wide-cells
       (if (= 1 wide-cells) " row has a cell" " rows have cells") " over "
       wide-cell-threshold " characters (longest " max-len " chars, line "
       max-line ") — too wide to read without endless sideways scrolling."))

;; ---------------------------------------------------------------------------
;; Heading slugs — copied from mdserve.clj's github-heading-slug so anchor
;; checks agree with the ids the renderer's anchorize-headings emits.
;; ---------------------------------------------------------------------------

(defn github-heading-slug
  "GitHub-style anchor slug for a heading's TEXT content: lowercase; drop every
   character that is not alphanumeric, space, hyphen or underscore (backticks,
   em/en dashes, colons, parentheses, / and friends simply vanish — an em-dash
   does NOT become a hyphen); then collapse each run of whitespace to a single
   hyphen. HTML entities are unescaped first so &amp; contributes nothing
   rather than the letters \"amp\". Byte-identical to mdserve.clj's fn, so a
   link this check passes lands on a real id in the rendered page."
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

(defn- heading-source-text
  "The reader-visible text of an ATX heading's source: leading #s and trailing
   closing #s removed, [text](url) links collapsed to their text (backticks and
   emphasis markers need no stripping — the slug's character filter drops
   them). What GitHub/mdserve slug from."
  [line]
  (-> line
      (str/replace #"^\s{0,3}#{1,6}\s+" "")
      (str/replace #"\s+#+\s*$" "")
      (str/replace #"!?\[([^\]]*)\]\([^)]*\)" "$1")))

(defn heading-slugs
  "Set of anchor slugs the rendered document's headings carry, from ATX (#)
   headings outside code fences. Duplicate slugs get -1, -2, ... suffixes in
   document order, matching mdserve's anchorize-headings. Setext (underlined)
   headings are not handled — the corpus does not use them."
  [body]
  (let [lines (str/split-lines body)]
    (loop [ls lines in-fence? false seen {} slugs #{}]
      (if-let [line (first ls)]
        (cond
          (re-matches #"\s*(```|~~~).*" line)
          (recur (rest ls) (not in-fence?) seen slugs)

          (and (not in-fence?) (re-matches #"\s{0,3}#{1,6}\s+.*" line))
          (let [base (github-heading-slug (heading-source-text line))
                n    (get seen base 0)
                slug (if (zero? n) base (str base "-" n))]
            (if (str/blank? base)
              (recur (rest ls) in-fence? seen slugs)
              (recur (rest ls) in-fence? (assoc seen base (inc n))
                     (conj slugs slug))))

          :else
          (recur (rest ls) in-fence? seen slugs))
        slugs))))

;; ---------------------------------------------------------------------------
;; Blockquoted tables, dangling anchors, broken sibling links.
;; ---------------------------------------------------------------------------

(defn- strip-inline-code
  "LINE with `code spans` blanked out, so pipes and pseudo-links inside inline
   code are never mistaken for real table rows or links."
  [line]
  (str/replace line #"`[^`]*`" ""))

(defn- unfenced-lines
  "Seq of [1-based-line-number line] for every line of BODY outside ```/~~~
   code fences."
  [body]
  (loop [ls (map-indexed vector (str/split-lines body)) in-fence? false out []]
    (if-let [[i line] (first ls)]
      (cond
        (re-matches #"\s*(```|~~~).*" line)
        (recur (rest ls) (not in-fence?) out)

        in-fence?
        (recur (rest ls) in-fence? out)

        :else
        (recur (rest ls) in-fence? (conj out [(inc i) line])))
      out)))

(defn- blockquote-pipe-row?
  "Line is a blockquote whose quoted content is a pipe table row."
  [line]
  (some? (re-matches #"\s{0,3}>\s*\|.*" line)))

(defn- blockquote-inner
  "The quoted content of a blockquote line, > markers stripped."
  [line]
  (str/replace line #"^\s{0,3}(>\s?)+" ""))

(defn check-blockquoted-tables
  "GitHub tables inside a blockquote (> | a | b |). markdown-clj does NOT
   render tables in blockquotes: every pipe comes out as literal text and the
   |---| separator row renders as mangled &mdash; entities. Hard error —
   content is mangled, and it looks perfectly fine in the source."
  [body]
  (let [lines (vec (str/split-lines body))
        n (count lines)]
    (loop [i 0 in-fence? false issues []]
      (if (>= i n)
        issues
        (let [line (nth lines i)]
          (cond
            (re-matches #"\s*(```|~~~).*" line)
            (recur (inc i) (not in-fence?) issues)

            in-fence?
            (recur (inc i) in-fence? issues)

            (and (blockquote-pipe-row? line)
                 (< (inc i) n)
                 (blockquote-pipe-row? (nth lines (inc i)))
                 (re-matches table-separator-re
                             (str/trim (blockquote-inner (nth lines (inc i))))))
            (let [j (loop [k i]
                      (if (and (< k n) (blockquote-pipe-row? (nth lines k)))
                        (recur (inc k)) k))]
              (recur j in-fence?
                     (conj issues
                           {:type :blockquoted-table :line (inc i)
                            :rows (- j i 2)
                            :text (str "Table at line " (inc i) " sits inside a"
                                       " blockquote (> | ... |) — markdown-clj"
                                       " does not render tables in blockquotes:"
                                       " all " (- j i) " lines come out as"
                                       " literal pipe text and the separator"
                                       " row as mangled &mdash; entities"
                                       " (content mangled). Use a bullet list"
                                       " inside the quote, or move the table"
                                       " out of it.")})))

            :else
            (recur (inc i) in-fence? issues)))))))

(defn check-dangling-anchors
  "In-document links [text](#slug) whose slug matches no heading in BODY.
   The source reads fine; in the render the link is simply dead — clicking it
   goes nowhere. Uses the same slug algorithm as the renderer, so agreement is
   by construction. Hard error."
  [body]
  (let [slugs (into (heading-slugs body)
                    ;; inline HTML targets too, e.g. <a id="g-term"></a> in a glossary
                    (map second (re-seq #"\bid=[\"']([^\"']+)[\"']"
                                        (str/join "\n" (map second (unfenced-lines body))))))]
    (vec
     (for [[ln line] (unfenced-lines body)
           [_ slug] (re-seq #"\]\(#([^)\s]*)\)" (strip-inline-code line))
           :when (not (contains? slugs slug))]
       {:type :dangling-anchor :line ln :slug slug
        :text (str "Link (#" slug ") at line " ln " matches no heading in this"
                   " document — the rendered link is dead (clicking it does"
                   " nothing). Renamed or deleted heading?")}))))

(defn check-sibling-links
  "Links to .md files — [text](OTHER.md), (sub/OTHER.md) or (/abs/path/OTHER.md)
   — whose target does not exist. Relative targets resolve against DIR (the
   document's directory); absolute ones are filesystem paths, which is what this
   corpus uses to reach the other doc world. This is how the doc set
   cross-references, and a file rename silently 404s every inbound link. Hard
   error. URLs with a scheme are left alone.

   Absolute targets were skipped until 2026-08-26, which is how two backticked
   paths inside DATABASE.md's link targets went unnoticed in both worlds.
   Relative targets still need DIR; absolute ones are checked without it."
  [body dir]
  (vec
   (for [[ln line] (unfenced-lines body)
         [_ target] (re-seq #"\]\(([^)#\s]+\.(?i:md))(?:#[^)]*)?\)"
                            (strip-inline-code line))
         :let [path (str/replace target "%20" " ")
               abs? (str/starts-with? path "/")
               f (if abs? (io/file path) (when dir (io/file dir path)))]
         :when (and (not (re-find #"^[a-z][a-z0-9+.-]*:" path))
                    f
                    (not (.exists f)))]
     {:type :broken-sibling-link :line ln :target target
      :text (str "Link (" target ") at line " ln ": no such file"
                 (if abs? "" (str " relative to " dir))
                 " — the link 404s in the render. Renamed or moved"
                 " target?")})))

(defn check-backticked-link-targets
  "A backtick INSIDE a link target — [text](`path`) — which puts the backtick
   into the URL, so the link 404s. Backticks in the link TEXT are correct and
   left alone; only the target is wrong.

   Deliberately reads the RAW line. check-sibling-links runs on
   strip-inline-code, which removes the backticked span before the link regex
   sees it, so a backticked target degrades into something that no longer looks
   like a .md link at all and escapes every other check.

   Real incident (2026-08-26): over-eager backticking put both of DATABASE.md's
   router links inside their own URLs — dead in both doc worlds, and invisible
   to the linter until this check existed."
  [body]
  (vec
   (for [[ln line] (unfenced-lines body)
         [_ target] (re-seq #"\]\(([^)]*`[^)]*)\)" line)]
     {:type :backticked-link-target :line ln :target target
      :text (str "Link target (" target ") at line " ln " contains a backtick"
                 " — the backtick becomes part of the URL and the link 404s."
                 " Backticks belong in the link text, not the target.")})))

;; ---------------------------------------------------------------------------
;; Underscore mangling — markdown-clj pairs ANY two underscores as emphasis,
;; including intraword ones CommonMark would leave alone.
;; ---------------------------------------------------------------------------

(def ^:private intraword-underscore-re
  "An underscore with an alphanumeric on BOTH sides — the kind that belongs to a
   snake_case term rather than to deliberate _emphasis_. Two of these in one
   block pair up and eat everything between them. The lookarounds give three
   exemptions for free: an escaped \\_ (backslash is not alphanumeric), the
   markers of __bold__ (an _ next to another _ fails both sides), and real
   _italic_ (its markers sit against whitespace)."
  #"(?<=[A-Za-z0-9])_(?=[A-Za-z0-9])")

(defn- strip-link-targets
  "LINE with the (target) half of every ](target) blanked. Measured: markdown-clj
   leaves underscores in a link TARGET alone but mangles them in the link TEXT,
   so the text must stay counted."
  [line]
  (str/replace line #"\]\([^)]*\)" "]()"))

(defn- underscore-block-start?
  "Line that begins a new block-level element, so emphasis cannot reach back
   past it. Measured: two tight list items each holding one intraword
   underscore do NOT pair — without this split they would sum to two and
   false-positive, which is the one realistic false alarm this check has."
  [line]
  (some? (or (re-matches #"\s*([-*+]|\d+[.)])\s+.*" line)
             (re-matches #"\s{0,3}#{1,6}\s+.*" line)
             (re-matches #"\s{0,3}>.*" line)
             (re-matches #" {0,3}\|.*" line))))

(defn- list-item-line?
  "A bullet or ordered list marker at the start of a line."
  [line]
  (some? (re-matches #"\s*([-*+]|\d+[.)])\s+.*" line)))

(defn- underscore-blocks
  "BODY outside code fences, grouped into the runs emphasis can pair within:
   split at blank lines and at every block-level line start. Each block is a
   vector of [1-based-line-number counted-text], the text having had inline
   code spans and link targets blanked.

   Lines indented 4+ spaces at block level are INDENTED CODE BLOCKS — measured:
   markdown-clj renders their underscores literally — so they are skipped. The
   same indent INSIDE a list item is continuation text, not code, so the list
   flag keeps those counted. Accepted false negative: a mangled continuation
   line under a list that has already been closed by a blank line."
  [body]
  (loop [ls (unfenced-lines body) cur [] in-list? false out []]
    (if-let [[ln line] (first ls)]
      (let [text (-> line strip-inline-code strip-link-targets)
            close (fn [out] (if (seq cur) (conj out cur) out))]
        (cond
          (str/blank? line)
          (recur (rest ls) [] in-list? (close out))

          (and (not in-list?) (re-matches #" {4,}\S.*" line))
          (recur (rest ls) [] false (close out))

          (list-item-line? line)
          (recur (rest ls) [[ln text]] true (close out))

          (underscore-block-start? line)
          (recur (rest ls) [[ln text]] false (close out))

          :else
          (recur (rest ls) (conj cur [ln text]) in-list? out)))
      (if (seq cur) (conj out cur) out))))

(defn check-underscore-mangling
  "Blocks holding two or more INTRAWORD underscores. markdown-clj 1.12.1 does
   not implement CommonMark's intraword-underscore rule: it pairs any two
   underscores as emphasis, so `foo_bar_baz` renders `foo<i>bar</i>baz` — the
   underscores VANISH into italics, taking no visible marker with them. The
   reader sees `foobarbaz` and cannot tell whether it is a formatting fault or
   an unresolved placeholder. Bites [[wiki_style_links]], bare snake_case terms,
   bare URLs with underscores, and link text. Pairing crosses lines within a
   block, so two separate single-underscore words on neighbouring lines mangle
   each other. One intraword underscore alone is harmless and is not flagged.
   Hard error — content is mangled, and the source looks perfectly fine.
   Known false negative, accepted: `a__b__c` renders as bold and is not caught."
  [body]
  (vec
   (for [block (underscore-blocks body)
         :let [hits (for [[ln text] block
                          :let [c (count (re-seq intraword-underscore-re text))]
                          :when (pos? c)]
                      [ln c])
               total (reduce + 0 (map second hits))]
         :when (>= total 2)
         :let [ln (ffirst hits)
               terms (->> (map (fn [[_ text]] text) block)
                          (mapcat #(re-seq #"[A-Za-z0-9]+(?:_[A-Za-z0-9]+)+" %))
                          distinct
                          (take 3))]]
     {:type :underscore-mangling :line ln :count total
      :text (str "Line " ln ": " total " intraword underscores in one block"
                 (when (seq terms)
                   (str " (" (str/join ", " terms) ")"))
                 " — markdown-clj pairs them as emphasis, so the underscores"
                 " and the text between them render as italics with the"
                 " underscores GONE (content mangled; the source looks fine)."
                 " Wrap each term in `backticks`.")})))

;; ---------------------------------------------------------------------------
;; Combined check + CLI.
;; ---------------------------------------------------------------------------

(defn check-doc
  "All eight checks over markdown BODY; PATH (may be nil) supplies the directory
   for sibling-link resolution. Returns {:structural [..] :legibility [..]}
   where every issue carries a human :text — structural issues lose or mangle
   content (hard errors), legibility issues merely render badly (warnings).
   The shape matches mdserve.clj's check-doc-health so the viewer's health
   banner can consume it directly."
  [body path]
  (let [{:keys [structural legibility]} (check-doc-health body)
        dir (some-> path io/file .getAbsoluteFile .getParent)]
    {:structural (vec (concat
                       (map #(assoc % :text (structural-issue-text %)) structural)
                       (check-blockquoted-tables body)
                       (check-dangling-anchors body)
                       (check-sibling-links body dir)
                       (check-backticked-link-targets body)
                       (check-underscore-mangling body)))
     :legibility (mapv #(assoc % :text (legibility-issue-text %)) legibility)}))

(defn lint-file
  "Lint one file, print its report (nothing when healthy), and return
   {:errors n :warnings n}."
  [path]
  (let [{:keys [structural legibility]} (check-doc (slurp path) path)]
    (when (or (seq structural) (seq legibility))
      (println path)
      (doseq [{:keys [text]} structural] (println "  ERROR" text))
      (doseq [{:keys [text]} legibility] (println "  WARN " text))
      (println))
    {:errors (count structural) :warnings (count legibility)}))

(defn- md-files
  "The .md files ARG names: itself for a file, every .md under it (recursive,
   dot-directories skipped) for a directory."
  [arg]
  (let [f (io/file arg)]
    (if (.isDirectory f)
      (->> (file-seq f)
           (remove #(some (fn [seg] (str/starts-with? seg "."))
                          (rest (str/split (str %) #"/"))))
           (filter #(and (.isFile %) (str/ends-with? (str/lower-case (str %)) ".md")))
           (map str)
           sort)
      [arg])))

;; ---------------------------------------------------------------------------
;; Safe-edit helpers. Every one asserts its match count and REFUSES to write
;; on a miss or a surplus — throwing ex-info that reports what it found —
;; because a silent wrong edit to a document costs more than a loud failure.
;; ---------------------------------------------------------------------------

(defn count-occurrences
  "Number of non-overlapping occurrences of NEEDLE in S."
  [s needle]
  (loop [from 0 n 0]
    (let [i (str/index-of s needle from)]
      (if (nil? i) n (recur (+ i (count needle)) (inc n))))))

(defn- guard-needle
  "Reject blank or absurdly short needles — a single space as a sentinel once
   nearly rewrote a whole file."
  [needle]
  (when (or (nil? needle) (str/blank? needle))
    (throw (ex-info "Refusing a blank needle — it would match everywhere."
                    {:needle needle}))))

(defn- write-lines
  "Write LINES back to PATH, preserving whether the original ended in \\n."
  [path lines original]
  (spit path (str (str/join "\n" lines)
                  (when (str/ends-with? original "\n") "\n"))))

(defn replace-exactly-once
  "Replace NEEDLE with REPLACEMENT in PATH iff NEEDLE occurs exactly once.
   Otherwise throws with the count found and writes NOTHING."
  [path needle replacement]
  (guard-needle needle)
  (let [s (slurp path)
        n (count-occurrences s needle)]
    (if (= 1 n)
      (do (spit path (str/replace-first s needle replacement))
          {:path path :replaced 1})
      (throw (ex-info (str "replace-exactly-once: needle occurs " n
                           " times in " path " (expected exactly 1)"
                           " — nothing written.")
                      {:path path :needle needle :found n})))))

(defn replace-n-times
  "Replace every NEEDLE with REPLACEMENT in PATH iff NEEDLE occurs exactly
   EXPECTED times. Otherwise throws with the count found and writes NOTHING."
  [path needle replacement expected]
  (guard-needle needle)
  (let [s (slurp path)
        n (count-occurrences s needle)]
    (if (= expected n)
      (do (spit path (str/replace s needle replacement))
          {:path path :replaced n})
      (throw (ex-info (str "replace-n-times: needle occurs " n " times in "
                           path " (expected " expected ") — nothing written.")
                      {:path path :needle needle :found n
                       :expected expected})))))

(defn- line-matcher
  "Whole-line matcher from a predicate fn or a regex (regex is anchored via
   re-matches — it must match the ENTIRE line, so a loose fragment cannot hit
   a heading when a table row was meant)."
  [pred-or-regex]
  (if (instance? java.util.regex.Pattern pred-or-regex)
    #(some? (re-matches pred-or-regex %))
    pred-or-regex))

(defn- the-one-matching-line
  "Index of the single line of LINES matching PRED-OR-REGEX; throws (listing
   every match's line number and content) when the count is not exactly 1."
  [path lines pred-or-regex op-name]
  (let [match? (line-matcher pred-or-regex)
        hits (vec (keep-indexed (fn [i l] (when (match? l) [i l])) lines))]
    (if (= 1 (count hits))
      (ffirst hits)
      (throw (ex-info (str op-name ": " (count hits) " lines match in " path
                           " (expected exactly 1) — nothing written."
                           (when (seq hits)
                             (str " Matches: "
                                  (str/join "; "
                                            (map (fn [[i l]]
                                                   (str "line " (inc i) " "
                                                        (pr-str l)))
                                                 hits)))))
                      {:path path :matches (mapv (fn [[i l]] [(inc i) l]) hits)})))))

(defn delete-line-matching
  "Delete the single line of PATH matching PRED-OR-REGEX (regex must match the
   whole line). Throws, listing all matches, unless exactly one line matches."
  [path pred-or-regex]
  (let [s (slurp path)
        lines (vec (str/split-lines s))
        i (the-one-matching-line path lines pred-or-regex "delete-line-matching")]
    (write-lines path (into (subvec lines 0 i) (subvec lines (inc i))) s)
    {:path path :deleted-line (inc i) :was (nth lines i)}))

(defn replace-line-matching
  "Replace the single line of PATH matching PRED-OR-REGEX (regex must match
   the whole line) with NEW-LINE. Throws, listing all matches, unless exactly
   one line matches."
  [path pred-or-regex new-line]
  (let [s (slurp path)
        lines (vec (str/split-lines s))
        i (the-one-matching-line path lines pred-or-regex "replace-line-matching")]
    (write-lines path (assoc lines i new-line) s)
    {:path path :replaced-line (inc i) :was (nth lines i)}))

(defn- insert-at-anchor
  "Shared body of insert-before/insert-after: ANCHOR must occur exactly once
   in PATH (as a substring); TEXT (possibly multi-line) is inserted as whole
   lines before or after the line containing it."
  [path anchor text where op-name]
  (guard-needle anchor)
  (let [s (slurp path)
        n (count-occurrences s anchor)]
    (when (not= 1 n)
      (throw (ex-info (str op-name ": anchor occurs " n " times in " path
                           " (expected exactly 1) — nothing written.")
                      {:path path :anchor anchor :found n})))
    (let [lines (vec (str/split-lines s))
          i (first (keep-indexed (fn [i l] (when (str/includes? l anchor) i))
                                 lines))
          at (case where :before i :after (inc i))
          new-lines (str/split-lines text)]
      (write-lines path (into (into (subvec lines 0 at) new-lines)
                              (subvec lines at))
                   s)
      {:path path :inserted-at-line (inc at) :lines (count new-lines)})))

(defn insert-before
  "Insert TEXT as whole line(s) immediately before the line containing ANCHOR.
   ANCHOR must occur exactly once in PATH, else throws and writes nothing."
  [path anchor text]
  (insert-at-anchor path anchor text :before "insert-before"))

(defn insert-after
  "Insert TEXT as whole line(s) immediately after the line containing ANCHOR.
   ANCHOR must occur exactly once in PATH, else throws and writes nothing."
  [path anchor text]
  (insert-at-anchor path anchor text :after "insert-after"))

;; ---------------------------------------------------------------------------
;; CLI dispatch.
;; ---------------------------------------------------------------------------

(def ^:private edit-commands
  #{"replace-once" "replace-n" "delete-line" "replace-line"
    "insert-before" "insert-after"})

(defn- run-edit
  "Dispatch one edit subcommand; prints the result map. Exits 2 on refusal so
   an edit failure is distinguishable from a lint failure (1)."
  [[cmd & args]]
  (try
    (let [result
          (case cmd
            "replace-once" (let [[f needle repl] args]
                             (replace-exactly-once f needle repl))
            "replace-n" (let [[f n needle repl] args]
                          (replace-n-times f needle repl (parse-long n)))
            "delete-line" (let [[f rx] args]
                            (delete-line-matching f (re-pattern rx)))
            "replace-line" (let [[f rx new-line] args]
                             (replace-line-matching f (re-pattern rx) new-line))
            "insert-before" (let [[f anchor text] args]
                              (insert-before f anchor text))
            "insert-after" (let [[f anchor text] args]
                             (insert-after f anchor text)))]
      (println "OK" (pr-str result)))
    (catch Exception e
      (println "REFUSED:" (ex-message e))
      (System/exit 2))))

(defn -main
  "Lint mode: every arg is a file or directory; report per file; exit 1 when
   any hard error was found, 0 when clean or warnings only. Edit mode: first
   arg is one of the edit subcommands (see file header)."
  [& args]
  (cond
    (empty? args)
    (do (println "usage: md_lint.bb FILE-OR-DIR ...  |  md_lint.bb <edit-cmd> ...")
        (System/exit 2))

    (contains? edit-commands (first args))
    (run-edit args)

    :else
    (let [files (mapcat md-files args)
          totals (reduce (fn [acc f]
                           (if (.exists (io/file f))
                             (merge-with + acc (lint-file f))
                             (do (println f)
                                 (println "  ERROR file does not exist")
                                 (println)
                                 (merge-with + acc {:errors 1 :warnings 0}))))
                         {:errors 0 :warnings 0} files)]
      (println (count files) "file(s) checked —"
               (:errors totals) "error(s)," (:warnings totals) "warning(s)")
      (System/exit (if (pos? (:errors totals)) 1 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
