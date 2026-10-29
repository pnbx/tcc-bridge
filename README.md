# TccBridge

**English** · [فارسی](README.fa.md)

[![build](https://github.com/pnbx/tcc-bridge/actions/workflows/build.yml/badge.svg)](https://github.com/pnbx/tcc-bridge/actions/workflows/build.yml)
![Paper](https://img.shields.io/badge/Paper-1.21.8-2b2b2b)
![Java](https://img.shields.io/badge/Java-21-orange)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A small Paper plugin that connects the **TCC game server** to **site.tccmc.ir**.

The website and the game host live on networks that cannot always reach each
other — the Iranian game host drops foreign traffic — so the plugin drives the
connection **outward** instead of waiting for the site to call in. No inbound
port, no public console, no RCON exposure.

---

## What it does

| Direction | Endpoint | What |
|---|---|---|
| server → site | `POST /api/mc/ingest` | real status every 20 s: players, max, MOTD, version, TPS, MSPT |
| site → server | `POST /api/mc/poll` | collects queued console commands (store rank grants) |
| server → site | `POST /api/mc/ack` | reports the result of each executed command |
| server → site | console, metrics, files | feeds the developer/owner panels on the site |
| server → site | `POST /api/mc/stats` | cumulative player statistics via PlaceholderAPI |
| server → site | `POST /api/mc/tiers` | tier grants mirrored from the server's tier system |
| in game | `/site connect <code>` | proves account ownership and links it to the website |

The site never invents data: if the bridge is down, panels say so.

## Commands

| Command | Who | Purpose |
|---|---|---|
| `/site connect <code>` | players | link the Minecraft account to site.tccmc.ir |
| `/site status` | everyone | is the bridge reachable, when was the last push |
| `/site reload` | `tccbridge.admin` | re-read `config.yml` without restarting |

Alias: `/tccweb`.

## Install

1. Grab the jar — GitHub Actions builds it on every push and publishes it as a
   release asset — and drop it in `plugins/`.
2. Start the server once so `plugins/TccBridge/config.yml` is created.
3. Set `site-url` and put the website's `MC_INGEST_TOKEN` in `token`.
4. Restart, or run `/site reload` as an operator.

```yaml
site-url: "https://site.tccmc.ir"
token: "<MC_INGEST_TOKEN from the website's .env>"
push-interval-seconds: 20
poll-interval-seconds: 10
console-push-seconds: 5
tier-push-seconds: 60
stats-push-seconds: 900
stats-players: 120
```

## Build from source

Requires JDK 21.

```bash
gradle build          # jar lands in build/libs/TccBridge-<version>.jar
```

There is no Gradle wrapper in the repo; CI uses `gradle/actions/setup-gradle`.
If you prefer a wrapper, run `gradle wrapper` once and commit it.

## How it is put together

```
src/main/java/ir/tcc/bridge/
  TccBridge.java    plugin entry, command handling, scheduling
  StatsReader.java  cumulative player stats through PlaceholderAPI
  TierReader.java   reads the server's tier system and mirrors it
  LogTailer.java    tails latest.log for the site's console panel
  Fs.java           sandboxed file operations for the developer panel
  Json.java         dependency-free JSON writer
```

Everything is plain Java plus the Paper API — no shaded libraries, so the jar is
small and there is nothing to conflict with other plugins.

## Requirements

- Paper 1.21.8 (api-version 1.21), Java 21
- [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) for player
  statistics (optional but recommended)

## Security

- The plugin only makes **outbound** requests; nothing listens for the site.
- Every request carries the shared `X-TCC-Token`; keep `config.yml` out of public
  backups and never commit it.
- Console and file operations are reachable only through the site's staff panels,
  which enforce permissions and write an audit entry.

Report issues privately — see [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE) © TCC Minecraft
