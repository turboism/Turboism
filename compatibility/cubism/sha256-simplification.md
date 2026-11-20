# SHA-256 check simplification

The reviewed facts in `reviewed-releases.json` are the single source for host
identities. `ReviewedHostArtifacts.java` is generated from that table, and
shell/Python tools read the JSON directly. Adding a reviewed host digest changes
one declaration instead of restating the same value in five or more consumers.
This does not make the entire version-onboarding procedure a one-file change.

The circular digest quality ratchet, `verification-record-pins.json` and
`updateVerificationPins` are retired. Repository record tests check schema,
identity, capability/alias relationships and mapping/profile consistency. Runtime
record admission no longer compares record bytes with their generated self-hash;
it checks manifest fields and host selector structure instead. A compatibility
contract carries the digest actually observed during probing to reject changes
between probing and resolver construction.

The remaining internal record digests are retired. `ReviewedSliceRecord`,
`SliceContract`, `EditorUiProviderAdmission.VerificationEvidence`, the generated
`*VerificationManifest` admission records and `CubismHostProbeCli` no longer carry
`recordSha256`; `CubismSliceCatalog.Candidate` no longer pins a record digest.
Draft packs no longer carry `exactArtifactSha256` or
`metadata.verificationRecordSha256`; `metadata.artifactSha256` alone names the
host artifact. `mapping-packs/draft/frozen.json` and its `check_frozen` gate are
deleted: Git history and review govern the retained evidence.

Only trust-boundary digests remain: the 21 host artifact SHA-256 values in
`reviewed-releases.json`, the reviewed Core API roster bindings, and the
dependency, signed-catalog and probe-evidence digests named above.

All 290 DRAFT packs were compared with base `9bfd3c58a`. The 283 changed packs
contain exactly the original data except for the approved changes: per-entry
`x.review` removal, per-file `exactArtifactSha256`, and removal of the two access
flag copies in `x.verification`. Access flags remain in verification selectors.
The packs shrink from 33,213,386 to 24,999,956 bytes: 8,213,430 bytes removed.
The generator, validators and import tests use the reduced representation.

`checkCompatibilityMatrix` inputs track the canonical release table and the Java tokens the generator reads,
plus previously omitted metadata dependencies. Unused update asset digests are
removed; security/release documentation distinguishes transfer integrity from
independent trust anchors.

The earlier audit overstated local packaging gates: the inspected `package-*.sh`
scripts emit checksum inventories rather than compare locally built JARs against
independent expected hashes. These inventories remain records. Performance probe
agent and fixture digests bind a report/rollback manifest to the actual run;
those checks remain to reject stale or mismatched evidence. Dependency downloads,
reviewed host/class bytes and signed plugin metadata retain their checks.

Validation results and any environment/baseline failures are recorded in the
implementation handoff rather than hidden by disabling repository gates.
