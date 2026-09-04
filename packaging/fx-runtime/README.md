# Managed fx runtime fixture

fx `0.0.5` runtime assets are kept in this repository as a
repository-local development, review, and test fixture for the
development-only `turboism-with-fx` plugin; they are not staged into any
release package.

- Upstream source commit: `df7e6245e1992758d4060c97477ceafa27770551`
- Runtime destination: `runtimes/fx/0.0.5/<platform>/`
- Normal launch verifies the installed executable size and SHA-256 from the closed plugin manifest.
- Every platform directory includes `LICENSE`, `THIRD_PARTY_NOTICES.md`, `TURBOISM-DISTRIBUTION-NOTICE.txt`, and `manifest.properties` beside the executable.
- Provider credentials, MCP bearer material, and fx durable data are never part of these fixtures.

## Platforms

The exact fixture platforms are Linux x86-64, Linux ARM64, macOS x86-64,
macOS ARM64, and Windows x86-64.

The four Linux/macOS executables are unmodified official upstream release
assets. Their archive names, sizes, SHA-256 values, reviewed GitHub release
asset paths, installed executable identities, and legal-file identities are
pinned in `manifest.properties`.

Windows x86-64 uses a Turboism product build because upstream fx v0.0.5 has
no Windows release archive:

```text
runtimes/fx/0.0.5/windows-x86_64/
  fx.exe
  LICENSE
  THIRD_PARTY_NOTICES.md
  TURBOISM-DISTRIBUTION-NOTICE.txt
  manifest.properties
```

The executable identity is fixed at `11,144,192` bytes and SHA-256
`a36b0b209d933e4757d7e1a961d259d39a8d370b68cbde8e9cba227603ac63c2`.
It is labeled as a Turboism build of upstream fx v0.0.5, not as an official
Vercel Windows asset.

This directory is a repository-local source of review and plugin-test
fixtures. The development-only `turboism-with-fx` plugin is not part of any
Turboism release roster, and the runtime payloads here are not staged into any
release archive (Java installer, NSIS, or ZIP). The plugin JAR embeds the
closed identity manifest and legal files for self-verification, but the
runtime itself is only ever used from a developer/validation workspace.

## Windows candidate limits

The Windows product build is intended to make the packaged ACP integration
usable in the current Windows candidate. It admits only the exact authenticated
numeric-loopback HTTP MCP server supplied by Turboism ACP, and Windows sessions
remain ephemeral. It does not imply feature parity with the official
Linux/macOS assets for durable sessions, native tools, general networking,
process ownership, or persistence. The plugin still verifies the exact
executable identity before launch, and its normal ACP and authenticated
Turboism MCP availability checks remain in force.
Host-facing operations remain limited to exact Cubism 5.2.03/5.3.02/5.3.03
admission and the typed services available in the active host.

Vercel does not sponsor or endorse Turboism or this integration.
