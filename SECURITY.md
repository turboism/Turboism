# Security Policy

## Supported versions

Turboism publishes installers and plugin packages through GitHub Releases.
Security fixes are handled on a best-effort basis and land on the current
development line, shipping with the next tagged release.

- **Latest stable release** — supported (currently the `0.44.x` line; see the
  tagged releases on the
  [Releases page](https://github.com/turboism/Turboism/releases)).
- **Nightly builds** (`v*-0.nightly.*`) — development snapshots; not
  separately supported, update to the latest build.
- **Older release lines** (0.43.x and earlier) — no backports; please
  upgrade.

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
