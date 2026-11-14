# Removed validation directories

Git history keeps every directory listed here. No Gradle task, CI workflow, reachable
script or documented procedure consumes them (issue #183). Each entry names the last
commit on `origin/main` that touched the directory and one command that restores it.

| Directory | Purpose | Last commit | Restore |
| --- | --- | --- | --- |
| `validation/atlas-image-kernel-probe` | Offline bytecode slices T030/T033/T035/T038/T039/T040 that compare reference and candidate Atlas kernels. | `55514b89e` | `git checkout 55514b89e -- validation/atlas-image-kernel-probe` |
| `validation/parameter-lock-host-probe` | Host exerciser that proves the production parameter-lock plugin blocks parameter mutation. | `8bfe6f982` | `git checkout 8bfe6f982 -- validation/parameter-lock-host-probe` |
| `validation/product-ui-lifecycle-host-probe` | Task-only agent that drives ACP, MCP, command-palette and perf-stats plugin enable and disable cycles. | `88a494973` | `git checkout 88a494973 -- validation/product-ui-lifecycle-host-probe` |
| `validation/0.70.0-acceptance` | Release-acceptance evidence notes for 0.70.0: representative host matrix, installer and Windows findings, plugin lifecycle, GIF recording review and the release-preparation checklist. | `c7e826e10` | `git checkout c7e826e10 -- validation/0.70.0-acceptance` |
| `validation/model-viewer-host-probe` | Test-only character viewer exerciser that drove the production viewer entrypoint through the generic Runner. | `afbf1c3c1` | `git checkout afbf1c3c1 -- validation/model-viewer-host-probe` |

Restore one directory only when a consumer returns. Add that consumer in the same change.
