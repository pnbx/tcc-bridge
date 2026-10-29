# Security policy

## Reporting

Do not open a public issue. Use GitHub's
[private vulnerability reporting](https://github.com/pnbx/tcc-bridge/security/advisories/new)
or contact the maintainer on Discord / Telegram.

## Model

TccBridge is an **outbound-only** agent. It never opens a port, so the game
server gains no new inbound surface. The things worth protecting:

1. **The shared token.** Every request carries `X-TCC-Token`, compared on the
   site side. A leaked token lets an attacker post fake status or read the
   command queue. Keep `plugins/TccBridge/config.yml` out of public backups.
2. **The command queue.** Only the site can enqueue, and the site checks a staff
   permission plus writes an audit entry before it does.
3. **The file sandbox.** `Fs.java` must stay inside the server root: no `..`,
   no absolute paths, no symlink escape.
4. **Deserialisation.** There is no reflective deserialisation of remote data;
   `Json.java` only writes.

## If you deploy it

- [ ] `token` is a long random string, unique to this server
- [ ] `config.yml` is not world-readable and not in a public repo
- [ ] the site's `MC_INGEST_TOKEN` matches, and is rotated if either file leaked
- [ ] `/site reload` is restricted to `tccbridge.admin`
