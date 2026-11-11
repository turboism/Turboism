# Security Policy

## Supported versions

Turboism publishes installers and plugin packages through GitHub Releases.
Security fixes are handled on a best-effort basis and land on the current
development line, shipping with the next tagged release.

- **Latest stable release** — supported (currently the `0.67.x` line; see the
  tagged releases on the
  [Releases page](https://github.com/turboism/Turboism/releases)).
- **Nightly builds** (`v*-0.nightly.*`) — development snapshots; not
  separately supported, update to the latest build.
- **Older release lines** (0.44.x and earlier) — no backports; please
  upgrade.

## Integrity and trust anchors

Release `.sha256` sidecars, the plugin-template `setup.sh` check and a plugin
`packageHash` are published next to the files they describe, so they detect
corruption but do not defend against tampering. Independent trust anchors are
the Ed25519-signed plugin catalog, pinned Gradle verification metadata and
wrapper/JDK archive hashes, and R2 write verification. Installers are currently
unsigned.

Static verification record digests are provenance metadata, not independent
trust anchors. Runtime admission checks record schema and identity, host bytes
and selector structure. Compatibility probing binds the observed record digest
so a record changed before resolver construction is rejected. The repository's
record-pin manifest and its regeneration task have been retired.

Validation agent/fixture digests bind reports to the actual run inputs; package
sidecars record transfer integrity. Neither establishes publisher authenticity.

## Reporting a vulnerability

Please do **not** open a public issue for a security concern. Report the
vulnerability privately by email to the Turboism maintainers at:

- **contact@turboism.dev**, with `[SECURITY]` in the subject line.

Please include, when available:

- a description of the vulnerability and its impact;
- the affected component and version;
- steps to reproduce (minimal fixture or scenario);
- any proof-of-concept you are able to share.

## Scope

This policy covers the Turboism source code and its build, runtime, packaging,
and release tooling. It does not cover Live2D Cubism Editor, its bundled JRE,
or other separately installed components; please report issues in those
components to their respective vendors.
