# TccBridge

Paper plugin that connects the **TCC game server** to **site.tccmc.ir**.

The website and the game host live on networks that cannot always reach each
other (the Iranian game host drops foreign traffic), so the plugin drives the
connection outward instead of waiting for the site to call in:

| Direction | Endpoint | What it does |
|---|---|---|
| server → site | `POST /api/mc/ingest` | pushes real status every 20s: players, max, MOTD, version, TPS, MSPT |
| site → server | `POST /api/mc/poll` | collects queued console commands (store rank grants) |
| server → site | `POST /api/mc/ack` | reports the result of each executed command |
| in game | `/site connect <code>` | proves account ownership and links it to the website |

## Install

1. Build the jar (GitHub Actions builds it on every push) and drop it in `plugins/`.
2. Start the server once so `plugins/TccBridge/config.yml` is created.
3. Put the website's `MC_INGEST_TOKEN` in `config.yml` and set `site-url`.
4. Restart the server (or run `/site reload` as an operator).

## Commands

| Command | Who | Purpose |
|---|---|---|
| `/site connect <code>` | players | link the Minecraft account to site.tccmc.ir |
| `/site status` | everyone | is the bridge reachable, when was the last push |
| `/site reload` | `tccbridge.admin` | re-read config.yml without restarting |

Alias: `/tccweb`.

## Config

```yaml
site-url: "https://site.tccmc.ir"
token: "<MC_INGEST_TOKEN from the website's .env>"
push-interval-seconds: 20
poll-interval-seconds: 10
```

The plugin has **no dependencies** — JSON handling is self-contained, so the jar
never needs shading and works on any Paper 1.21.x server.
