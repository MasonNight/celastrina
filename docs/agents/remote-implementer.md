# Remote implementation

1. Create a ticket worktree. On Windows, use a short path under `C:\cw\<name>`.
2. Load the build environment with `. C:\Users\mason\.config\celastrina\env.ps1` in PowerShell or `. ~/.config/celastrina/env.sh` on macOS or Linux.
3. Implement test-first with the `tdd` skill. Run the ticket's host checks before committing.
4. Commit with the attribution trailer your harness provides for the model you used, then push.
5. Run any required [device checks](emulator.md) with `scripts/tv.sh`.
