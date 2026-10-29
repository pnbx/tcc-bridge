# Contributing

```bash
gradle build     # JDK 21
```

CI runs the same build and publishes the jar as a release asset.

Please keep the plugin dependency-free: plain Java plus the Paper API, no shaded
libraries. Anything the site needs should go through an `/api/mc/*` endpoint and
be documented in the README table.

Two rules that matter:

- **Outbound only.** Do not add a listening socket.
- **Token on everything.** New endpoints authenticate with `X-TCC-Token` first.

Found a vulnerability? See [SECURITY.md](SECURITY.md), not a public issue.
