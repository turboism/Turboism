# Self-Update API Production Deployment Acceptance (2026-11-01)

After the user approved production deployment, the new release API was deployed to `api.turboism.dev`. This record covers the first deployment, which kept release disabled; the later approved production key and public key configuration is in [signing configuration acceptance](signing-provisioning-validation.md). Client self-update stays disabled, and the product code stays on `test/issue-50-windows-validation-fixes`.

## Deployment Identity

| Item | Value executed |
| --- | --- |
| Deployment repository commit | [`0579cfd2cc5a78a23e1a4509adb461fa73becb48`](https://github.com/turboism/turboism-learn/commit/0579cfd2cc5a78a23e1a4509adb461fa73becb48) |
| Deployed product source | `743a6b3882c74f0e7273b09006b932e766153d6a` |
| Actual deployment workflow | [41268862380](https://github.com/turboism/turboism-learn/actions/runs/41268862380), `workflow_dispatch`, succeeded |
| Cloudflare Worker version | `e8d9a081-2c89-46f9-acfb-d98eb384dd30` |
| Deployment completed | 2026-11-01 03:22 UTC (11:22 Beijing time) |
| Full deployment acceptance completed | 2026-11-01 03:25 UTC (11:25 Beijing time) |

What was actually applied is [deploy-release-api-disabled.patch](deploy-release-api-disabled.patch): one workflow file, +9/-3. Another workflow in the deployment repository deploys the tutorial site on any push to main, so this commit used `[skip ci]` to suppress that automatic push trigger and then ran the existing API deployment workflow explicitly. The API source tests, configuration assertions, domain checks, and full mirror verification really ran; the commit produced exactly one manual API run.

## Passed Checks

- A real GitHub runner ran the server tests against the pinned source: 78 passed, 0 failed.
- `api.turboism.dev` is still bound to the separate `turboism-release-api` Worker.
- Full download of the six Stable, Beta, and Nightly EXE and ZIP assets, the SHA-256 values, the checksum files, Range 206, and the per-file download counters all passed. The acceptance downloads used the existing statistics exclusion marker.
- The version, build, source, and asset hashes of the three channels matched before and after deployment; none reported `release.incremental`.
- An unknown hash on the new document and payload path answered `404 FRAMEWORK_RESOURCE_UNKNOWN` for GET, a 404 with no body for HEAD, and normal CORS.
- A credential-free empty request to `/v1/framework/admit` and `/v1/framework/withdraw` answered `503 FRAMEWORK_DISABLED`; release authority stayed off.
- The existing [real OIDC sync workflow 41269118606](https://github.com/turboism/Turboism/actions/runs/41269118606) succeeded with a real GitHub identity; no CI number or identity was forged.

## Release Data Freshness

Before deployment `/health` already reported `degraded` with a snapshot time of `2026-11-01T02:30:33.874Z`; the three channels still reported `ready`. The same state was observed after deployment and after the OIDC sync, which shows that a passed sync authentication does not mean the upstream refresh succeeded.

The service then resumed refreshing through its existing mechanism and the snapshot moved to `2026-11-01T03:37:40.254Z`. Both health reads inside the diagnostic window and the final local re-check reported `ready`, and the three channel versions and asset hashes still matched the pre-deployment values. This round changed no refresh code and added no upstream credential; the specific error before the anomaly cleared was not captured, so the cause is not attributed to a proven rate limit or network failure.

The final [read-only diagnostic 41270435338](https://github.com/turboism/turboism-learn/actions/runs/41270435338) succeeded: a 100-second window captured 4 complete records with no matching refresh error, and both health responses reported `ready`. The diagnostic exists only on the deployment repository branch `ops/issue-50-release-api-refresh-diagnostic` (`93dee8146e1df14d6ec60856ea934228df75878f`) and performs no deployment action; deployment repository main was `0579cfd` at that time. The first diagnostic ended early because the tool mishandled the expected timeout 124; its shell exit-code handling was fixed and the run repeated. That failure is not a production deployment failure.

## Follow-Up Release Preparation

A read-only check after the first deployment found that the product repository had no framework update variables yet and that the `framework-release-signing` environment did not exist. The later approved signing environment, key, and public key configuration is recorded separately; the `production-release` deployment environment configuration is still pending.

The production signing key and public key configuration is approved; release authority and client activation stay off. The candidate build and signing flow require product `main`, and the product "do not merge into main" requirement still holds; the first real CI full installation and the A-to-B-to-C upgrade need the source release arrangement decided first.

Local detailed evidence is kept in the ignored `build/agent-tasks/` directory: `deployment-before.json`, `deployment-run-41268862380.log`, `deployment-run-41268862380/api-production.json`, `refresh-diagnostic-41270435338.log`, and `refresh-diagnostic-41270435338/summary.json`. `deployment-after.json` is updated by the re-check script to the most recent result; the pinned re-check copy of this public key deployment is in [signing configuration acceptance](signing-provisioning-validation.md).
