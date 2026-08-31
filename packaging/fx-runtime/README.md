# Managed fx runtime payload

Turboism distributes fx `0.0.5` as a product payload outside the plugin JAR.

- Upstream source commit: `df7e6245e1992758d4060c97477ceafa27770551`
- Runtime destination: `runtimes/fx/0.0.5/<platform>/`
- Normal launch verifies the installed executable size and SHA-256 from the closed plugin manifest.
- Every platform directory includes `LICENSE`, `THIRD_PARTY_NOTICES.md`, `TURBOISM-DISTRIBUTION-NOTICE.txt`, and `manifest.properties` beside the executable.
- Provider credentials, MCP bearer material, and fx durable data are never part of this payload.

## Delivery

The exact supported platforms are Linux x86-64, Linux ARM64, macOS x86-64,
macOS ARM64, and Windows x86-64.

The four Linux/macOS executables are unmodified official upstream release
assets. Their archive names, sizes, SHA-256 values, reviewed GitHub release
asset paths, installed executable identities, and legal-file identities are
pinned in `manifest.properties`. Java Full installs only the host's matching
platform. On explicit user request, supported Linux/macOS Thin installations
can download that exact pinned archive and activate it atomically.

Windows x86-64 uses a Turboism product payload because upstream fx v0.0.5 has
no Windows release archive. Windows NSIS Full, Full ZIP, and Java Full carry:

```text
runtimes/fx/0.0.5/windows-x86_64/
  fx.exe
  LICENSE
  THIRD_PARTY_NOTICES.md
  TURBOISM-DISTRIBUTION-NOTICE.txt
  manifest.properties
```

The executable identity is fixed at `11,174,912` bytes and SHA-256
`04eca2ccb0037d4080724ad644cb42a2605f610632e0e95148f077e1550c4541`.
It is labeled as a Turboism build of upstream fx v0.0.5, not as an official
Vercel Windows asset. There is no online Windows repair archive; product repair
or reinstall restores it. Lite remains plugin-free and runtime-free.

## Windows candidate limits

The Windows product build is intended to make the packaged ACP integration
usable in the current Windows candidate. It does not imply feature parity with
the official Linux/macOS assets. In particular, Turboism does not claim
Windows parity for durable sessions, ACP MCP-server support, native tools, or
networking, process, and persistence functionality disabled in this build.
The plugin still verifies the exact executable identity before launch, and its
normal ACP and authenticated Turboism MCP availability checks remain in force.
Host-facing operations remain limited to exact Cubism 5.2.03/5.3.02/5.3.03
admission and the typed services available in the active host.

Vercel does not sponsor or endorse Turboism or this integration.
