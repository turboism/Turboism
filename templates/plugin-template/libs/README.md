Drop `turboism-sdk-<version>.jar` here.

Download it (and its `.sha256` sidecar) from the Turboism GitHub Releases page:
https://github.com/turboism/Turboism/releases

When a `turboism-sdk-*.jar` is present in this directory, the build uses it
directly; otherwise it falls back to `dev.turboism:sdk` from `mavenLocal()`.
