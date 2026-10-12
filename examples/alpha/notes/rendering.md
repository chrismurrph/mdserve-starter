# What the renderer handles

Plain markdown, plus a few things that usually come out wrong.

## Tables

| Thing | Where | Note |
|---|---|---|
| Markdown | anywhere | rendered |
| `.clj`, `.sh`, `.json` | anywhere | shown as highlighted code |
| Images, PDFs | anywhere | served as they are |

## Entry lists

- `show` — opens a doc as a tab in the right window
- `show-link` — the same for an ordinary web page
- `remember-tabs` — saves the open tabs as EDN

## Callouts

> A blockquote renders as a callout box. Keep tables out of them; the health
> banner at the top of a page says so when you don't.

## Diffs

```diff
- (def port 7000)
+ (def port (or (:port config) 7000))
```

## Code

```clojure
(defn greet [name]
  (str "hello, " name))
```

PageDown and PageUp jump from heading to heading.
