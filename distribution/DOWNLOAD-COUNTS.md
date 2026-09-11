# Per-release download requests

`GET /v1/downloads/<version>.json` is an optional analytics endpoint. It is separate
from update metadata so a statistics outage never invalidates a release. It only
reads known, active releases; it cannot increment or set counters.

The total adds GitHub's binary-asset `download_count` to official mirror starts.
The four binaries count; checksum sidecars do not. GitHub keeps its own platform
semantics, including any automated downloads. We cannot subtract mirror or CI
requests retroactively from GitHub counts. GitHub values refresh with release
synchronization. Missing/invalid source data is null (`partial`), never a fake zero.
The UI must show an em dash for an unknown total and identify available sources.

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
binary requests. This is a public analytics opt-out, not authentication and does
not bypass any authorization, WAF, file integrity or rate limit. Prefetch/prerender
requests are also excluded. Audit opt-out on the official source cannot change
GitHub's separate metric.

File responses use `private, no-store` so a response cache cannot conceal new
request starts from the Worker. R2 object integrity and immutable storage remain
unchanged. A statistics write/read failure is isolated from streamed file serving.

Test with `node --test distribution/test/*.test.mjs`. The counter tests exercise
real SQLite transactions and concurrent starts, not a mock count implementation.
