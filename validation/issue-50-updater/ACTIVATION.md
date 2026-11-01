# Framework self-update activation contract (not activated)

Current rollout preparation is recorded in [activation-release-validation.md](activation-release-validation.md).
The user has authorized activation and a verified main release; the signing switch is now enabled, while actual
signed-package publication and the Windows upgrade acceptance remain pending. The 2026-11-01 provisioning details
below describe the earlier disabled configuration.

The normal runtime now constructs the installer adapter, but it remains UNAVAILABLE unless a reviewed release
bundles both a client activation policy and dedicated framework signing authority. Neither resource is taken from
an update feed, staged directory, settings file or unrelated classpath JAR. Development class directories do not
activate the product path.

## Bundled release resources

`META-INF/turboism/framework-update-client.json`:

```json
{"schemaVersion":1,"enabled":true,"endpoint":"https://api.turboism.dev/v1/framework/"}
```

The endpoint above is the implemented service prefix and the release resource generator's fixed endpoint. The
production deployment was upgraded to this implementation on 2026-11-01 with publication disabled; see the
[deployment validation](production-service-validation.md). It uses HTTPS on `api.turboism.dev`, without credentials,
port overrides, query, fragment or redirects. Its relative resources implement:

- `documents/<sha256>` for the exact signed manifest and detached envelope;
- `objects/<sha256>/payload` for immutable file content;
- bounded Content-Length, byte Range/ETag resume and the existing downloader integrity requirements.

`META-INF/turboism/framework-update-trusted-keys.json`:

```json
{"schemaVersion":1,"keys":{"framework-official-v1":"BASE64_ED25519_X509_SPKI"},"revoked":[]}
```

The placeholder is NOT a valid key and must never be shipped. Only public keys belong in the client; the protected
publisher retains private keys. Plugin catalog authority is separate. Key rotation must provision the next key in
existing clients before using it. Revoked keys cannot authorize new installation or old receipt authentication;
when trustworthy history is unavailable the updater refuses and requires reviewed full-install recovery.

The official keyring and the main-only `framework-release-signing` environment were provisioned on 2026-11-01.
The production API now trusts the same public key, while `FRAMEWORK_UPDATE_SIGNING_ENABLED=false` and both Worker
publisher identity settings remain empty. No production candidate has bundled the activation resources or run the
protected signing workflow; see the [signing provisioning validation](signing-provisioning-validation.md).

Both files must be added BEFORE assembling/signing the agent and before generating its framework manifest. Client
construction additionally requires a clean numbered CI identity and the actual standard-package agent location.
Local synthetic version labels from earlier host tests do not meet this production gate.

## First conventional full install

1. The protected release producer generates a complete signed framework manifest and envelope for the exact full
   package bytes (not config, plugins or Cubism files). Deliver these documents with the full installer/ZIP; a
   prebuilt installed receipt index cannot bind to a root that does not yet exist.
2. Before copying a new full package over an interrupted update, run its existing trusted recovery path while no
   Cubism process uses that installation. Do not let a stale transaction later revert a newly installed package.
3. After extraction, before first launch, invoke the bundled helper with a JDK 17+ runtime:

```text
java -cp <trusted-agent-or-helper-copy.jar> dev.turboism.update.FrameworkUpdateHelper
  --initialize-receipt --root <Turboism-home> --stage <directory-with-manifest.json-and-manifest.json.sig>
  --work <Turboism-home>/state/runtime/update/helper
```

4. Initialization verifies the independent signature, complete allowed layout and every actual installed file,
   then writes exact immutable receipt documents and a root-bound index. It does not alter payload, config or
   plugins. A different existing index or a modified payload fails closed. An identical receipt is idempotent.
5. Receipt directories must have the existing private owner/permission/ACL policy. The initializer creates missing
   private directories but never silently makes an existing insecure directory private. The installer must prepare
   its own state namespace deliberately without discarding user state; migration needs separate acceptance.
