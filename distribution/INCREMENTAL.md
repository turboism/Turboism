# Offline incremental admission v1

`incremental.mjs` is the admission protocol. `framework.mjs` (imported by `worker.mjs`)
supplies the production storage authority and public reads described in
"Production service (api.turboism.dev)" at the end of this file. The text below
describes the protocol and client contract; where it says "not deployed", read it
as "inert until an operator configures keys and a publisher workflow".

The local Python producer writes `turboism-V-incremental.json`:

```
format: turboism.incremental-candidate
schemaVersion: 1
repository: turboism/Turboism
identity: {version, channel, buildNumber, sourceRevision}
manifest: {bytes, sha256}
envelope: {bytes, sha256}
objectsSha256: SHA256(deterministic JSON of unique [{bytes, sha256}], digest-sorted)
```

This descriptor is untrusted. The protected publisher must independently verify
candidate/ledger/tag source and supply the exact expected identity and keyId;
public Ed25519 SPKI keys come from operator-pinned trust configuration. No key
from the candidate, objects.json, object metadata, or ETag supplies authority.
The receiver verifies the precise original manifest bytes, then its fields,
identity, layout and complete framework payload. It derives the unique object
closure from that signed manifest and checks each actual object's length and SHA.
The v1 publisher requires this project's deterministic UTF-8 JSON encoding;
noncanonical or duplicate-key documents fail closed. This is not JCS and does
not change the Java verifier's ability to authenticate other exact signed bytes.
Publisher limits include 1 MiB manifest, 16 KiB envelope, 512 entries, 240-character
ASCII paths, 512 MiB files, 1 GiB expanded payload, safe-integer build numbers and
Java int version components. All plugins remain on the independent catalog path.

`admitIncremental(candidate, expected, trustedKeys, source, store, options)`
uses only abstract offline interfaces, with no network/upload implementation:

- `source(key, maxBytes)` returns a bounded Uint8Array or missing. Document keys
  are `documents/SHA`; payload keys are `objects/SHA/payload`.
- `store.createImmutable(key, bytes)` atomically creates absent bytes and never
  replaces present bytes. Admission independently rehashes readback, including
  existing objects. `store.read(key, maxBytes)` returns actual bounded bytes.
- `store.readChannel(channel)` returns the current immutable release ID, or
  exactly null for an absent channel. Admission reads that snapshot and verifies
  its release record, exact signed manifest/envelope, closure, and version/build
  indexes. The ID binds to the authenticated manifest SHA, not an untrusted label.
- `store.compareAndSwapChannel(channel, expectedCurrent, nextId)` is a
  **linearizable** expected-current CAS against that same verified snapshot.
  A production implementation needs a transactional authority, not an R2 head/put
  pair or a declaration that R2 keys are immutable.

`options.previous` must equal the observed channel snapshot. Normal admission
itself requires both strictly increasing SemVer (using protocol.mjs comparison)
and strictly increasing global buildNumber. Same-version reinterpretation,
older branches with higher builds, and newer versions with stale builds fail.
Missing/tampered old records or unknown historical signing keys fail closed.
An exact same-record retry authenticates the current record and returns without
writing a pointer, including when the original previous ID is now stale.
An empty channel requires explicit `previous:null, initialize:true`.
On any other CAS race the caller must fetch a new snapshot and retry the entire
verification; admission never silently substitutes another previous ID.
Both public verification and admission snapshot candidate/expected/keys before
the first async callback.

All source content is validated before storage writes. Payload objects are
created and independently read back, then manifest and envelope are registered,
then immutable version/build/release records, and channel CAS occurs last. Version
and global build indexes prevent the same identity acquiring another manifest.
An error, conflict, abort or CAS race leaves the old channel usable; immutable
orphans can remain for retry and are never deleted. The adapter must not expose
registration keys as channel pointers. Cancellation after an atomic CAS has
committed cannot undo that commit. Local mocked adapters exercise ordering and
failure recovery; no real storage atomicity has been proven.

