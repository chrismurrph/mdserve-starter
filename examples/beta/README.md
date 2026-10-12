# Beta

The second example project, on **workspace 3**. Its `web` service uses port
8703, so both projects can run at once.

Its `check` is set up to **fail**, to show a red dot and a failing log.

Show this file from a terminal and it lands in Beta's window:

```bash
curl "http://localhost:7000/show$(realpath README.md)"
```

Add `?ws=1` and it goes to Alpha's window instead.
