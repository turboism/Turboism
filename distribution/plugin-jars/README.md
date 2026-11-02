# Plugin JAR mirror: API control plane + Cloudflare R2 byte plane

**Server implementation is staged, NOT automatically deployed.** The existing
Turboism self-update / release API and old JAR GitHub URLs continue to work until
the explicitly reviewed staging and rollout gates pass.

## Contract for the independent local Java client agent

The trusted Plugin Directory v2 signed catalog remains at
`https://plugin.turboism.dev/api/v2/catalog.json` and `.sig`.
Each verified `plugin.releases[].artifact` contains `fileName`, `size`,
`sha256`, `descriptorSha256` and the original GitHub `url`.

* `GET https://api.turboism.dev/v1/plugins/artifacts/<sha256>/<filename>.jar`
  responds with noncacheable **302** to the CDN for allowlisted, active,
  successfully mirrored artifacts.
* `GET https://dl.turboism.dev/plugins/<sha256>/<filename>.jar` streams the
  R2 bytes. HEAD/Range/If-Range/If-None-Match support 200/206/304/416 with
  byte-accurate Content-Range/Content-Length, ETag = quoted SHA256, and a
  **60-second shared cache**. An allowed object gets cache tag
  `turboism-plugin-<sha256>` for targeted revocation purge.
* Unknown/withdrawn/unmirrored file -> 404 or 503. The client MUST
  automatically fall back to `artifact.url` from the verified signed catalog,
  and MUST validate size, SHA256, JAR descriptor, plugin ID and version **before
  installing**. A TLS/HTTP success or ETag does not replace that validation.
* The signature catalog continues to publish **original GitHub URLs**.
  Never replace signed URLs with a CDN address; old clients stay compatible.
* Plugin install, plugin auto-update and update-window download all use the
  same `PluginCatalogClient`; local agent owns this Java work, not this PR.

## Trust + storage

Publisher reads the production catalog+detached signature and validates
Ed25519 using the checked-in *production-only* trusted public key; it then
downloads every **active** JAR from its signed GitHub Release URL, verifying
the entire file size and SHA256. Only after all required bytes are verified
does it write the private R2 index:

`plugins/index-v1.json`

The JAR object paths in the existing **PRIVATE** `turboism-downloads` bucket:

`plugins/objects/<sha256>/<filename>.jar`

Index schema:
`{schemaVersion:1,catalogVersion,signedCatalogSha256,artifacts:[{pluginId,version,sha256,fileName,descriptorSha256,size,githubUrl}]}`.

This index is private trusted **deployment state**, never supplied by the
requester. Worker checks its entry and R2 object size on both the API and
the CDN. It does not do on-demand GitHub proxying and does not expose
arbitrary R2 keys. No new R2 public custom domain or `r2.dev` access.

The CLI uploader does NOT change signed source catalogs, plugin JARs, or the
published signature, and needs no Ed25519 private key.

## Deployment gates

1. [Plugin Directory mirror stage] Validate the **live production** signed
   catalog and separately verify all active GitHub JAR bytes; the 2026-11-01
   test covered 33 plugins / 191 release JARs / 20,451,009 total bytes.
2. Upload objects to private R2 using an existing Cloudflare-authorized manual
   workflow; upload `plugins/index-v1.json` **LAST**. If any JAR fails, DO NOT
   publish the new index.
3. Verify the existing API `/health`, main release JSON feeds, existing
   `/files` binary Range, `dl` installer Range, Caddy/DNS/TLS health.
4. Deploy **the existing named** `turboism-download-edge` and
   `turboism-release-api` Workers from the exact tested source. Confirm
   neither Worker route nor Durable Object migration differs from the current
   release configs; do not modify DNSPod/Cloudflare DNS.
5. Canary one small JAR: API 302 points to the expected CDN hostname;
   CDN HEAD returns correct size/ETag; 1KiB Range 206 is correct;
   FULL JAR download matches signed SHA256; CDN MISS then HIT improves
   real transfer. Probe three mainland carriers and at least one overseas
   network before changing client defaults.
6. A withdrawn plugin is removed from the next index; **purge its cached CDN
   URL/cache tag immediately**. Cache HITs may survive for up to 60 seconds
   if no purge is performed. Do not call withdrawal immediate if cache purge
   is untested. For urgent compromise, disable CDN Workers and purge.
7. Rollback: revert only the new Worker code/config to the previous pinned
   deployment; existing main downloads and GitHub plugin URLs stay valid.
   Do not delete historical R2 data as a rollback mechanism.

Production deployments of `turboism-release-api` are triggered when
`turboism-learn/.github/workflows/deploy-release-api.yml` is edited. **Do
not edit that workflow as part of the initial mirror upload**. Use a separate
manual-only workflow and explicit review. No on-demand downloads are proxied
through the plugin directory website itself.

## Verification

`node --test distribution/test/*.test.mjs` from Turboism repo.
`wrangler@4.127.0 deploy --config distribution/wrangler.json --dry-run`
and same with `wrangler.download-edge.json`.

## Security note

The end-user **must** hash the actual JAR. R2 inventory + size allowlist
is backed by the publisher's prior signature and hash validation, but the
Worker doesn't recompute the entire SHA-256 on every cached Range request.