`verifyHistoricalPromotion` is a separate read-only operation: the protected
archive must contain the exact pre-existing historical record under its digest,
with resolved source/version/channel/build and exact asset size/hash identities.
The verifier also rehashes those assets. It does not generate historical evidence,
accept a caller's legacy flag, or switch a channel. Unknown candidates cannot
fall back to this path. Creating a trusted historical archive requires independent
review of the already-published release/tag and immutable asset evidence; local
scans, new candidates and self-declared timestamps cannot establish it.

Before production activation, operators must select an activation boundary and
pin its policy version, public keyring/key rotation rules and trusted historical
archive anchors. The protected publisher must route every new candidate through
v1 and preserve authoritative discovery source,
and preserve history promotion only for pre-established evidence. Implement and
review streaming bounded storage adapters, linearizable CAS authority, API serving
of registered immutable assets and public reachability preflight before enabling.
Until those dependencies exist the current production chain stays unchanged;
there is no permissive 8/10 fallback or unconditional break of existing releases.

Run the existing `node --test distribution/test/*.test.mjs`. It executes real
Python/OpenSSL fixture output with temporary keys, plus memory-store failure,
conflict, interrupted retry and channel CAS scenarios. No production network is used.

## Read-only client discovery prerequisite

The Java discovery/cache/service path can retain an optional `release.incremental`:

```
{source: {origin: "https://api.turboism.dev", redirects: "reject"},
 descriptor: <the unmodified v1 incremental-candidate JSON>}
```

The authority remains the existing fixed-origin `/v1/releases/<channel>.json`
transport, which rejects HTTP redirects. For this optional capability the feed's
source.repository, metadataUrl and release.channel must match that authority;
release.version/buildNumber/sourceRevision must exactly match descriptor.identity.
The source promise cannot change an endpoint or authorize following a redirect.
No new download URL is declared or inferred from the object/document keys.

Current production parseRelease already supplies tag/ledger-verified sourceRevision.
The production service does not yet advertise this wrapper; future activation
must project it only from a registered, admitted incremental release. The current
worker and publisher remain untouched. Syntactically valid but false hashes/sizes
cannot be proven false until exact downloaded bytes are checked; this metadata
does not confer signature trust. The cache preserves the complete tuple and
capability together, and the service exposes it only after successful discovery
while an update is available. Invalid responses and network failures do not merge
new identity fields into old cache. Legacy discoveries retain the full-update path.
No receipt evidence, TrustedUpdateManifest, downloader or apply permission is made.

## Local client download slice (TASK-066-6B1)

`FrameworkUpdateDownloader` is a caller-executor-owned, sequential streaming
session. It downloads bounded descriptor-addressed documents, invokes the existing
manifest verifier with independent expected and installed context, then the
read-only planner. Only authenticated framework DOWNLOAD objects are transferred;
REUSE stays local and duplicate content is fetched once. Installation hashes must
come from authenticated installation evidence; no production receipt loader is
provided. No installation file is written.

`FrameworkUpdateSource.disabled()` is the default source. There is no production
call site or deployed endpoint contract. A package-owned trusted base-path adapter
can only resolve digest paths on HTTPS api.turboism.dev without userinfo, ports,
query or fragment. Its relative document/object paths express a proposed adapter
contract, not live URL availability. HTTP loopback is a separate package test seam.
All redirects, compressed bodies, unexpected lengths/statuses/ranges are rejected.

The exclusive cache root is created private (0700/0600), recognizes only its own
marker, uses a file writer lock, and checks static ancestor/type/link-count/owner
constraints. POSIX-only admission currently fails closed on Windows until private
ACL/link checks are implemented. A `.verified` suffix records resource-byte cache
placement only: every reuse rehashes the full file and every session reauthenticates
the exact documents. It is never an install/staging trust marker. Corrupt resource
files can be removed only at generated cache keys; unknown directories are never
adopted or recursively cleared. Parent-directory races are not solved here.

