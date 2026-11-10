# 0.70.0 representative exact-host matrix

Status: audited scheduling specification. The audit performed no host runs.
The main agent reports native 5.3.04 sessions; they are not Stage 1 to Stage 3 Core acceptance.
This document does not claim behavior acceptance.

The main agent later [acquired all eight official endpoint inputs](host-matrix-official-acquisition.md).
That acquisition supersedes the missing-input status below, but it does not prove native behavior.

## Scope and rules

This audit reads product source, installed Cubism inventories and compatibility records.
It writes this file and the retained raw inventory output.
The audit did not launch Cubism, edit an official installation or configuration, acquire a
missing installer, touch a user model, alter a compatibility oracle or change product source.
The main agent owns host scheduling and final acceptance.
This machine is Windows.

The matrix uses the repository rule in
[CONTRIBUTING.md](../../CONTRIBUTING.md#cubism-version-adaptation):
validate the lowest and highest reviewed patch of each affected minor line.
Offline preflight runs first.
A host PASS requires exact-host identity, fixture hashes, terminal evidence and safe cleanup.
Offline gates and static records do not imply a host PASS.

Source baseline: `9994a3aa7460485d452e8065e442a95703fddad9`
(branch `acceptance/070-host-matrix-audit`).
The worktree was clean before this audit output was added.

## Reviewed release families

Declared support is the 20 reviewed rows in
[reviewed-releases.json](../../compatibility/cubism/reviewed-releases.json).
Every row has `fullRuntime: true`.
That flag does not prove every feature or every UI slice.

| Family | Reviewed patches | Minimum | Maximum | Core profile | Family boundary evidence |
| --- | --- | --- | --- | --- | --- |
| 5.0 | 5.0.00, 5.0.01, 5.0.02, 5.0.03, 5.0.04, 5.0.05, 5.0.06 | 5.0.00 | 5.0.06 | 5.0.0, legacy 62/80 selectors | 5.0 lacks document-UID backup, protected export, export settings, editor commands, top menu, AutoYure, main toolbar and external-app edit. |
| 5.1 | 5.1.00, 5.1.01, 5.1.02, 5.1.03 | 5.1.00 | 5.1.03 | 5.0.0, legacy 62/80 selectors | 5.1 adds document identity and edit infrastructure. Core geometry, selection brush, animation timeline and atlas UI remain absent through 5.1.03. |
| 5.2 | 5.2.00, 5.2.01, 5.2.02, 5.2.03 | 5.2.00 | 5.2.03 | 5.0.256, full 80/80 selectors | 5.2 adds Core geometry, animation, selection and atlas UI. 5.2.00 to 5.2.02 lack five commands added in 5.2.03. |
| 5.3 | 5.3.00, 5.3.01, 5.3.02, 5.3.03, 5.3.04 | 5.3.00 | 5.3.04 | 6.0.257 for 5.3.00 to 5.3.03; 6.0.513 for 5.3.04; full 82/82 selectors | 5.3 adds alpha composition, part masks, part opacity writes and MOC export. 5.3.04 has a separate Core profile and trust root. |

Sources:
[version-boundaries.json](../../compatibility/cubism/version-boundaries.json),
[core-model-read.json](../../compatibility/cubism/verification-src/core-model-read.json) and
[core selector review](../../validation/cubism-version-matrix/README.md).

## Reviewed endpoint identities

The eight matrix endpoints use the minimum and maximum patch of each family.
Artifact identity comes from the reviewed records, not from a directory name.

| Endpoint | Family role | Editor build | Editor size | Editor SHA-256 | Core tuple | Core size | Core SHA-256 |
| --- | --- | ---: | ---: | --- | --- | ---: | --- |
| 5.0.00 | 5.0 minimum | 500000005 | 36091669 | `42ee222a05478f48739bb1fbf7e7bc7350b21004e506e1b555d1d55c584d99cd` | 5.0.0 | 29924 | `9deda4c05a69549e23196bcf7981e9e5325e387c9b7beb468145e21467ac27b0` |
| 5.0.06 | 5.0 maximum | 500060001 | 36677328 | `cbb9d9ecddb9be0252733ece055ecae7d709de5c56647b7226f6c44607e646f2` | 5.0.0 | 30155 | `c7d94635a3049912dd984d7f66420e686ef32bdcc4fe0279149e993041ff7854` |
| 5.1.00 | 5.1 minimum | 501000004 | 38664130 | `cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05` | 5.0.0 | 30153 | `0c67858efd261c2e5600ee8755b358d09deda5c63341a620aa64a6acbcce5de1` |
| 5.1.03 | 5.1 maximum | 501030000 | 38910843 | `a3d916077bd7a176345233da95f1eb0a8bbdf8967fd08162776d5bf6d746009b` | 5.0.0 | 30156 | `1af6619b951a87bb52a80173816179cab91836e696e753fb546d4c14505e7a92` |
| 5.2.00 | 5.2 minimum | 502000003 | 40625430 | `25c41c81ddb3af884b7f0d61a02eba9b09f0412bdc025997c419de7ab7158cf1` | 5.0.256 | 36241 | `9adfb1e98c0e427dc3f99790eba0c8c70c0195c0ac70efb2958c23bc22c96dab` |
| 5.2.03 | 5.2 maximum | 502030002 | 40805584 | `bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd` | 5.0.256 | 36237 | `85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e` |
| 5.3.00 | 5.3 minimum | 503000005 | 41859315 | `c89dcaf17b6bfad7227db30bffc2f29cdf49b8e4b0cec1f12af2d85b1c13e767` | 6.0.257 | 42471 | `98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4` |
| 5.3.04 | 5.3 maximum | 503040001 | 42203164 | `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d` | 6.0.513 | 43708 | `5458a64ebe33b38350de1437399343b0d502f2353a34a97da76d56d118c0d186` |

The 5.3.00 to 5.3.03 Editor artifacts have distinct bytes.
Those four patches share one reviewed Core JAR.
5.3.04 has a distinct Core JAR and native tuple.

## Installed Windows inventory

The audit enumerated `F:/Live2D/Live2D Cubism*` and
`C:/Program Files/Live2D Cubism*`.
Each row compares the installed Editor JAR and Core JAR to
`reviewed-releases.json` and `core-api/observed/*.json`.
A directory name is not identity evidence.

| Installed path | Declared identity | Editor size | Editor SHA-256 | Core size | Core SHA-256 | Launcher state | Verdict |
| --- | --- | ---: | --- | ---: | --- | --- | --- |
| `F:/Live2D/Live2D Cubism 5.0` | 5.0.01 (ReadMe) | 36099677 | `1aa93b1d9d8da88e8c28de06b680c41a73fc70243307d856d4a2967e6e487a0b` | 29924 | `e8b99be96fddfe907e9c83878a6df5e9463a4d147b507f28e2aaaa973e2c090a` | Managed BAT, official backup | Static match: 5.0.01 |
| `F:/Live2D/Live2D Cubism 5.0.05` | 5.0.05 (ReadMe) | 36644752 | `1225893a466d486415773b0a62ec61a75badb2cb5cb2fce8ae744b0123becec4` | 29918 | `b0b855389d388ab3fc79d3e6dbaeba1a13659b57f8f629a8758a9bf007964594` | Managed BAT, official backup | Static match: 5.0.05 |
| `F:/Live2D/Live2D Cubism 5.1` | 5.1.00 (ReadMe) | 38664130 | `cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05` | 30153 | `0c67858efd261c2e5600ee8755b358d09deda5c63341a620aa64a6acbcce5de1` | Managed BAT, official backup | Static match: 5.1.00 |
| `F:/Live2D/Live2D Cubism 5.2` | 5.2.03 (JAR identity; no ReadMe) | 40805584 | `bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd` | 36237 | `85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e` | No live BAT or EXE; stripped copy | Static match: 5.2.03, not launchable |
| `F:/Live2D/Live2D Cubism 5.3` | 5.3.00 (ReadMe) | 41859315 | `c89dcaf17b6bfad7227db30bffc2f29cdf49b8e4b0cec1f12af2d85b1c13e767` | 42471 | `98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4` | Managed BAT, official backup | Static match: 5.3.00 |
| `F:/Live2D/Live2D Cubism 5.3.02` | 5.3.02 (ReadMe) | 41922739 | `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21` | 42471 | `98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4` | Managed BAT, official backup | Static match: 5.3.02 |
| `C:/Program Files/Live2D Cubism 5.1` | 5.1.00 (ReadMe) | 38664130 | `cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05` | 30153 | `0c67858efd261c2e5600ee8755b358d09deda5c63341a620aa64a6acbcce5de1` | Managed BAT, official backup | Static match: 5.1.00 |
| `C:/Program Files/Live2D Cubism 5.2.03` | 5.2.03 (ReadMe) | 40805584 | `bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd` | 36237 | `85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e` | Managed BAT, official backup | Static match: 5.2.03 |
| `C:/Program Files/Live2D Cubism 5.3.04` | 5.3.04 (ReadMe) | 42203164 | `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d` | 43708 | `5458a64ebe33b38350de1437399343b0d502f2353a34a97da76d56d118c0d186` | Managed BAT, official backup | Static match: 5.3.04 |
| `F:/Live2D/Live2D Cubism 4.2` | 4.2.04 (ReadMe) | 28015042 | `22d002239d6175ba040e5b0e48317351a3b7fc5272c1f0776e5873a24c9c2771` | 29814 | `161bc50dd7963668d42976acb93309655cf865c7714fa25e01dc764472390b0a` | Unmanaged 4.x BAT | Unsupported line: 4.2 |
| `F:/Live2D/Live2D Cubism 5.3 alpha` | 5.3.00 alpha1 (ReadMe) | 41025634 | `73de97602bc9f13f981f0e2ea52046dc3b2432f08767f811f27c045eafc17ef6` | 36245 | `68ca508263d1638db9a74a82197a853b3f0a5f3554677dde6d69d1e1cf2a7a2d` | Unmanaged BAT | Reject: not a reviewed release |
| `C:/Program Files/Live2D Cubism 5.4 alpha` | 5.4.00 alpha2 (ReadMe) | 44483377 | `a31aa0751bf474e867a81b5288c13b29b0ab3f1ee00ac17106270c0a96626675` | 43708 | `5458a64ebe33b38350de1437399343b0d502f2353a34a97da76d56d118c0d186` | Unmanaged BAT | Reject: not a reviewed release |

`F:/Live2D/Live2D Cubism 5.2.zip` is an archive.
The audit did not open or use it.

### Directory-name mismatches

The following names do not describe the exact artifact:

- `F:/Live2D/Live2D Cubism 5.0` contains 5.0.01.
- `F:/Live2D/Live2D Cubism 5.2` contains 5.2.03. The reviewed host label is
  `Live2D Cubism 5.2`.
- `F:/Live2D/Live2D Cubism 5.3` contains 5.3.00. The reviewed host label is
  `Live2D Cubism 5.3.00`.
- `F:/Live2D/Live2D Cubism 5.3.02` contains 5.3.02. The reviewed host label is
  `Live2D Cubism 5.3`.
- `F:/Live2D/Live2D Cubism 5.3 alpha` contains 5.3.00 alpha1.
- `C:/Program Files/Live2D Cubism 5.4 alpha` contains 5.4.00 alpha2. Its Core JAR
  has the 5.3.04 bytes. That does not make it a reviewed 5.3.04 Editor.

The reviewed `hostLabel` is the expected folder name in the golden prefix.
Local Windows folder names differ for several patches.
Verify the actual path and JAR hash for each run.
Do not derive the version from the folder name.

### Installed and missing stable patches

Installed stable patches on this Windows host:
5.0.01, 5.0.05, 5.1.00, 5.2.03, 5.3.00, 5.3.02 and 5.3.04.

Missing stable patches:
5.0.00, 5.0.02, 5.0.03, 5.0.04, 5.0.06, 5.1.01, 5.1.02, 5.1.03, 5.2.00,
5.2.01, 5.2.02, 5.3.01 and 5.3.03.

Four matrix endpoints are missing on this host: 5.0.00, 5.0.06, 5.1.03 and 5.2.00.
The audit did not acquire or install them.
This is an audit limit, not a release rule.
The main agent may arrange reviewed official isolated acquisition or user-supplied exact
inputs, and must verify the exact hash before use.

### Launch constraints

Every launch-capable reviewed 5.x stable installation has a Turboism-managed
`CubismEditor5.bat`.
The 4.2, 5.3 alpha and 5.4 alpha installs use unmanaged BAT files.
The managed BAT calls `%LOCALAPPDATA%\Turboism\launch-cubism-turboism.ps1`.
The official bytes remain in `CubismEditor5.bat.turboism-original.bak`.
Do not invoke the official BAT directly into the user installation.
That in-place launch uses the installed Turboism home, not the candidate build.
The reviewed managed launcher may run with an explicit isolated `HomePath`.
Its supported managed-header handling then delegates to the official
`CubismEditor5.bat`.
Do not bypass the required official-BAT path.
Do not edit these installations to restore or replace the BAT.

`F:/Live2D/Live2D Cubism 5.2` has only `.bak` launchers and no Editor EXE.
It is an artifact copy, not a launchable installation.
`F:/Live2D/Live2D Cubism 5.0` also contains a `crack/` directory.
The JAR bytes still match the reviewed record, but the installation is not pristine.

## Representative matrix status

`STATIC MATCH` means the installed Editor and Core hashes equal the reviewed records.
`PRIOR EVIDENCE` means a recorded host run exists, but it is not bound to the 0.70.0 source.
`NOT RUN` means no current-source run for this matrix workload exists.
`BLOCKED` means a required input is missing or unsafe.

| Endpoint | Task code | Windows installation | Static identity | Prior evidence | Current-source smoke | Full behavior |
| --- | --- | --- | --- | --- | --- | --- |
| 5.0.00 | 5000 | MISSING | Reviewed record only | Yes, unbound | NOT RUN | NOT RUN |
| 5.0.06 | 5006 | MISSING | Reviewed record only | Yes, unbound | NOT RUN | NOT RUN |
| 5.1.00 | 5100 | `F:` and `C:` copies | STATIC MATCH | Yes, unbound | NOT RUN | NOT RUN |
| 5.1.03 | 5103 | MISSING | Reviewed record only | Yes, unbound | NOT RUN | NOT RUN |
| 5.2.00 | 5200 | MISSING | Reviewed record only | Yes, unbound | NOT RUN | NOT RUN |
| 5.2.03 | 5203 | `C:` copy is launchable; `F:` is stripped | STATIC MATCH | Yes, unbound | NOT RUN | NOT RUN |
| 5.3.00 | 5300 | `F:` copy | STATIC MATCH | Yes, unbound | NOT RUN | NOT RUN |
| 5.3.04 | 5304 | `C:` copy | STATIC MATCH | Yes, unbound | NOT RUN | NOT RUN |

The main agent reports native 5.3.04 sessions.
They are not this workload and do not satisfy Stage 1 to Stage 3.

Endpoint readiness on this host:

- Installation present: 5.1.00, 5.2.03, 5.3.00 and 5.3.04.
  Use a reviewed isolated launch path.
  Do not invoke the official BAT directly into the user installation.
  The reviewed managed launcher may run with an explicit isolated `HomePath`.
- BLOCKED by a missing installation: 5.0.00, 5.0.06, 5.1.03 and 5.2.00.

The campaign record claims a 20-version Linux Proton golden prefix.
This audit did not enumerate that prefix or verify its JAR hashes.
The main agent must verify that inventory before scheduling those four endpoints there.
An unverified claim is not a substitute installation.

## Minimal meaningful workload

One endpoint passes this matrix only when all four stages complete for the candidate source.
Do not replace a missing stage with evidence from another version or another feature.

### Stage 0: offline preflight

On this native Windows host, use native Gradle commands without X11 or xvfb:

```bat
.\gradlew.bat --console=plain :runtime:admissionPreflightTest
.\gradlew.bat --console=plain checkReviewedReleases
python scripts\materialize_verification_records.py
```

On a Linux host, use the X11 wrapper:

```sh
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew :runtime:admissionPreflightTest
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew checkReviewedReleases
python3 scripts/materialize_verification_records.py
```

For each endpoint, verify the exact Editor and Core selector records.
Native Windows commands:

```bat
.\gradlew.bat verifyStaticHostSelectors ^
  -PturboismStaticVerificationRecord=build/generated/verification/cubism-<VERSION>-editor-model.json ^
  -PturboismHostArtifact=<EDITOR_JAR>

.\gradlew.bat verifyStaticHostSelectors ^
  -PturboismStaticVerificationRecord=build/generated/verification/cubism-<VERSION>-core-model-read.json ^
  -PturboismHostArtifact=<CORE_JAR>
```

Linux X11 commands:

```sh
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew verifyStaticHostSelectors \
  -PturboismStaticVerificationRecord=build/generated/verification/cubism-<VERSION>-editor-model.json \
  -PturboismHostArtifact=<EDITOR_JAR>

env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew verifyStaticHostSelectors \
  -PturboismStaticVerificationRecord=build/generated/verification/cubism-<VERSION>-core-model-read.json \
  -PturboismHostArtifact=<CORE_JAR>
```

Exit 0 requires exact artifact identity and every selector match.
These gates write build output.
They are not host acceptance.

### Stage 1: exact-host acquisition

Task: `core-acquisition:<TASK_CODE>`.

The queue selects the startup-assistance variant by default.
Keep that selection and record it.
The probe must report all of these records:

- `status=PASS`.
- `editorHashMatch=true` and `coreHashMatch=true`.
- `sameDefiningClassLoader=true`.
- `nativeCoreVersion` equal to the endpoint tuple.
- `stableSnapshots=3` and `modelInstancesCount` at least 1.
- `bridgeStatus=NO_PUBLIC_BRIDGE` is acceptable here.
  It proves acquisition and observation only.
- Supervisor `identityVerified`, `fixtureUnchanged`, `normalExit` and
  `cleanup=safe`.

A timeout, an unhandled dialog or a missing stable model is `FAIL` or `BLOCKED`.
It is not evidence that the version lacks support.

### Stage 2: native Core behavior

Task: `core-owned-moc:<TASK_CODE>`.

The run must use the production SDK and the pinned MOC fixture.
Assertions:

- `status=PASS` and supervisor identity, fixture, exit and cleanup checks.
- `nativeCoreVersion` equal to the endpoint tuple.
- Independent model instances and immutable copies.
- Explicit child-to-parent close, parent cascade close and use-after-close rejection.
- 5.0 and 5.1: `allStructuralReadMethods=PARTIAL_EXPECTED`.
  Read Canvas, Parameters and Parts.
  Reject complete Drawable, Glue and Deformer families with a typed failure.
  Counts are 42 parameters and 19 parts for the pinned fixture.
- 5.2: full structure read.
  Counts are 42 parameters, 19 parts, 84 drawables, 97 deformers and 0 Glues.
- 5.3.00 to 5.3.04: full structure read with the same counts.
  The reviewed Core boundary grants parameter repeat from 5.3.00.
  The prior 5.3.04 job did not publish that key; assert it on the current run.
  The prior 5.3.00 run reported `latestMocVersion=V5_3`; assert the observed
  value for each endpoint.
- The pinned fixture is Haru MOC V3_0 with SHA-256
  `aa59f7a3dc3b30b23b5f10afd9192ed755e4d01dc9a12442d99b972a418e2543`.

The zero-Glue count belongs to the fixture.
It does not prove Glue behavior.

### Stage 3: native UI binding

Task: `status-bar:<TASK_CODE>`.

The probe must report `status=PASS` for:

- The visible manager status and compact status.
- Severity rendering and removal of warning and error statuses.
- The canvas hint path.
- Status removal on disable and shutdown.

The supervisor must also verify identity, unchanged fixture, normal exit and safe cleanup.
This stage proves the public SDK status-bar path.
It does not prove work-time totals, complete production plugin composition or
every UI surface.
If a 5.0 or 5.1 endpoint records a typed capability absence, keep it `BLOCKED`.
Do not substitute another UI task to produce a PASS.

### Fixture constraints

- Bind each endpoint to its own fixture through
  `TURBOISM_HOST_VALIDATION_FIXTURE_<TASK_CODE>` and the matching `_SHA256` value.
  The wrapper falls back to the 5.3.03 fixture.
  Do not accept that fallback for a 5.0, 5.1 or 5.2 endpoint.
  A newer-saved project can show the newer-model warning and block stable identity.
- Use a project saved by the endpoint version or an older version.
  The project must open without a conversion that changes the fixture.
- Use one model with a stable document identity and no unsaved change.
- Use no PRO-only feature.
  FREE startup assistance is allowed and must be recorded.
  Never select trial, purchase or activation options.
- For Stage 2, use the pinned `.moc3` fixture.
  The packaging must declare `validation/moc/` in the plugin descriptor.
  An undeclared resource is a probe failure, not a Core verdict.
- Keep the original fixture, the golden prefix and official installations unchanged.
  Each job uses a task-owned copy.

### Explicit non-goals

This matrix does not cover these feature acceptance items:

- Batch deformer rename, parameter transfer and self-inversion.
- Framework and plugin update, recovery and migration.
- Marketplace truncated text and localized links.
- Recent-project-board layouts and the empty state.
- GIF recording and the deferred input investigation.
- Work-time totals across sessions.
- Performance, FPS, memory or Mesa optimization claims.

Those items remain `NOT RUN` and require their own exact-host evidence.

## Evidence classification

### Static identity matches

The four installed endpoints 5.1.00, 5.2.03, 5.3.00 and 5.3.04 have exact Editor
and Core hash matches.
The other installed patches (5.0.01, 5.0.05 and 5.3.02) also match.
These are file-identity facts only.
They do not prove startup, admission, behavior or cleanup.

### Prior evidence

`validation/cubism-version-matrix/campaign-jobs.json` records older host jobs.
The tracked record has no source revision, so the jobs are not bound to
`9994a3aa7`.
They are prior evidence, not current-source acceptance.

| Endpoint | Prior acquisition job | Prior owned-MOC job | Other prior job |
| --- | --- | --- | --- |
| 5.0.00 | `ec170db7` (`allversions-simple-r1`) | `2501a359` (`allversions-owned-r1`) | None |
| 5.0.06 | `83d0daad` (`allversions-r1`) | `3b47b96e` (`allversions-owned-r2`) | None |
| 5.1.00 | `328953a2` (`allversions-r1`) | `d0a21c17` (`allversions-owned-r1`) | None |
| 5.1.03 | `c7a96026` (`allversions-r1`) | `0165fbd2` (`allversions-owned-r6`) | MCP `68327e5a`, `9ee1e5eb` FAIL |
| 5.2.00 | `d3190394` (`allversions-r1`) | `f5578093` (`allversions-owned-r1`) | MCP `60665b58` PASS (`allversions-free-r2`) |
| 5.2.03 | `d509a5a5` (`allversions-r1`) | `a7fd2516` (`allversions-owned-r1`) | MCP `26b44a08` PASS (`allversions-free-r1`) |
| 5.3.00 | `0cda0dcf` (`allversions-r1`) | `6ae62c47` (`allversions-owned-r1`) | MCP `547fce40` PASS (`allversions-free-r1`) |
| 5.3.04 | No tracked job | `6b80a640` (`allversions-owned-r5`) | MCP `eb9c1f5f` and `bfa2cc04` PASS; workspace `f05ef34e` PASS |

Each value is a unique `jobId` prefix in `campaign-jobs.json`.
The prior 5.3.04 acquisition PASS exists only as prose in the matrix README
(`t17-r3`).
Its job record is not tracked.

The matrix README also cites `2026-09-30-host-results.json`,
`2026-10-01-host-results.json` and `2026-10-01-core-selector-exploration.json`.
Those files are not present at this revision.
The prose summary remains; the raw result files do not.
Do not treat the prose as a reproducible current result.

Prior evidence has these limits:

- 5.0.00 acquisition failed before the final PASS.
  The earlier failures include a timeout and a newer-model dialog.
- 5.1.03 owned-MOC failed in r4 and r5 before r6.
- 5.3.04 owned-MOC failed in r2 to r4 before r5.
- 5.3.04 MCP failed in r1 to r5 before r6.
- The workspace workload uses a validation-only provider.
  It does not prove production workspace composition.
- The MCP free-fixture runs modify a task copy and restore it.
  They do not prove ordinary PRO saving.

### Current-source native smoke

The audit performed no runs.
The main agent reports native 5.3.04 sessions.
Those sessions are not Stage 1 to Stage 3 Core acceptance.
They do not bind this matrix workload, the candidate JAR hash or the required assertions.
Record them as 5.3.04 activity, not as matrix acceptance.
Stage 1 to Stage 3 remain `NOT RUN` for all eight endpoints.
A current-source smoke must bind the candidate agent hash, probe hash, prepared digest,
source revision, exact host JAR hash, fixture hash and terminal supervisor result.
Do not infer it from prior jobs, static records, offline tests or unqualified sessions.

### Full behavior acceptance

Full behavior acceptance is outside this matrix.
The representative matrix proves identity, Core behavior and one UI binding path.
It does not prove the release feature list.
Keep the two ledgers separate.

## Blockers

1. Four endpoints are missing on native Windows: 5.0.00, 5.0.06, 5.1.03 and 5.2.00.
   The audit did not acquire or install them.
   The main agent may arrange reviewed official isolated acquisition or user-supplied
   exact inputs.
   Verify the exact hash before scheduling.
   Otherwise keep the endpoint `BLOCKED`.
2. The Linux Proton golden prefix inventory is unverified in this audit.
   The campaign claims 20 installs.
   The main agent must hash-check each endpoint before scheduling.
3. In-place invocation of the managed Windows BAT uses the user installation.
   `F:/Live2D/Live2D Cubism 5.2` is stripped and not launchable.
   Use the reviewed managed launcher with an explicit isolated `HomePath`, or the
   Linux Proton queue.
   Do not bypass the official-BAT path.
4. The audit performed no host runs.
   The main agent reports native 5.3.04 sessions, but they are not Stage 1 to Stage 3
   Core acceptance.
   All tracked prior jobs lack a source revision binding.
5. The raw result files cited by the matrix README are absent.
   The evidence chain is incomplete.
6. The 5.0 and 5.1 Core profile is partial.
   Expect `PARTIAL_EXPECTED` and typed rejection of geometry.
   Do not report full Core support or a failure for the missing families.
7. The Windows installs are not pristine.
   They contain managed BAT files, official backups and a `crack/` directory in one copy.
   Do not edit them during acceptance.
8. The 5.3 alpha, 5.4 alpha and 4.2 installs are unreviewed or unsupported.
   Do not use them for any matrix row.
   5.4 alpha shares Core bytes with 5.3.04; it is still not a 5.3.04 Editor.
9. FREE license dialogs require the startup-assistance variant.
   Record the assistance.
   Do not choose trial, purchase or activation.
10. The status-bar capability has a known withdrawal limitation.
    Normal binding can run.
    Seam-failure withdrawal is not proven.

## Reproduction commands

### Commands executed for this audit

The audit ran read-only commands in Git Bash from the worktree root.

```sh
sha256sum "F:/Live2D/Live2D Cubism 5.3/app/lib/Live2D_Cubism.jar" \
  "F:/Live2D/Live2D Cubism 5.3/app/lib/Live2DCubismCore.jar"
stat -c '%s %n' "F:/Live2D/Live2D Cubism 5.3/app/lib/Live2D_Cubism.jar"
python3 scripts/preview/reviewed_host_artifacts.py sha256 5.3.00
```

The comparison script read `reviewed-releases.json`,
`core-api/observed/*.json` and every installed `app/lib` JAR.
It printed the Editor and Core hash for each directory and the matching reviewed row.
The installed inventory table records the output.
The raw output is retained at
[representative-host-inventory.txt](representative-host-inventory.txt).
No host process started.

### Commands for the main agent

The queue requires Linux.
No command below ran in this audit.

```sh
python3 scripts/preview/host_validation.py list
python3 scripts/preview/host_validation.py plan \
  core-acquisition:5100 core-owned-moc:5100 status-bar:5100
python3 scripts/preview/host_validation.py prepare core-acquisition:5100 \
  --run-label 070-matrix --json
python3 scripts/preview/host_validation.py submit --prepared <PREPARED_ID> \
  --request-id 070-matrix-5100-acquisition --json
python3 scripts/preview/host_validation.py status <JOB_ID> --json
python3 scripts/preview/host_validation.py wait <JOB_ID>
```

Repeat for each installation-present endpoint and each stage.
Use `TURBOISM_ENV_FILE` for the private fixture and golden-prefix paths.
The task manifest is [host-validation-tasks.json](../../scripts/preview/host-validation-tasks.json).
The queue rules are in
[README-host-validation-scheduling.md](../../scripts/preview/README-host-validation-scheduling.md).

## Evidence references

- [representative-host-inventory.txt](representative-host-inventory.txt): retained raw inventory output.
- [CONTRIBUTING.md](../../CONTRIBUTING.md#cubism-version-adaptation): representative matrix rule.
- [reviewed-releases.json](../../compatibility/cubism/reviewed-releases.json): 20 Editor rows.
- [core-api observed records](../../compatibility/cubism/core-api/observed/): exact Core JARs.
- [core-model-read.json](../../compatibility/cubism/verification-src/core-model-read.json): Core profiles and selectors.
- [version-boundaries.json](../../compatibility/cubism/version-boundaries.json): family boundaries.
- [validation/cubism-version-matrix/README.md](../../validation/cubism-version-matrix/README.md): prior host summary and limits.
- [campaign-jobs.json](../../validation/cubism-version-matrix/campaign-jobs.json): prior job identifiers.
- [campaign.md](../../validation/cubism-version-matrix/campaign.md): 20-install claim and directory aliases.
- [host-validation-tasks.json](../../scripts/preview/host-validation-tasks.json): task codes and variants.
- [host-validation-env.sh](../../scripts/preview/host-validation-env.sh): Core tuples and fixture selection.
- [release-preparation-pr.md](release-preparation-pr.md): outstanding matrix item.
- [release-note-review.md](release-note-review.md): declared 5.0.00 to 5.3.04 support.

## Return summary

Matrix state: specification complete; audit host runs: none.
Main-agent native 5.3.04 sessions exist, but they are not Stage 1 to Stage 3 acceptance.
Installation-present endpoints on this Windows host: 5.1.00, 5.2.03, 5.3.00, 5.3.04.
Missing endpoints on this Windows host: 5.0.00, 5.0.06, 5.1.03, 5.2.00.
No PASS is claimed.
