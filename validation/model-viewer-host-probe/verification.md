# Character viewer verification

The production viewer passed on Cubism Editor 5.3.04 under Proton on 2026-10-15.
This acceptance covers a plugin-owned viewer window. It does not cover native
ArtMesh conversion, skeleton controls or editable morph parameters.

## Exact-host acceptance

Queue job: `ea67f8e7-c78f-4982-b999-cb26a0f511fd`.
Run: `queue-99f8cccece4940c49ff08c730ed0fc3d`.
Official host JAR SHA-256:
`c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d`.

The probe invoked the installed production action through `ActionCatalogService`.
It opened the real SDK file chooser and selected the task-local GLB copy.
The production controller read the granted file through `readBytesBounded`.
The source contained 82,512,460 bytes. Its SHA-256 was
`1e16cdf61b75b4bb4d336d1a37f66fce03f33c694b42d9c710b2f4200a18bd45`.
The source file retained this hash after the session.

| Assertion | Observed result |
| --- | --- |
| Initial production frame | Visible, nonblank |
| Initial frame hash | -3299470738161565262 |
| Mouse drag frame hash | 381203938168194484 |
| Mouse wheel frame hash | 4150181243317249559 |
| Reset frame hash | -3299470738161565262 |
| Geometry inspection frame hash | 1412787708634184451 |
| Window close | Window closed; renderer worker terminated |
| Host lifecycle | succeeded; normal exit; runner and wrapper exit 0 |
| Supervisor cleanup | safe; no cgroup kill; task prefix removed |
| Host identity and CMO3 fixture | Verified and unchanged |

An earlier candidate also passed in job
`a780cdf2-6cd0-412e-839e-f0a2ed921ea8`. The final candidate reproduced all five
frame hashes after the fatal-error guard correction.

The private queue evidence is keyed by the final job ID above. Its final
`evidence/lifecycle-result.json` records `validationStatus=PASS`,
`identityVerified=true`, `fixtureUnchanged=true` and `normalExit=true`.
Raw logs, model images and machine-specific lifecycle files remain local.

The tested production plugin SHA-256 was
`294e8ee8f04362a53f28509743af3f3ca0f4d6d7af6267dedf928e0030bd0f94`.
The tested runtime Agent SHA-256 was
`5916060ce4fa34b709bcfcb8df94578c8a43402157df579cb85ed260f284f75e`.
The test-only probe SHA-256 was
`37fed396b76164cd525fdfc3c95bcbd315c33bfdd1c14b29ff4e1fde0bb1bfd5`.

## Automated checks

With `TURBOISM_VIEWER_FIXTURE` set to the reference GLB, the plugin suite reported
219 tests: 193 passed, 26 skipped, zero failures and zero errors. Skips concern
separate native-import asset fixtures. The real viewer fixture test ran.
Runtime user-file tests reported 37 tests: 34 passed, three skipped, zero failures
and zero errors. All integration i18n catalog tests passed.

The offline check used the production plugin JAR. It decoded 16 meshes,
26 primitives, 121,097 triangles, 15 materials, 10 images, two skins and
144 morph targets. Decode took 2,958 ms in this run. Three rotation frames differed.

The final headless `devCheck` and `:runtime:admissionPreflightTest` passed.
The runtime admission suite reported 3,704 tests, 55 skips and zero failures.
All four exact-version admission preflight tests passed. All 13 native atlas
append regression tests passed after the fatal-error guard correction.
A preceding desktop-mode admission run timed out in an existing screenshot
capture test. The headless rerun used that test's declared skip condition.

## Reproduction

Build the production plugin and runtime Agent through Gradle. Run `build.sh`
with the SDK JAR, production plugin JAR, runtime fat JAR and local output directory.
Run `run-host-validation.sh --help` for the generic Runner options. Prepare the
exact host, isolated bundle, copied GLB and unchanged CMO3 fixture before submission.
Pass `-Dturboism.viewer.fixture={HOME}\\viewer-fixture.glb` and opt in to
`-Dturboism.viewer.exitAfterProbe=true` for the queued test session.
Use the official queue and require the final supervisor lifecycle verdict.

## Appearance and scope

Source-material mode preserves exported GLB material assignments. This source
contains black base-color textures and a packed color texture assigned to hair.
Geometry inspection shows the full geometry with neutral lighting and source alpha.
It does not reconstruct the original engine shader. Both modes include all scene
primitives, including the detached plane present in the source.

Interactive viewer input supports embedded GLB 2.0 with PNG/JPEG textures up to
128 MiB. Native import retains its separate format and authoring restrictions.
The viewer does not write the Cubism document, so document Undo/Redo and native
save/reopen checks do not apply to this acceptance.
