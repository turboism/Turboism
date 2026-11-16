# Self-Update Release Wiring Validation (2026-11-01)

The working baseline was `743a6b3882c74f0e7273b09006b932e766153d6a` on local branch `local/w2-validation-fixes`, with the remote target still `test/issue-50-windows-validation-fixes`. This round again used the existing Paseo DeepSeek V4.1 Flash agent to write code, and the main assistant reviewed and verified it.

## Completed Verification

| Check | Result |
| --- | --- |
| Server `node --test distribution/test/*.test.mjs` | 78 passed |
| Existing release tooling, orchestrator, promotion, and prerelease regression (local WSL Ubuntu) | 106 passed |
| Client resource generation `scripts.test.test_framework_client` | 17 passed, related Python lint passed |
| Release wiring and packaging regression `scripts.test.test_framework_release` | 19 passed; 142 passed in total together with the existing regression and the client resource test |
| Real `:bootstrap:jar` with a temporary test public key | Build and licence checks passed; the JAR holds both generated resources |
| Resource directory using a repository-root-relative path | `:bootstrap:processResources` passed |
| Rebuild of `:bootstrap:jar` with the generated configuration off | Passed; neither the generated directory nor the final JAR held an active resource |
| Real NSIS 3.09 compile (isolated signing fixture) | Final script compiled; produced the receipted EXE/ZIP plus all eight assets; the ZIP signature, 27 framework files, and exclusion checks passed |
| `checkFrameworkUpdateTooling --dry-run` | Gradle configuration resolved; this only proves task configuration and does not count as a test run |
| actionlint 1.7.7 (optional shellcheck and pyflakes not run) | The three changed workflows passed |
| CI coverage, related Python Ruff, `git diff --check` | Passed |
| Server deployment proposal patch | Apply and reverse apply passed; the assertions passed against the real Worker configuration of this workspace |

The real JAR verification used a temporary `local-build-probe` public key. The package reported `buildKind=local`, `dirty=true`, and an empty `buildNumber`, so no real CI identity was forged. That JAR was never installed into Cubism, and the build was restored afterwards to a local build with no active resource.

The NSIS experiment used an isolated temporary directory, a temporary signing key, and an explicitly marked synthetic test identity, and it verified only the compile and the packaged bytes. No test EXE was installed, and no CI number was requested or online admission called.

The final review fixed a reference from the signing workflow to an external public key file that was never generated. A new regression extracts the real `sign` and `verify` commands from the workflow steps and runs signing, packaging, and signature verification in an isolated fixture without that external file; the public key must still be read from the actual bundled agent JAR and verified. The final 142 regressions include this case.

Running the existing 106 release regressions directly on local Windows produced 3 symbolic-link permission errors and promotion failures caused by newline differences; the same suite passed all 106 on Ubuntu. That Windows run must not be recorded as a pass. This round did not re-run the old full runtime suite and does not claim that the existing formatting problem is solved.

## Live Service and Deployment Proposal

Read-only checks of the live service at the release wiring stage: `/health` reported ready; Stable was `0.67.4`, Build 56, with no incremental field. At that time the new resource path returned the old implementation's `NOT_FOUND`.

After the required changes were organised into the [service deployment proposal](service-deployment-proposal.md) and a minimal patch, the user approved production deployment, the new API was deployed, and full download acceptance passed. See [production deployment acceptance](production-service-validation.md) for the later measured state. The production signing key and public key were then approved and configured, the server public key deployment passed acceptance, and both signing and the self-update switch stay off; see [signing configuration acceptance](signing-provisioning-validation.md).

## Evidence Location and Scope

Detailed logs live in the Git-ignored `build/agent-tasks/`: `client-enabled-build.log`, `client-enabled-verified.json`, `client-enabled-identity.properties`, `client-relative-path-build.log`, `client-disabled-build.log`, `client-disabled-verified.json`, `framework-real-nsis.log`, `framework-real-nsis.json`, `final-release-tests.log`, `framework-gate-config.log`.

A real-numbered CI release, two consecutive framework upgrades after installing the official complete package, and on-site failure recovery still need acceptance. The existing candidate build, signing, and release admission all require product `main`; this round committed only the test branch as required and changed no such rule. An official release needs a separate decision on the product source arrangement, because the server deployment itself does not require merging the product test branch into `main`.
