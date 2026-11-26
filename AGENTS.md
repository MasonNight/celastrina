# Celastrina

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `MasonNight/celastrina`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Verifying on a device

On-device checks run on the `celastrina-tv` Android TV emulator: `CELASTRINA_ADB_SERIAL=emulator-5554 scripts/tv.sh …`.
See `docs/agents/emulator.md`. The household TV is the maintainer's; install on it only when they ask.
