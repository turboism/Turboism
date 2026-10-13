# Per-release download requests

`GET /v1/downloads/<version>.json` is an optional analytics endpoint. It is separate
from update metadata so a statistics outage never invalidates a release. It only
reads known, active releases; it cannot increment or set counters.

The total adds public GitHub binary-asset downloads to official mirror starts.
The four binaries count; checksum sidecars and standalone developer SDK assets do
not. GitHub's raw `download_count` includes internal fetches that occur before or
during initial release mirroring (such as the Cloudflare Worker's one-time mirror
pull of each binary). To exclude those internal fetches:

1. When `mirrorRelease` first completes mirroring a release into R2, it captures
   each binary asset's post-pull GitHub count (`downloadCount + 1` for each binary
   pulled from GitHub during mirroring) and writes it once into the immutable R2
   completion marker `mirrors/<tag>.json` under `baseline` (`onlyIf:
   {etagDoesNotMatch: '*'}`). `synchronize` also preserves the captured per-asset
   `internalBaseline` immutably in the Durable Object SQLite `known_releases` table.
2. Public GitHub count per binary is `max(0, current - internalBaseline)`. Each
   `assets` entry exposes `internalBaseline`, and `github.internalBaseline` exposes
   the release-wide baseline sum alongside `github.count` and `github.asOf`.
3. Releases mirrored before baseline capture have no `baseline` field in their
   existing `mirrors/<tag>.json` marker and fall back to the reviewed overrides in
   `distribution/download-count-baselines.json`. If a release has neither a captured
   baseline nor an override entry (for example, an unmirrored release), its
   `internalBaseline` defaults to `0`.
4. GitHub values refresh with release synchronization. Missing/invalid source data
   remains `null` (`partial`), never a fake zero. The UI must show an em dash for an
   unknown total and identify available sources.

Official counting begins at `official.since`, the actual first initialization of
this feature. Past downloads are not reconstructed from release dates or legacy
Updates. Official counters survive deployments in the existing SQLite registry.
A successful full GET or a 206 starting at byte zero counts as a request start,
not a completed download or a unique user. HEAD, 304, failed requests, checksum
files and nonzero resume ranges are excluded. Repeated starts for the same asset,
Cloudflare-provided IP and user agent within a sliding 30-minute window count
once. Same-network/same-UA clients can be grouped; deliberate abuse is not fully
prevented. This is a request statistic, not a licensing or security mechanism.

No raw IP or user agent is persisted. Asset-scoped HMAC fingerprints use a random
private registry salt. Expired fingerprints are pruned on traffic/reads and regular
synchronization; aggregate counts remain. No tracking cookies are set.

Automated verification must send `X-Turboism-Download-Purpose: verification` on
binary requests, or verify GitHub Release assets via the GitHub REST API `digest`
and `size` fields without downloading binary bytes. This header is a public
analytics opt-out, not authentication, and does not bypass any authorization, WAF,
file integrity or rate limit. Prefetch/prerender requests are also excluded.

File responses use `private, no-store` so a response cache cannot conceal new
request starts from the Worker. R2 object integrity and immutable storage remain
unchanged. A statistics write/read failure is isolated from streamed file serving.

## Historical baseline overrides (`distribution/download-count-baselines.json`)

For releases mirrored before automatic baseline capture, internal GitHub fetches
are derived from the release workflows (`release.yml`, `release-github-only.yml`,
`release-publisher.yml`, `verify-github-assets.py`, and
`turboism-learn/.github/workflows/deploy-release-api.yml`):

- Candidate build and promotion workflows verify local Actions artifacts before
  upload and verify published GitHub Release assets via the GitHub API `size` and
  `digest` (`sha256:...`) metadata fields (**0** GitHub binary downloads).
- Post-deploy API verification (`deploy-release-api.yml`) downloads only from the
  official R2 mirror (`https://api.turboism.dev/files/...`) with
  `X-Turboism-Download-Purpose: verification` (**0** GitHub binary downloads).
- The Cloudflare Worker mirror (`distribution/mirror.mjs`) downloads each of the
  four binaries **once** from GitHub when first populating R2 (**1** GitHub binary
  download per asset).

| Tag | `.exe` | `.jar` | `-full.zip` | `-lite.zip` | Total baseline | Observed GitHub (`.exe` / `.jar` / `-full` / `-lite`) | Derivation |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `v0.67.0-beta.1` | 3 | 3 | 3 | 2 | 11 | 3 / 3 / 3 / 2 | Observed pre-fix counts are 100% internal (1 R2 mirror pull + maintainer/rollout verification checks). |
| `v0.44.1-0.nightly.11` | 1 | 1 | 1 | 1 | 4 | 3 / 3 / 4 / 4 | 0 publisher/verifier GitHub downloads + 1 R2 mirror pull per binary. |
| `v0.44.1-0.nightly.10` | 1 | 1 | 1 | 1 | 4 | 3 / 3 / 3 / 3 (2 / 2 / 2 / 2 at last active sync) | 0 publisher/verifier GitHub downloads + 1 R2 mirror pull per binary. |
| `v0.44.0` | 1 | 1 | 1 | 1 | 4 | 3 / 2 / 12 / 3 | 0 publisher/verifier GitHub downloads + 1 R2 mirror pull per binary. |
| `v0.43.11` | 1 | 1 | 1 | 1 | 4 | 1 / 1 / 1 / 1 | 0 publisher/verifier GitHub downloads + 1 R2 mirror pull per binary (exact match to observed 1/1/1/1). |
| `v0.43.10` | 1 | 1 | 1 | 1 | 4 | 1 / 1 / 1 / 1 | 0 publisher/verifier GitHub downloads + 1 R2 mirror pull per binary (exact match to observed 1/1/1/1). |
| `v0.43.10-0.nightly.3` | 1 | 1 | 1 | 1 | 4 | 2 / 2 / 2 / 2 | 0 publisher/verifier GitHub downloads + 1 R2 mirror pull per binary. |
| `v0.43.9` | 1 | 1 | 1 | 1 | 4 | 5 / 4 / 4 / 4 (`null` in API snapshot) | 0 publisher/verifier GitHub downloads + 1 R2 mirror pull per binary when `api.turboism.dev` first launched. |
| `v0.43.2`, `v0.43.1`, `v0.43.0`, `v0.42.0` | — | — | — | — | — | Not in `api.turboism.dev` (`404`) | Predated `api.turboism.dev` (never mirrored to `turboism-downloads`; 0 workflow GitHub downloads). |

Test with `node --test distribution/test/*.test.mjs`. The counter tests exercise
real SQLite transactions and concurrent starts, not a mock count implementation.
