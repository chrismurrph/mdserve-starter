# Alpha

An example project. It lives on **workspace 1**, so it gets a window of its own
there, with its command dashboard (blue dot) and its Explorer (green dot).

## Try the buttons

On the dashboard:

- `web` — a service. **▶ Launch** starts a small web server on port 8701; the
  dot turns green once the port answers. **■ Stop** ends it.
- `check` — a one-shot. **▶ Run** opens its log in a new tab and follows it
  until the run ends; the dot then says whether it passed.

> Both scripts are in `examples/bin`, which `install.bb` put on the commands'
> PATH. Your own commands normally live in `~/bin`.

## Try showing a doc

From a terminal:

```bash
curl "http://localhost:7000/show$(realpath notes/rendering.md)"
```

It opens in this window, because the file lives under this project.