`.part` and an atomically written strong ETag can survive failure/cancellation.
Resume requires Range + If-Range and an exact 206 Content-Range/length/ETag.
A 200, 416 or changed validator permits one fresh restart, never an append of
a full response. Digest mismatch discards that resource without retry. Network
socket/connect/read-timeout/EOF and HTTP 408/429/500/502/503/504 failures permit at
most two transient retries, sharing a maximum of four HTTP requests with the one
Range reset. Backoff is 100/200 ms with Retry-After (seconds or HTTP date) bounded
to two seconds. Local IO, signature, identity, redirect, encoding, size and range
failures do not retry. Each request has a 30-second deadline, 5-second connect/read
timeouts and a session deadline of at most ten minutes. Concurrent transfer count is one per session and the cache writer lock
excludes competing writers. Caller-owned executors/timers must remain live through
teardown; cancellation completes the future and dispatches potentially blocking
connection cleanup off the calling thread.

Progress is per resource: received is the current on-disk extent (including
resumed bytes), verified is zero until a complete SHA-256 succeeds, total is that
resource's declared length. An explicit RESTARTING event resets extent to zero;
retry traffic is never added to it. Callbacks run on the IO worker and must not
block or perform UI work directly. taskVerified/taskTotal count actual verified
target bytes: current REUSE plus completely verified DOWNLOAD objects, expanded
across target entries. TASK events have empty resource SHA and zero resource
extent, so even an all-REUSE task reports truthful aggregate progress. Totals are
zero before manifest authentication and planning. No retry traffic is accumulated.

`download()` still returns volatile session output without staging. The explicit
`stage()` and `recover()` APIs below return an optional StagedTask observation;
none of these paths grants future apply authority. Production enablement,
downloader UI, receipt loading, Windows ACL/reparse/TOCTOU, helper and apply remain
pending.

## Persistent local staging (TASK-066-6B2, POSIX only)

`stage(candidate, freshContext, installationRoot, progress)` downloads as needed
and publishes a task only after complete final revalidation.
`recover(taskId, candidate, freshContext, installationRoot, progress)` is cache-only:
missing or corrupt resources fail, and explicit stage is needed to fetch replacements.
Both require trusted current expected identity and authenticated installation
evidence from the caller. There is no production receipt loader or online
revocation authority here. A saved record cannot supply these trust inputs.

`FrameworkUpdateTaskStore` writes bounded strict JSON records at generated
`task-<id>.json` names. Records contain the full descriptor, a binding SHA, state
(DOWNLOADING or STAGED), and digest/length references. Binding covers normalized
root digest, installed identity, the caller's trusted old hash evidence table and
updater/platform policy. It is an equality check against fresh inputs, not a signed
installation receipt. No absolute paths, Plan or Action are serialized. Exact signed
documents stay in digest-addressed document resources. Referenced object lists
are conservative GC pins only; signed manifests recompute the real closure.

Recovery validates the record against current inputs, atomically demotes it to
DOWNLOADING, reauthenticates exact documents and admission, validates every supplied
old evidence file, rebuilds the plan from the current installation, and rehashes
all needed objects. It repeats signature/installation/object checks after commit
preparation. Missing old owned files also fail, even when the target object exists
in cache. Stored status, filenames, ETags, references or mutable Downloaded paths
cannot bypass these checks. Malformed task records fail closed and block GC;
they are reported rather than guessed, followed or automatically repaired.

Record writes use private temporary files, force(true), same-directory ATOMIC_MOVE
(no fallback), and directory force. Resource publication and ETag metadata also
force before rename. Private root initialization forces its marker and directory
and attempts parent-directory persistence. Tests cover process interruption
boundaries, not a universal hardware/power-loss guarantee.

A short in-memory COMMITTING claim arbitrates cancellation without blocking the
cancelling/UI thread on IO. Cancellation before this claim prevents STAGED. After
the claim, cancel returns false and the future reports actual completion or
failure; the visibility point remains atomic rename. If rename succeeds but a
subsequent operation fails, StagedTask reports published state with
`directoryDurabilityConfirmed=false`, never a cancelled future. Recovery must
still repeat all validation. STAGED means verified staging, never installed.

