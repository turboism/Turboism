# Turboism distribution service

`https://api.turboism.dev` is the new release API. The legacy Updates service is
neither queried nor modified. Public product GitHub Releases are authoritative.
No software is built or published by the synchronization service.

## Public contract

- `GET /v1/releases/stable.json` (default), `/beta.json`, `/nightly.json`
- HTTP 200 `ready` contains a published release with `version`, `buildNumber`,
  `channel`, resolved source commit, release notes, SHA-256, size and sources.
- `buildNumber: null` means a historical release has no build receipt. It is never
  replaced with a timestamp, Release ID, sync counter or a guessed number.
- HTTP 200 `not_published` requires a complete, successful GitHub enumeration.
- HTTP 503 `unavailable` means the latest release could not be verified. It does
  not mean the client is up to date. Retry-After is 60 seconds; errors are not cached.
- Successful reads support HEAD, ETag/If-None-Match, CORS and JSON content types.
- `GET /health` reports synchronization and mirror configuration separately.
- `GET /files/<sha256>/<filename>` serves only known, nonwithdrawn product files
  from the new R2 binding. No arbitrary URL or transparent GitHub proxy exists.
  Single byte ranges, If-Range, ETag and HEAD are supported.

Channel precedence is NOT update precedence. Filter by selected channel and
compatibility, compare SemVer, then use build identity to distinguish builds.
Never silently downgrade a newer development line to a later-built old stable
line. Switching channels requires the client's explicit user choice. SemVer
build metadata (`+...`) does not change precedence. Nightly tags may use
`vMAJOR.MINOR.PATCH-0.nightly.NUMBER`; nightly publication is not enabled by this
API. Drafts and plugin tags are excluded; prerelease flags must match tags.

## Synchronization and security

A SQLite-backed Durable Object persists snapshots and coalesces refreshes.
Cloudflare Cron refreshes every 15 minutes. First use initializes the registry.
GitHub's `notify-release-api.yml` requests an immediate refresh after release
publication/changes or after the protected publisher finishes. The notification
uses verified GitHub OIDC (repository ID, audience, workflow, ref, expiry and RSA
signature), so no shared API secret is needed. The public cannot invoke refresh
without authentication. No candidate artifact is executed by this service.

Remote lists are bounded and paginated; failure does not masquerade as absence.
File identities are validated from GitHub's SHA-256 asset digests. The exact tag
is resolved to a commit. New build receipts in release notes are matched against
`build-ledger` entries. Known same-version files or source commits cannot silently
change. Withdrawn releases stop being advertised/served; no stored data is deleted.
The JSON and checksums are not code-signing certificates or binary signatures.

## Deployment

Source is in the product repo. `deploy-release-api.yml` in `turboism-learn` pins
this source revision and uses the owner's already configured Cloudflare CI
credential; it does not modify the tutorial Worker or its database. Separate
concurrency groups keep deployments independent. Update the pinned revision to
upgrade this service. The token needs Workers Scripts Edit and Workers Routes
Edit. Cloudflare manages the custom-domain certificate/DNS; conflicting domains
must not be overwritten automatically.

R2 is optional at startup. Without it the API advertises **GitHub sources only**,
not a fictional China mirror. For mirrored downloads, add Account > Workers R2
Storage > Edit to the publishing credential, enable the isolated
`turboism-downloads` bucket/binding, and run the mirror workflow. Existing legacy
buckets must never be reused or cleared. Region/provider latency still needs
mainland-China testing; global Cloudflare delivery is not a mainland-CDN guarantee.

## Local checks

`node --test distribution/test/*.test.mjs`
`python3 -m unittest discover -s scripts/release -p test_build_identity.py`

The regular product candidate remains explicitly promoted. No new product version,
GitHub tag, installer or public nightly build is created by deploying this service.
