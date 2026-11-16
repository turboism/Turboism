# Website and API: Incremental Resource Publishing and Download Endpoints for Framework Self-Update (related to #50)

Related: #50 (in-Cubism incremental updater).

> This document keeps the early task draft. Checked on 2026-11-01: `distribution/worker.mjs` already serves incremental admission, withdrawal, resource reads, and `release.incremental`, and it was approved for production deployment, with the 78 tests of the real deployment workflow and the full download acceptance passing; the release CI, the signing, and the initial installation receipt wiring of Stable and Beta are complete. The production signing key and public key were then configured and the server public key deployed, while signing and the self-update switch stay off. An unchecked box below does not mean the code is still missing. The remaining work is to fix the first release version and the source release arrangement, configure release authority and activation, and complete the consecutive-upgrade acceptance of a real-numbered CI release. See [INCREMENTAL.md](../../distribution/INCREMENTAL.md#production-service-apiturboismdev) for the current service contract, and [production deployment acceptance](production-service-validation.md) and [signing configuration acceptance](signing-provisioning-validation.md) for the measured state.

## User Goal

The user clicks "update" inside Cubism, the changed framework files download in the background, and modelling continues; the next launch uses the new framework. When plugin updates are checked, the compatible version of each selected plugin downloads as well.

The normal flow opens no browser, runs no installation wizard, and never force-closes Cubism.

This needs the website backend and distribution service to serve the resources, but not a rebuilt download page, a user login system, or a remote "install or restart Cubism" endpoint.

## Original Gap (Historical Record)

When this draft was written, the release chain still published complete EXE and ZIP files as its public download contract, and `distribution/worker.mjs` did not serve the incremental publishing or resource endpoints. Those endpoints now exist; the GitHub Release still keeps eight assets, and incremental resources ship over a separate R2 path.

Adding one button to the download page is not enough: the signed manifest and the matching immutable file payloads must publish first, and only then may the version query declare that the version supports an in-product incremental update.

## What the Website and API Must Do

### 1. Extend the Existing Version Query Instead of Inventing a Second Update Protocol

- [ ] Keep the existing `GET https://api.turboism.dev/v1/releases/{stable|beta|nightly}.json`.
- [ ] Add an optional `release.incremental` for a version whose incremental resources are published and verified.
- [ ] Return no such field for an older version or one without incremental resources, and keep supporting the complete installation package download.
- [ ] Keep the version, channel, buildNumber, sourceRevision, publish time, and the existing update notes and localized notes.
- [ ] `release.incremental` must match the version, channel, buildNumber, and sourceRevision of the outer release exactly.

It reuses the structure the client already has instead of inventing a new format:

```text
release.incremental = {
  source: {
    origin: "https://api.turboism.dev",
    redirects: "reject"
  },
  descriptor: {
    format: "turboism.incremental-candidate",
    schemaVersion: 1,
    repository: "turboism/Turboism",
    identity: { version, channel, buildNumber, sourceRevision },
    manifest: { bytes, sha256 },
    envelope: { bytes, sha256 },
    objectsSha256
  }
}
```

The block above describes the structure and is not publishable JSON. `descriptor` carries the raw values the release tool generates. The signing public key, an arbitrary download domain, and installation paths never come from this endpoint.

### 2. Serve the Signed Manifest and the File Payloads Publicly

Keep the same official domain and fix one prefix. For example:

```text
GET /v1/framework/documents/{sha256}
GET /v1/framework/objects/{sha256}/payload
```

`/v1/framework/` is already deployed as a production path and matches the fixed prefix of the client resource generator; resource publishing stays off. The relative `documents/...` and `objects/.../payload` paths are already fixed by the client implementation.

- [ ] `documents/{sha256}` returns the exact bytes of the raw manifest or of the standalone signed envelope.
- [ ] `objects/{sha256}/payload` returns the content of the single file the manifest references.
- [ ] A resource with the same SHA256 must never be overwritten with different content; the existing R2 and CDN infrastructure may store and distribute it directly.
- [ ] Serve the real content from `api.turboism.dev` and never send the client to GitHub, a proxy, or a temporary download domain through an HTTP redirect.
- [ ] Return a correct `Content-Length` and a stable strong ETag; never compress, transcode, or replace the resource bytes with an HTML error page.
- [ ] Support `Range` and `If-Range`, and answer a partial request with `206` and a correct `Content-Range`. On a version mismatch the server may answer a full `200` and the client downloads again.
- [ ] Answer a missing resource with an explicit `404` or another error status and never mix a success status with an error body.
- [ ] Expose only registered published resources and never turn the internal upload area of object storage into a public directory.

The client already compares and caches at file level, so the website never computes a diff from the user's local files and never receives the user's configuration, project, or plugin directory.

### 3. Wire the Release Pipeline So Files Are Ready Before the Version Is Announced

The release CI and the website backend do this together:

- [ ] Generate the complete framework file manifest from one traceable official build, recording the path, length, SHA256, version, channel, build number, and source SHA.
- [ ] Sign the manifest with a dedicated Ed25519 key inside the protected release CI; the website only stores and distributes the raw manifest and the signature and never holds the private key.
- [ ] Configure the public key the distribution side verifies with and keep it in sync with the framework update public key embedded in the client. Never treat the plugin catalog key as framework update authority by default.
- [ ] Announce `release.incremental` in the version endpoint only after every payload object, the manifest, and the signature are uploaded and their remote length and hash verified.
- [ ] On an upload failure or a publish conflict, never switch to a new version with missing files; keep the previously usable version and its published resources.
- [ ] Reuse the existing registry for controlled publication and channel switching; a separate user-facing authorization service is unnecessary.
- [ ] Explicitly change the current "GitHub Release holds exactly eight assets" and EXE/ZIP allowlist contract: decide how the new documents are delivered and whether payloads live only in R2, and update the publisher, the verifier, the documentation, and the tests together. Never bypass the old validation quietly and never open every asset unconditionally.

### 4. Plugins Keep the Existing Signed Catalog and Download Resources

- [ ] Keep the existing plugin signed catalog, its signature, and the plugin JAR download source reliably reachable.
- [ ] Keep the version, compatibility requirement, size, and hash in the catalog consistent with the real plugin file.
- [ ] When a new framework needs a new compatible plugin version, publish the plugin resources and catalog before announcing the framework update.

No plugin needs to enter the framework incremental manifest, and no separate "update all plugins" endpoint is needed. The client reuses its existing plugin update logic to decide which plugins the user selected, to download in the background, to stage, and to apply at the next launch; an unselected plugin and any third-party file stay untouched.

### 5. Keep the Existing Download Page as the Manual Recovery Entry

- [ ] The existing `turboism.dev/download` complete EXE and ZIP downloads stay available.
- [ ] The page takes no part in the normal in-product update and never asks the user to confirm each download in a browser.
- [ ] An old client without incremental update support can still upgrade through a complete installation package to a version with self-update.

## Client and Installer Responsibilities Outside the Website API

- Background download, progress, cancel, resume, and the selected plugin updates.
- Signature and hash verification plus the local file-level plan.
- Establishing the trusted installation receipt on the first complete install and advancing it after every update.
- Applying the update after the user closes Cubism themselves, and restoring the previous version when startup fails.
- Keeping the configuration, the projects, the unselected plugins, the third-party files, and the protected Cubism BAT integration.

The website neither owns nor directly operates an installation, an exit, or a restart on the user machine.

## Acceptance Checklist

- [ ] An old client and the complete installation package downloads are unaffected.
- [ ] A usable new version returns a correct `release.incremental`, and an incomplete publication returns none.
- [ ] The manifest, the signature, and every referenced object are readable directly from the official domain, and the real bytes match the manifest.
- [ ] An existing identical file needs no retransfer; a version that changes one file downloads only that file plus a little metadata.
- [ ] An interrupted download resumes through Range and ETag, and an abnormal response is never misread as success.
- [ ] With plugin updates checked, the client fetches the compatible selected plugins through the existing plugin catalog.
- [ ] An interrupted publication never breaks the currently usable version, and a problematic version can stop being recommended to new check requests.
- [ ] One complete end-to-end flow runs against the client: click update inside Cubism, download in the background, the user closes Cubism, the next launch applies it; then upgrade once more.

## Integration References

- `distribution/INCREMENTAL.md`: the existing offline admission protocol and discovery structure.
- `distribution/incremental.mjs`: the offline validation and publish admission logic, already wired into the production Worker, with release authority still off.
- `runtime/src/main/java/dev/turboism/update/UpdateIncrementalDiscovery.java`: the client discovery fields.
- `runtime/src/main/java/dev/turboism/update/FrameworkUpdateSource.java`: the fixed source and the relative resource paths.
- `scripts/release/turboism_release/framework_update.py`: the manifest and signature generator.
- `validation/issue-50-updater/ACTIVATION.md`: the client public key, the enabling configuration, and the first-install handoff.

Ship the minimal endpoints and one integration-ready version first, and do not make binary diffing, download statistics, a complex admin backend, or a user account system a prerequisite of this issue.
