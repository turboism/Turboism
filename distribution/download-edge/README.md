# Isolated cacheable download edge (opt-in, not yet production)

The release API remains the **control plane** and the existing `/files/<sha>/<name>`
endpoints remain unchanged for old clients. A separate Worker `turboism-download-edge`
on `dl.turboism.dev` is the **data plane** for **currently published** installer
and ZIP files.

**Do not attach an R2 custom domain or enable `r2.dev` public access on the
existing `turboism-downloads` bucket.** The edge Worker has a PRIVATE R2
binding and a service binding to `turboism-release-api`. Its route admits
only immutable SHA-256 paths from the current published release manifest,
checks R2 object length + SHA-256 metadata and streams bytes without using the
counter Durable Object. It never provides directory listings.

## How the paths work

1. A browser requests
   `GET https://api.turboism.dev/v1/downloads/start/stable/files/<sha256>/<filename>`.
   The existing API Worker/DO checks the current channel, active release and
   verified mirror metadata, records the **download start** with existing dedup
   and analytics opt-out rules, then returns a **noncacheable 302**.
2. The browser follows the redirect to
   `https://dl.turboism.dev/stable/files/<sha256>/<filename>`.
   This is served by an independently deployed Worker, with
   `Cache-Control: public, max-age=0, s-maxage=60, must-revalidate` on valid
   bytes only. `wrangler.download-edge.json` also explicitly sets
   `cache.enabled=true`; a header alone does NOT activate Workers Cache.
   Each response carries a `Cache-Tag: turboism-<channel>-<sha>`.
   On a cache HIT Cloudflare can return bytes without executing the
   Worker or reaching R2; on MISS the Worker uses its service binding to check
   the published release and reads from its private R2 bucket binding.
3. An old updater or a user choosing the legacy official URL continues to use
   `https://api.turboism.dev/files/<sha256>/<filename>` with its exact
   Range/If-Range/ETag semantics, and is counted once as before.
4. Direct requests to `dl.turboism.dev` intentionally are **not counted**.
   Only validated browser starts through the redirect route can enter the
   existing official counter. The metric is download **starts**, not completions.

The redirect route is **disabled by default** and returns 404 until the
existing API Worker is deployed with `EDGE_DOWNLOADS_ENABLED="true"`.
Do not enable until all deployment gates pass.

## Predeployment checks

- Release API `/health` and all three channels are HTTP 200 JSON through
  ordinary DNS; never rely on a Cloudflare `--resolve` diagnostic IP.
- Current `api.turboism.dev` DNSPod/Caddy chain remains untouched.
- Cloudflare must have a verified `dl.turboism.dev` custom domain with no
  conflicting A/CNAME/NS record; **do not** change `api.turboism.dev` or
  `plugin.turboism.dev` DNS.
- Use **separate** `turboism-download-edge` Worker, config
  `distribution/wrangler.download-edge.json`. Its service binding must target
  the existing `turboism-release-api` Worker on the **same Cloudflare account**.
  R2 binding remains private, referencing `turboism-downloads`. The existing
  API Worker config, migration and Durable Object must not be overwritten.
- Deploy the independent Worker first, and only after successful tests deploy a
  reviewed update of the existing release API source pinned in
  `turboism-learn/.github/workflows/deploy-release-api.yml`. Note that
  editing that workflow **auto-deploys production**. Test carefully.
- Configure Worker runtime `EDGE_DOWNLOADS_ENABLED="true"` only after the
  `dl` domain, private R2 binding, and service binding all work. In Wrangler,
  this can be a reviewed `vars` addition to `distribution/wrangler.json`
  for the existing API Worker; **do not** add it at initial rollout.
- Run `node --test distribution/test/*.test.mjs` against the tested exact
  commit and verify with Wrangler locally or staging before any production deploy.

## Canary / validation matrix

- GET/HEAD/OPTIONS; malformed and cross-channel path => 404/405.
- Cold cache and second HIT, verify `CF-Cache-Status` and real downloaded byte
  counts; do not infer throughput from online tools that truncate response
  body to 10 KiB.
- GET with `Range: bytes=0-1048575` => 206, exact Content-Range and bytes;
  suffix/invalid ranges, If-Range matching vs mismatch, If-None-Match => 304;
  full-file SHA-256 must match release metadata. Verify HTTP/2 reset rate.
- Check the existing `/files` endpoint is byte-for-byte compatible and the
  legacy updater still passes its contract tests.
- Confirm exactly one official download start through the 302, with the same
  30-minute dedup rules; direct CDN GETs **do not increment** the counter.
- Withdraw a canary release and verify **new cache MISSes deny it** immediately;
  use a **Worker-cache purge by tag** (e.g. `turboism-stable-<sha256>`)
  or exact URL/path prefix for cached bytes and confirm cached HITs are
  inaccessible. An already-cached withdrawn URL can persist for up to **60s**
  without explicit purge; this is a behavior change and requires an agreed
  revocation runbook before enabling by default.
- Probe real Telecom/Unicom/Mobile + overseas hosts with full 8 MiB and full
  installer transfers at peak/off-peak, record success, range, checksum,
  throughput, errors and latency, and compare with the legacy `api` route.
- Keep the legacy download as a fallback, and roll back the UI selection by
  reverting the canary feature flag. To disable new starts immediately set
  `EDGE_DOWNLOADS_ENABLED` to false; this **does not** revoke already cached
  files, which require explicit purge / TTL expiry.

## Why not simply set the existing /files Cache-Control to public?

CDN hits bypass the existing Durable Object and would silently change download
statistics. Also, a withdrawn package can remain cached. This split isolates
cache semantics and preserves the existing update feed, integrity checks, and
file transfer fallback. Cloudflare Workers Cache has a 60s TTL here; actual
hit behavior must be measured, not assumed.

Cloudflare docs:
- https://developers.cloudflare.com/workers/cache/
- https://developers.cloudflare.com/cache/reference/range-requests/
- https://developers.cloudflare.com/workers/runtime-apis/cache/
- https://developers.cloudflare.com/r2/reference/consistency/