The cache enforces a 2 GiB default logical-byte quota over all flat files, including
partial/verified payloads, documents, records, temporary metadata, owner/lock and
unknown regular files, plus actual free-space checks. It bounds entries to 4096
and task records to 64. Growth reservation charges only missing extents; valid
cached objects need no second payload copy. Atomic metadata replacement charges
its real temporary copy. All task pins (including incomplete tasks) and current
session pins are retained. Under pressure GC removes only unreferenced generated
resource keys, preserving unknown files and other tasks. `discard(taskId)` explicitly
releases only a validated task record; shared resources remain pinned until their
last task reference disappears. Links/hardlinks/directories and malformed records
refuse cleanup. No recursive or record-supplied path deletion exists.

This remains a local POSIX implementation. Windows requires a separately reviewed
private DACL/owner/nlink/reparse/handle file layer and native evidence. It remains
fail-closed; Linux tests do not complete the Windows product path.

## Production service (api.turboism.dev)

The GitHub Release contract is **unchanged**: exactly the eight existing assets, the
EXE/ZIP whitelist in `parseRelease`, and the R2 `files/` mirror. Incremental resources
are never GitHub assets and never pass through the mirror. They have a separate,
authenticated delivery path:

1. The protected publisher signs the manifest offline (`build-framework-update.py`) and
   runs `publish-framework.mjs`. It uploads manifest, envelope and payloads to the
   non-public R2 prefix `framework-incoming/` (give it a lifecycle expiry; never serve it).
2. `POST /v1/framework/admit` (GitHub OIDC, audience `https://api.turboism.dev`, workflow
   `FRAMEWORK_PUBLISHER_WORKFLOW`, optional `FRAMEWORK_PUBLISHER_ENVIRONMENT`; the sync
   notifier workflow is refused) runs `admitIncremental` inside the `FrameworkAuthority`
   Durable Object. Payloads are hashed and copied as streams; R2 enforces the digest and
   never replaces a key. Documents and payloads land in `framework/`, records in
   `framework/releases|versions|builds/`. The channel pointer CAS, the release row and the
   public-resource registration commit in one SQLite transaction. Failure leaves the old
   channel and only unreferenced immutable orphans.
3. `GET|HEAD /v1/framework/documents/<sha256>` and `/v1/framework/objects/<sha256>/payload`
   serve only registered resources from the official origin: exact bytes, `Content-Length`,
   strong ETag `"<sha256>"`, `application/octet-stream`, `Cache-Control: ... no-transform`,
   `Range`/`If-Range` (206, 416; a stale validator yields a full 200). Unknown keys are JSON
   404; a stored object whose size/R2 checksum disagrees is a 503, never served.
4. `/v1/releases/<channel>.json` gains `release.incremental` only when an admitted,
   unwithdrawn record has the same version, channel, buildNumber and sourceRevision as the
   release. Any authority failure silently returns the plain full-installer document.
5. `publish-framework.mjs` finishes with a public preflight (redirect-free, byte-exact, Range).
   Only after it passes should the GitHub release be published/synchronized, so the
   version document never advertises resources that are not yet reachable.
6. `POST /v1/framework/withdraw {"id": "<manifest sha256>"}` (same identity) stops
   advertising a release; resources stay served for in-flight resumes.

Operator configuration (all inert while unset): `FRAMEWORK_TRUSTED_KEYS` (JSON key id ->
base64 Ed25519 SPKI; the same public keys must be compiled into the client),
`FRAMEWORK_PUBLISHER_WORKFLOW`, `FRAMEWORK_PUBLISHER_ENVIRONMENT`. The client endpoint
`https://api.turboism.dev/v1/framework/` is now this service's prefix. Deploying adds the
`FrameworkAuthority` Durable Object (migration `v2`); it changes no existing route.