6. Full installer extraction/invocation and bootstrap-document packaging are wired through
   `packaging/windows-installer/bootstrap-receipt.ps1`, `configure_turboism.ps1 -InitializeReceipt`,
   `installer.nsi` (`BOOTSTRAP_RECEIPT_DIR`), and `assemble-release.sh` (`--bootstrap-receipt-dir`).
   Legacy packages lacking bootstrap documents preserve their original installation function without
   claiming self-update is available. Actual publication of signed bootstrap documents remains gated on
   the protected release signing authority and reviewed key provisioning.

## Normal incremental transaction

- Discovery must advertise an admitted incremental capability for the exact source/version/channel/build.
- The runtime reloads signed installed receipts on its IO worker; the core UI never supplies hashes or paths.
- Download and materialization authenticate exact documents and stream/rehash changed objects. Unchanged files are
  omitted from the stage and are independently rehashed by the helper before reuse.
- The coordinator persists next-launch intent and a retained helper copy. Production does not depend on a
  one-hour background waiter. Before starting Cubism, the controlled launcher runs that helper, which obtains its
  transaction lock, reauthenticates old receipt/full payload and re-verifies the target. Mutable installed-build.json
  is not old identity.
- Existing targets absent from old signed ownership cannot be overwritten. Old owned files absent from the new
  layout (e.g. versioned SDK/Graal-host JARs) are retired only when their old hashes still match. User additions are
  never inferred as retired files.
- The journal backs up payload, retired files, old installed-build.json and the old receipt index. Exact immutable
  new receipt documents are appended first; the index advances only after payload application, with installed-build
  written last. Failures or unconfirmed startup restore the old index and files together.
- Runtime health confirmation now reauthenticates the entire new receipt/layout as well as the loaded agent before
  discarding backups for this production transaction path.

## Windows process guard

Windows JDK 17 does not reliably provide ProcessHandle command lines. The helper therefore uses a bounded Windows
PowerShell 5.1 CIM query, with a Base64 path value, explicit UTF-8/text output and no user path interpolated as code.
It terminates only its own query subprocess on timeout. Query failure blocks apply/recovery rather than treating
missing data as "not running". This process scan is an additional guard. The current branch also publishes a
root/token/PID/start-bound LAUNCHING lease under the transaction lock before a managed spawn; the real agent premain
turns it into USING before runtime/plugin initialization. USING remains until the process has exited, including
after startup health. Child-JVM and built-agent premain probes plus local Cubism recovery/native-consent acceptance
are recorded in [the current Windows evidence](windows-launch-lease-host-validation.md). Legacy/direct launches
still have a pre-premain window, and old retained helpers without the token protocol fail closed rather than
migrating. Long-session, broken-new-launcher and full signed-release qualification remain blockers.

## Required before enabling

- Configure the first release's updater baseline and publisher credentials, then publish immutable resources and
  admit a real numbered release through the deployed API.
- Bundle the provisioned public keyring and reviewed activation resources; never ship local test keys or invent
  release identity. The main-only release workflows still require an agreed source release arrangement.
- Qualify the wired full-package bootstrap and migration from existing supported installations. Host testing fixed
  DACL preparation on owned Modify-only directories: it must not require re-setting ownership/WRITE_OWNER.
- Qualify the implemented durable next-launch handling and launcher/apply interlocking, including a broken new
  launcher, long sessions, multiple Cubism instances and restart/power-loss recovery.
- Qualify Windows writable-cache safety and metadata durability; file force/atomic rename alone is not proof of
  Windows power-loss safety or handle-based anti-TOCTOU safety.
- Validate UI download/cancel/resume and at least two successive real signed updates inside supported Windows
  Cubism. Keep Linux/Proton evidence separate.

Current production feeds still advertise only full packages. The service and its public key configuration have
been deployed and validated; publication and client activation remain disabled.
