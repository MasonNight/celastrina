# Configuration over ADB through a shell-only ContentProvider

The agent following the Install Runbook configures an installed Celastrina (Sources, Extension Repositories, the TMDB key override) by running `adb shell content call` and `adb shell content query` against an exported ContentProvider. The provider accepts calls only from the `shell` user, root or Celastrina itself. A provider replies synchronously, so every write comes back with a validation and connection-test result the agent can act on. It needs no listening socket, and the trusted ADB connection the runbook already has is its only authentication. That matters on a TV that also runs unverified Extensions.

## Considered Options

- **A file pushed over ADB.** Secrets sit on shared storage, which other apps can read on API ≤ 28. The app only picks the file up at its next launch, and there's no reply.
- **A broadcast or intent.** A broadcast isn't delivered to an app that hasn't been opened since install, and neither gives an answer back.
- **A local HTTP endpoint on the TV.** It's exposed to the LAN and needs its own pairing.

See [Choose how the agent configures Sources on the TV](https://github.com/MasonNight/celastrina/issues/12).
