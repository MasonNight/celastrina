# Extensions run in-process, unisolated

Celastrina v1 hosts CloudStream `.cs3` Extensions by bundling CloudStream's GPL-3.0 `library` at a pinned version and loading each Extension's DEX into the app's own process, guarded only by per-call timeouts and catching every `Throwable`. Running existing Extensions unchanged is the v1 headline, and neither CloudStream nor Aniyomi plugins can run in an `isolatedProcess` (no sockets, no files, and in-memory DEX loading needs API 26+, above our `minSdk 25`). So an Extension can read everything Celastrina can: other Sources' credentials, all app files, and the home network. We accept that risk and tell the Household so with a warning at every Extension Repository import, which has Cancel focused.

## Considered Options

- **Sandboxed JavaScript on Rhino 1.8.x.** It can be isolated cleanly, but no Extensions exist for it, so it would defeat the headline. Out of scope for v1.
- **A same-UID `:ext` process.** It contains crashes and hangs but is not a security boundary, and passing results over Binder costs effort. Revisit only if real Extensions crash the app.
- **Aniyomi APKs.** They need about 35 reimplemented stub classes and are untested below API 26.

See [Choose the Extension format and trust model](https://github.com/MasonNight/celastrina/issues/16).
