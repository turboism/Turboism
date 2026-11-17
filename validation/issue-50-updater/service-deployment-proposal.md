# Self-Update Service Deployment Proposal

The user approved this patch, and production deployment with a full download acceptance completed on 2026-11-01. See [production-service-validation.md](production-service-validation.md) for the execution record. The later approved production key and public key configuration is in [signing-provisioning-validation.md](signing-provisioning-validation.md), and self-update stays disabled. The text below keeps the pre-deployment findings and the executed patch scope.

## Verified State

Read-only checks on 2026-11-01:

- The live `/health` reported `ready`; Stable was `0.67.4`, Build 56, with no `release.incremental`.
- `/v1/framework/documents/<64 zeros>` answered `404 NOT_FOUND`; the new implementation answers `FRAMEWORK_RESOURCE_UNKNOWN` for an unknown resource.
- `.github/workflows/deploy-release-api.yml` in `turboism/turboism-learn` pinned product source `03491cca190522c3c9f85f446f1ffd36e146ffec` and allowed only the older `EDGE_DOWNLOADS_ENABLED` configuration before deployment.
- `743a6b3882c74f0e7273b09006b932e766153d6a` on the product test branch already carried the admission, withdrawal, resource download, and version discovery endpoints. This round ran `node --test distribution/test/*.test.mjs` locally, and 78 cases passed.

## Reviewable Change

[deploy-release-api-disabled.patch](deploy-release-api-disabled.patch) changes one workflow file in the external deployment repository:

1. It updates the pinned source version to `743a6b3882c74f0e7273b09006b932e766153d6a`.
2. It updates the configuration assertions to accept the three framework release settings that default to off in the current source.
3. It checks the `REGISTRY/ReleaseRegistry` and `FRAMEWORK/FrameworkAuthority` bindings and the `v1/v2` SQLite Durable Object migration.

The existing domain checks, Cloudflare credential references, service tests, and full mirror verification stay. The patch contains no key.

| Setting | Value this deployment intends |
| --- | --- |
| `EDGE_DOWNLOADS_ENABLED` | `true` (existing value) |
| `FRAMEWORK_PUBLISHER_WORKFLOW` | empty string |
| `FRAMEWORK_PUBLISHER_ENVIRONMENT` | empty string |
| `FRAMEWORK_TRUSTED_KEYS` | `{}` |

These values keep the new release authority off. After the patch passed local apply, reverse apply, and content checks, the user explicitly approved production deployment. The deployment repository commit is `0579cfd2cc5a78a23e1a4509adb461fa73becb48`, and the manual API deployment workflow `41268862380` succeeded; the acceptance record holds the trigger and the evidence.

## Follow-Up Connection Order

1. Done: apply this patch, deploy the endpoint implementation, and check the existing API plus the new resource path.
2. Separately confirm the production signature, public key, and release workflow configuration, and keep the client-embedded public key equal to the server verification key.
3. Produce a complete package with an initial signature receipt through a real-numbered CI build and the protected release flow.
4. On Windows Cubism, verify a full installation followed by two consecutive upgrades, the background download, and the restart-and-apply step, which completes the real-version upgrade evidence.

Step 1 may deploy the product test commit pinned in the patch without merging into product `main`. The existing candidate build, signing, and release admission of step 3 accept product `main` only; the current "do not merge into main" requirement still holds, so the source arrangement for an official CI release needs separate confirmation. This round relaxed no admission rule for the test branch.

The product source stays on `test/issue-50-windows-validation-fixes`, and this proposal performs no product branch merge. See [BOOTSTRAP_RECEIPT.md](../../packaging/windows-installer/BOOTSTRAP_RECEIPT.md) for the release wiring and configuration, and [INCREMENTAL.md](../../distribution/INCREMENTAL.md#production-service-apiturboismdev) for the service contract.
