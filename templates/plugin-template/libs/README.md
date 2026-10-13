Run `./scripts/setup.sh` (or `.\scripts\setup.ps1` on Windows) from the project
root to download `turboism-sdk-<version>.jar` and
`turboism-event-processor-<version>.jar` into this directory and verify their
`.sha256` checksums automatically.

For offline or manual setup, download both JARs (and their `.sha256` sidecars)
from the Turboism GitHub Releases page and place the JARs here:
https://github.com/turboism/Turboism/releases

When `turboism-sdk-*.jar` (and `turboism-event-processor-*.jar`) are present in
this directory, the build uses them directly; otherwise it falls back to
`dev.turboism:sdk` and `dev.turboism:event-processor` from `mavenLocal()`.
