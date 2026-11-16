# Production Signing Key Configuration Acceptance (2026-11-01)

The user approved creating a main-only signing environment, generating and storing the production private key, and configuring the matching public key, while requiring self-update to stay off. The GitHub configuration and the production deployment of the server public key are both complete, and release authority and client self-update stay off.

## Key and GitHub Configuration

| Item | Verified result |
| --- | --- |
| Key ID | `framework-official-v1` |
| Algorithm and format | Ed25519; the public key is a 44-byte X.509 SPKI; the private key is a base64-encoded PKCS#8 PEM |
| Public key SPKI SHA-256 | `0d455181367e79798bd4559dd757f8e788e72ff5bffae6fbff98ae4c1862ae18` |
| Private key location | the `FRAMEWORK_UPDATE_SIGNING_KEY_B64` secret of the `framework-release-signing` environment in the product repository |
| Secret update time (GitHub) | `2026-11-01T04:05:49Z` |
| Signing environment branch rule | custom branch policy; exactly one rule, `name=main, type=branch` |
| Environment variable | `FRAMEWORK_UPDATE_SIGNING_KEY_ID=framework-official-v1` |
| Repository public key variable | `FRAMEWORK_UPDATE_TRUSTED_KEYS`; schemaVersion 1, the single public key above, revoked empty |
| Repository signing and client switch | `FRAMEWORK_UPDATE_SIGNING_ENABLED=false` |

The private key was generated in local memory and stored into the environment secret only through the standard input of the GitHub CLI. It was never written to a local private key file, a command argument, an environment variable, or a log. The three JSON files kept locally hold only the public key, the fingerprint, and the operation status.

A challenge with a separate configuration-purpose prefix and a random nonce completed the local signing, signature verification, and tamper rejection checks; no release manifest was signed and no CI identity was constructed. After the secret write succeeded, the public variables, the environment rules, and the secret name and update time were read again. GitHub does not return secret plaintext, so this record does not claim that the remote private key plaintext was verified or that a production signing CI has run.

## Server Configuration

The public configuration commit is [`a0c84429baba6ee197aadfb3bdac80d9fe0e261a`](https://github.com/turboism/Turboism/commit/a0c84429baba6ee197aadfb3bdac80d9fe0e261a), which changes only `FRAMEWORK_TRUSTED_KEYS` in `distribution/wrangler.json` and matches the active public key in the client repository variable exactly. The 78 local server tests passed.

`FRAMEWORK_PUBLISHER_WORKFLOW` and `FRAMEWORK_PUBLISHER_ENVIRONMENT` both stay empty. Registering the public key grants no release authority by itself.

## Production Deployment and Acceptance

| Item | Value executed |
| --- | --- |
| Deployment repository commit | [`33ccb35c630ade51d24bad38a805a9d8f9114f2d`](https://github.com/turboism/turboism-learn/commit/33ccb35c630ade51d24bad38a805a9d8f9114f2d) |
| Pinned product source | `a0c84429baba6ee197aadfb3bdac80d9fe0e261a` |
| Actual deployment workflow | [41272321923](https://github.com/turboism/turboism-learn/actions/runs/41272321923), `workflow_dispatch`, succeeded |
| Cloudflare Worker version | `64aa72c0-878a-4570-9f94-258a52160410` |
| Deployment completed | 2026-11-01 04:15 UTC (12:15 Beijing time) |
| Full deployment acceptance completed | 2026-11-01 04:18 UTC (12:18 Beijing time) |

The deployment workflow only updates the pinned product source and the public key configuration assertion; the release identity settings stay empty, and the domain, storage bindings, migrations, and the existing full acceptance all stay. The commit used `[skip ci]` to avoid an unrelated tutorial site deployment and then ran the full API deployment workflow above explicitly.

Verified results:

- A real GitHub runner ran the 78 server tests against the pinned source and all passed; the deployment configuration assertion confirmed the public key and the disabled release authority.
- `api.turboism.dev` is still bound to the separate `turboism-release-api` Worker.
- Full SHA-256 of the six Stable, Beta, and Nightly EXE and ZIP assets, the checksum files, Range 206, and the per-file download counters all passed; the acceptance downloads used the statistics exclusion marker.
- Local re-check at 04:18 UTC: `/health` and all three channels reported `ready`; the version, build, source, and asset hashes of the three channels matched the pre-deployment values with no `release.incremental`.
- GET on the document and payload path with an unknown hash answered `404 FRAMEWORK_RESOURCE_UNKNOWN`, HEAD answered a 404 with no body, and CORS was normal.
- A credential-free empty request to the incremental admission and withdrawal endpoints answered `503 FRAMEWORK_DISABLED`.
- The public GitHub configuration was read again after deployment: the signing switch is still `false`, the client and Worker public keys match, the signing environment still holds only the `main` branch rule, and the secret update time is unchanged.

## Remaining Release Steps

`FRAMEWORK_UPDATE_MINIMUM_UPDATER` waits for the first real release version. The release credential of the `production-release` environment, client activation, and the real A-to-B-to-C acceptance are not complete.

The product code is still on `test/issue-50-windows-validation-fixes` and was not merged into main. The existing production candidate and signing flow require main, so the source release arrangement still needs a decision; this round configured only the key and the public trust material.

Local public evidence lives in the ignored `build/agent-tasks/framework-signing-public/` directory: `client-trusted-keys.json`, `worker-trusted-keys.json`, and `provisioning-record.json`. That directory holds no private key. The production deployment log is `build/agent-tasks/framework-key-deployment.log`, and the full download acceptance and the local endpoint re-check are kept in `build/agent-tasks/framework-key-deployment/api-production.json` and `public-api-checks.json`.
