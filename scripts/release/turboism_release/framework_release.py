"""Protected-release signing handoff for the framework self-update feed.

The offline producer (:mod:`turboism_release.framework_update`) signs exactly one standard ZIP whose
ownership set is the numbered build's payload inventory, and it refuses any archive entry it does not
own -- including ``bootstrap/manifest.json``. The shipped package must nevertheless carry those signed
bootstrap documents, so the release signs first and packages afterwards:

    payload ZIP_A --sign--> documents --package--> final ZIP_B (+ installer EXE)

The final package is then verified entry by entry: ``owned + excluded + bootstrap`` and nothing else.
The manifest therefore never references itself and never owns packaging metadata or mutable state.

Everything here reads the *shipped* activation resources from the agent JAR inside the package. A
candidate whose agent is not activated (missing client policy, disabled flag, foreign endpoint, or a
keyring that cannot authorize the signing key) is refused before any key material is touched. Nothing
here invents build identity: the ledger receipt and payload inventory come from the same numbered CI
build, and the private key only ever exists inside one signing step's environment.
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import re
import stat
import tempfile
import zipfile
from pathlib import Path

from .candidate import _load_script, developer_artifacts, framework_artifacts
from .channels import require_version
from .contracts import ReleaseError, canonical_bytes, read_document, write_document
from .framework_client import ENDPOINT, KEYS_RESOURCE, CLIENT_RESOURCE, trusted_keyring
from .framework_update import (
    MAX_ARCHIVE,
    MAX_DOCUMENT,
    MAX_ENTRIES,
    POLICY,
    SIGNATURE_FORMAT,
    document,
    integer,
    openssl,
    path_name,
    read_bytes,
    regular,
    sha_file,
    strict_json,
)

SCRIPTS = Path(__file__).resolve().parents[1]

EVIDENCE_FORMAT = "turboism.framework-bootstrap-evidence"
BOOTSTRAP_MANIFEST = "bootstrap/manifest.json"
BOOTSTRAP_ENVELOPE = "bootstrap/manifest.json.sig"
BOOTSTRAP_DOCUMENTS = (BOOTSTRAP_MANIFEST, BOOTSTRAP_ENVELOPE)
AGENT_MEMBER = "turboism-agent.jar"
MAX_AGENT = 64 * 1024 * 1024
RECEIPT_FIELDS = {
    "schemaVersion",
    "version",
    "channel",
    "buildNumber",
    "sourceRevision",
    "runId",
    "runAttempt",
}
IDENTITY_FIELDS = ("version", "channel", "buildNumber", "sourceRevision")
SUPPORTED_CHANNELS = ("stable", "beta")
KEY_ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*\Z")
PLUGIN_JAR = re.compile(r"plugins/[a-z0-9-]+\.jar\Z")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


# --------------------------------------------------------------------------- identity


def load_identity(receipt: Path) -> dict:
    """Read the allocated build receipt (ledger). Its values are never rewritten here."""
    values = document(Path(receipt), 8192)
    require(set(values) == RECEIPT_FIELDS, "build receipt has unexpected fields")
    require(
        type(values["schemaVersion"]) is int and values["schemaVersion"] == 1,
        "build receipt schemaVersion must be 1",
    )
    identity = {name: values[name] for name in IDENTITY_FIELDS}
    require(
        isinstance(identity["version"], str) and identity["version"].isascii(),
        "invalid receipt version",
    )
    require(
        identity["channel"] in ("stable", "beta", "nightly"), "invalid receipt channel"
    )
    require(
        integer(identity["buildNumber"], 9007199254740991, 1),
        "invalid receipt build number",
    )
    require(
        isinstance(identity["sourceRevision"], str)
        and re.fullmatch(r"[0-9a-f]{40}", identity["sourceRevision"]) is not None,
        "invalid receipt source revision",
    )
    require(
        re.fullmatch(r"[1-9][0-9]{0,19}", str(values["runId"])) is not None,
        "invalid receipt run id",
    )
    require(integer(values["runAttempt"], 2147483647, 1), "invalid receipt run attempt")
    return identity


def signing_identity(identity: dict) -> dict:
    """Only the channels the protected publisher may admit are signed (Stable and Beta)."""
    require(
        identity["channel"] in SUPPORTED_CHANNELS,
        "Nightly framework self-update is not supported; only Stable and Beta are signed",
    )
    require_version(identity["version"], identity["channel"])
    return identity


# --------------------------------------------------------------------------- shipped activation


def archive_for(dist: Path, version: str) -> Path:
    archive = Path(dist) / f"turboism-{version}.zip"
    require(
        archive.is_file() and not archive.is_symlink(),
        f"standard release ZIP is missing: {archive.name}",
    )
    return archive


def _agent_member(archive: Path) -> bytes:
    with zipfile.ZipFile(archive) as package:
        info = (
            package.getinfo(AGENT_MEMBER)
            if AGENT_MEMBER in package.namelist()
            else None
        )
        require(
            info is not None, "the release ZIP does not contain the framework agent"
        )
        require(
            info.file_size <= MAX_AGENT, "the framework agent exceeds the bounded size"
        )
        return package.read(info)


def shipped_activation(archive: Path) -> tuple[dict, dict]:
    """Read and validate the activation resources that ship inside the candidate's agent JAR.

    These are the only resources the runtime trusts, so the release refuses to sign a package whose
    shipped policy is missing, disabled, points at another endpoint, or whose keyring cannot authorize
    the signing key. This runs before any key material is read.
    """
    with zipfile.ZipFile(io.BytesIO(_agent_member(archive))) as agent:
        names = agent.namelist()
        for member in (CLIENT_RESOURCE, KEYS_RESOURCE):
            require(
                "META-INF/turboism/" + member in names,
                f"the shipped agent has no {member}; enable the framework update client resources first",
            )
        policy_bytes = agent.read("META-INF/turboism/" + CLIENT_RESOURCE)
        keyring_bytes = agent.read("META-INF/turboism/" + KEYS_RESOURCE)
    require(
        len(policy_bytes) <= 4096 and len(keyring_bytes) <= 16384,
        "shipped activation resources exceed the runtime limits",
    )
    policy = strict_json(policy_bytes)
    require(
        policy == {"schemaVersion": 1, "enabled": True, "endpoint": ENDPOINT},
        "shipped client policy must be enabled with the reviewed endpoint",
    )
    keyring = trusted_keyring(keyring_bytes.decode("utf-8", errors="strict"))
    return policy, keyring


def signing_keyring(keyring: dict, key_id: str) -> dict:
    require(
        isinstance(key_id, str) and KEY_ID.fullmatch(key_id) is not None,
        "invalid signing key id",
    )
    require(
        key_id not in keyring["revoked"],
        "signing key is revoked in the shipped client keyring",
    )
    require(
        key_id in keyring["keys"],
        "signing key is not provisioned in the shipped client keyring",
    )
    return {key_id: keyring["keys"][key_id]}


def require_matching_keyring(keyring: dict, provided: Path | None) -> None:
    """A caller-supplied keyring must be exactly the shipped one; the package is the authority."""
    if provided is None:
        return
    supplied = trusted_keyring(
        read_bytes(Path(provided), 16384).decode("utf-8", errors="strict")
    )
    require(
        canonical_bytes(supplied) == canonical_bytes(keyring),
        "provided client keyring differs from the one shipped inside the candidate agent",
    )


# --------------------------------------------------------------------------- candidate adaptation


def _artifact(entries, name):
    matches = [
        entry
        for entry in entries
        if isinstance(entry, dict) and entry.get("name") == name
    ]
    require(len(matches) == 1, f"audited evidence must describe exactly one {name}")
    return matches[0]


def adapt_candidate(
    archive: Path, identity: dict, evidence: Path | None, prerelease: Path | None
) -> dict:
    """Build the producer's candidate view from the audited candidate evidence of this build.

    Stable builds publish ``candidate.json``; Beta builds publish ``prerelease-candidate.json`` whose
    receipt must equal the ledger receipt. Either way only the artifact identity of the *original* ZIP
    is reused; no run/build identity is synthesized.
    """
    require(
        (evidence is None) != (prerelease is None),
        "exactly one of the candidate evidence inputs is required",
    )
    archive = Path(archive)
    size = regular(archive, MAX_ARCHIVE).st_size
    digest = sha_file(archive, MAX_ARCHIVE)
    tag = None
    if evidence is not None:
        candidate = document(Path(evidence))
        source = candidate.get("source") or {}
        framework = candidate.get("framework") or {}
        require(
            source.get("repository") == "turboism/Turboism",
            "candidate source repository mismatch",
        )
        require(
            source.get("revision") == identity["sourceRevision"],
            "candidate source revision mismatch",
        )
        require(
            framework.get("version") == identity["version"],
            "candidate framework version mismatch",
        )
        existing_tag = source.get("tag")
        require(
            existing_tag in (None, "v" + identity["version"]),
            "candidate tag identity mismatch",
        )
        tag = existing_tag
        artifacts = framework.get("artifacts")
    else:
        prerelease_document = strict_json(read_bytes(Path(prerelease), 128 * 1024))
        require(
            prerelease_document.get("type") == "turboism.prerelease-candidate",
            "unexpected prerelease candidate type",
        )
        receipt = prerelease_document.get("receipt") or {}
        require(
            all(receipt.get(name) == value for name, value in identity.items()),
            "prerelease candidate receipt differs from the ledger receipt",
        )
        manifest = prerelease_document.get("manifest") or {}
        require(
            manifest.get("version") == identity["version"],
            "prerelease manifest version mismatch",
        )
        require(
            manifest.get("format") == "turboism.framework-artifacts",
            "prerelease manifest format differs from the audited artifact manifest",
        )
        artifacts = manifest.get("artifacts")
    require(isinstance(artifacts, list), "audited evidence has no artifact list")
    artifact = _artifact(artifacts, archive.name)
    require(
        integer(artifact.get("size"), MAX_ARCHIVE, 1), "audited ZIP size is invalid"
    )
    require(
        isinstance(artifact.get("sha256"), str)
        and re.fullmatch(r"[a-f0-9]{64}", artifact["sha256"]) is not None,
        "audited ZIP digest is invalid",
    )
    require(
        artifact["size"] == size, "ZIP size differs from the audited candidate evidence"
    )
    require(
        artifact["sha256"] == digest,
        "ZIP bytes differ from the audited candidate evidence",
    )
    return {
        "format": "turboism.release-candidate",
        "schemaVersion": 1,
        "source": {
            "repository": "turboism/Turboism",
            "revision": identity["sourceRevision"],
            "tag": tag,
        },
        "framework": {
            "version": identity["version"],
            "artifacts": [{"name": archive.name, "size": size, "sha256": digest}],
        },
    }


# --------------------------------------------------------------------------- signing


def private_key_material(variable: str) -> bytes:
    """Read one unencrypted Ed25519 private key from the step environment (base64 PEM or PEM)."""
    require(
        isinstance(variable, str) and variable.strip(),
        "a signing key environment variable is required",
    )
    value = os.environ.get(variable)
    require(value is not None and value.strip(), f"{variable} is not set for this step")
    text = value.strip()
    try:
        decoded = base64.b64decode(text, validate=True)
    except (ValueError, TypeError):
        decoded = b""
    if b"PRIVATE KEY" in decoded:
        text = decoded.decode("ascii", errors="strict")
    data = text.encode("ascii", errors="strict")
    require(len(data) <= 8192, "signing key material is too large")
    require(
        data.count(b"PRIVATE KEY") == 2,
        "signing key material is not one PEM private key",
    )
    return data


def sign(
    *,
    dist: Path,
    identity_path: Path,
    inventory: Path,
    evidence: Path | None,
    prerelease_evidence: Path | None,
    key_id: str,
    private_key_env: str,
    minimum_updater: str,
    output: Path,
    client_trusted_keys: Path | None = None,
) -> dict:
    """Sign the audited payload ZIP and emit the producer output directory."""
    identity = signing_identity(load_identity(Path(identity_path)))
    archive = archive_for(Path(dist), identity["version"])
    candidate = adapt_candidate(archive, identity, evidence, prerelease_evidence)
    _, shipped = shipped_activation(archive)
    require_matching_keyring(shipped, client_trusted_keys)
    flat = signing_keyring(shipped, key_id)
    secret = private_key_material(private_key_env)
    output = Path(output).absolute()
    with tempfile.TemporaryDirectory(prefix="turboism-framework-release-") as directory:
        temporary = Path(directory)
        candidate_path = temporary / "candidate.json"
        candidate_path.write_bytes(canonical_bytes(candidate))
        keyring_path = temporary / "trusted-keys.json"
        keyring_path.write_bytes(canonical_bytes(flat))
        key_path = temporary / "signing.pem"
        descriptor = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(secret)
        try:
            stock = _generate(
                candidate_path,
                identity_path,
                inventory,
                archive,
                identity,
                key_id,
                key_path,
                keyring_path,
                minimum_updater,
                output,
            )
        finally:
            key_path.unlink()
            secret = b""
    return stock


def _generate(
    candidate,
    identity_path,
    inventory,
    archive,
    identity,
    key_id,
    key_path,
    keyring_path,
    minimum_updater,
    output,
):
    from . import framework_update

    return framework_update.generate(
        candidate=Path(candidate),
        receipt=Path(identity_path),
        inventory=Path(inventory),
        archive=Path(archive),
        identity=identity,
        key_id=key_id,
        private_key=Path(key_path),
        trusted_keys=Path(keyring_path),
        minimum_updater=minimum_updater,
        output=Path(output),
    )


# --------------------------------------------------------------------------- final verification


def documents_for(documents: Path, version: str) -> tuple[bytes, bytes]:
    prefix = f"turboism-{version}"
    root = Path(documents)
    manifest = read_bytes(root / f"{prefix}-manifest.json", MAX_DOCUMENT)
    envelope = read_bytes(root / f"{prefix}-manifest.json.sig", MAX_DOCUMENT)
    return manifest, envelope


def verify_signature(manifest: bytes, envelope: dict, public_spki: str) -> None:
    signature = envelope.get("signature")
    require(isinstance(signature, str), "signature envelope has no signature")
    try:
        raw_signature = base64.b64decode(signature, validate=True)
        public = base64.b64decode(public_spki, validate=True)
    except ValueError:
        raise ReleaseError("signature envelope carries invalid base64") from None
    require(len(raw_signature) == 64, "signature must be 64 bytes")
    require(
        public.startswith(bytes.fromhex("302a300506032b6570032100"))
        and len(public) == 44,
        "trusted key is not an Ed25519 X.509 SubjectPublicKeyInfo",
    )
    with tempfile.TemporaryDirectory(prefix="turboism-framework-verify-") as directory:
        temporary = Path(directory)
        (temporary / "manifest.json").write_bytes(manifest)
        (temporary / "signature.bin").write_bytes(raw_signature)
        (temporary / "public.der").write_bytes(public)
        openssl(
            [
                "pkeyutl",
                "-verify",
                "-pubin",
                "-keyform",
                "DER",
                "-inkey",
                str(temporary / "public.der"),
                "-rawin",
                "-in",
                str(temporary / "manifest.json"),
                "-sigfile",
                str(temporary / "signature.bin"),
            ]
        )


def _verify_envelope(envelope: dict, manifest: bytes) -> str:
    require(
        set(envelope)
        == {
            "format",
            "schemaVersion",
            "algorithm",
            "keyId",
            "manifestSha256",
            "signature",
        },
        "signature envelope has unexpected fields",
    )
    require(
        envelope["format"] == SIGNATURE_FORMAT, "signature envelope format mismatch"
    )
    require(
        type(envelope["schemaVersion"]) is int and envelope["schemaVersion"] == 1,
        "signature envelope schemaVersion must be 1",
    )
    require(
        envelope["algorithm"] == "Ed25519",
        "signature envelope algorithm must be Ed25519",
    )
    require(
        isinstance(envelope["keyId"], str)
        and KEY_ID.fullmatch(envelope["keyId"]) is not None,
        "signature envelope key id is invalid",
    )
    require(
        envelope["manifestSha256"] == sha256_bytes(manifest),
        "signature envelope does not describe this manifest",
    )
    return envelope["keyId"]


def _verify_manifest(manifest: dict, identity: dict, key_id: str) -> dict:
    expected = {
        "schemaVersion",
        "keyId",
        "minimumUpdaterVersion",
        "targetPlatform",
        "entries",
        *IDENTITY_FIELDS,
    }
    require(set(manifest) == expected, "signed manifest has unexpected fields")
    require(
        type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1,
        "signed manifest schemaVersion must be 1",
    )
    require(
        manifest["keyId"] == key_id, "signed manifest key id differs from its envelope"
    )
    for name in IDENTITY_FIELDS:
        require(
            manifest[name] == identity[name],
            f"signed manifest {name} differs from the build receipt",
        )
    require(
        manifest["targetPlatform"] == "windows-x64",
        "signed manifest target platform mismatch",
    )
    require(
        isinstance(manifest["minimumUpdaterVersion"], str)
        and manifest["minimumUpdaterVersion"],
        "signed manifest minimum updater version is missing",
    )
    entries = manifest["entries"]
    require(
        isinstance(entries, list) and 1 <= len(entries) <= MAX_ENTRIES,
        "signed manifest entry count is invalid",
    )
    owned = {}
    for entry in entries:
        require(
            isinstance(entry, dict)
            and set(entry) == {"relativePath", "bytes", "sha256", "contentId"},
            "signed manifest entry has unexpected fields",
        )
        name = entry["relativePath"]
        path_name(name)
        require(
            not name.startswith("bootstrap/"),
            "signed manifest must not own packaging metadata",
        )
        require(name not in owned, "signed manifest repeats a payload path")
        require(
            integer(entry["bytes"], 512 * 1024 * 1024),
            "signed manifest entry size is invalid",
        )
        require(
            isinstance(entry["sha256"], str)
            and re.fullmatch(r"[a-f0-9]{64}", entry["sha256"]) is not None,
            "signed manifest entry digest is invalid",
        )
        require(
            entry["contentId"] == "sha256:" + entry["sha256"],
            "signed manifest contentId mismatch",
        )
        owned[name] = entry
    return owned


def _verify_inventory(inventory: dict, identity: dict) -> tuple[dict, set[str]]:
    policy = document(POLICY)
    require(
        inventory.get("format") == "turboism.framework-payload-inventory"
        and type(inventory.get("schemaVersion")) is int
        and inventory["schemaVersion"] == 1,
        "build inventory contract mismatch",
    )
    require(
        inventory.get("layout") == policy["layout"], "build inventory layout mismatch"
    )
    require(
        inventory.get("identity") == identity,
        "build inventory identity differs from the build receipt",
    )
    files = inventory.get("files")
    require(
        isinstance(files, list) and 1 <= len(files) <= MAX_ENTRIES,
        "build inventory lists no payload files",
    )
    owned = {}
    for item in files:
        require(
            isinstance(item, dict) and set(item) == {"relativePath", "bytes", "sha256"},
            "build inventory entry has unexpected fields",
        )
        owned[item["relativePath"]] = item
    excluded = inventory.get("excludedPaths")
    require(
        isinstance(excluded, list)
        and len(excluded) <= MAX_ENTRIES
        and len(excluded) == len(set(excluded)),
        "invalid excluded-path inventory",
    )
    allowed = set(policy["excludedStateFiles"]) | {
        name for name in excluded if PLUGIN_JAR.fullmatch(name)
    }
    for name in excluded:
        path_name(name)
        require(name in allowed, "build inventory excludes an unknown path")
    require(
        set(policy["excludedStateFiles"]) <= set(excluded),
        "build inventory omits required state exclusions",
    )
    require(
        not set(excluded) & set(owned),
        "build inventory exclusions overlap payload ownership",
    )
    return owned, set(excluded)


def verify(
    *,
    dist: Path,
    documents: Path,
    inventory: Path,
    identity_path: Path,
    evidence_output: Path,
    client_trusted_keys: Path | None = None,
) -> dict:
    """Prove the final package is exactly the signed payload, its exclusions and the signed documents."""
    identity = signing_identity(load_identity(Path(identity_path)))
    version = identity["version"]
    archive = archive_for(Path(dist), version)
    _, shipped = shipped_activation(archive)
    require_matching_keyring(shipped, client_trusted_keys)
    manifest_bytes, envelope_bytes = documents_for(Path(documents), version)
    envelope = strict_json(envelope_bytes)
    key_id = _verify_envelope(envelope, manifest_bytes)
    signing_keyring(shipped, key_id)
    verify_signature(manifest_bytes, envelope, shipped["keys"][key_id])
    manifest = _verify_manifest(strict_json(manifest_bytes), identity, key_id)
    owned, excluded = _verify_inventory(
        document(Path(inventory), 2 * MAX_DOCUMENT), identity
    )
    require(
        set(owned) == set(manifest),
        "build inventory and signed manifest own different payload paths",
    )
    for name, entry in manifest.items():
        require(
            owned[name]["bytes"] == entry["bytes"]
            and owned[name]["sha256"] == entry["sha256"],
            f"build inventory disagrees with the signed manifest for {name}",
        )
    payload_digest = hashlib.sha256(
        canonical_bytes(
            [
                {
                    key: entry[key]
                    for key in ("relativePath", "bytes", "sha256", "contentId")
                }
                for entry in sorted(
                    manifest.values(), key=lambda item: item["relativePath"]
                )
            ]
        )
    ).hexdigest()
    with zipfile.ZipFile(archive) as archive_file:
        infos = archive_file.infolist()
        names = [info.filename for info in infos]
        require(len(names) == len(set(names)), "final ZIP contains duplicate entries")
        require(
            set(names) == set(manifest) | excluded | set(BOOTSTRAP_DOCUMENTS),
            "final ZIP entries are not exactly the signed payload, its exclusions and the bootstrap documents",
        )
        for info in infos:
            require(
                not info.is_dir()
                and stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG),
                "final ZIP must contain plain files only",
            )
        for name, entry in sorted(manifest.items()):
            info = archive_file.getinfo(name)
            require(
                info.file_size == entry["bytes"],
                f"final ZIP size differs from the signed manifest for {name}",
            )
            digest = hashlib.sha256()
            with archive_file.open(info) as payload:
                while block := payload.read(1024 * 1024):
                    digest.update(block)
            require(
                digest.hexdigest() == entry["sha256"],
                f"final ZIP bytes differ from the signed manifest for {name}",
            )
        require(
            archive_file.read(BOOTSTRAP_MANIFEST) == manifest_bytes,
            "final ZIP carries different signed manifest bytes",
        )
        require(
            archive_file.read(BOOTSTRAP_ENVELOPE) == envelope_bytes,
            "final ZIP carries different signature envelope bytes",
        )
    sidecar = archive.with_name(archive.name + ".sha256")
    require(
        sidecar.is_file() and not sidecar.is_symlink(),
        "final ZIP checksum sidecar is missing",
    )
    final_digest = sha_file(archive, MAX_ARCHIVE)
    require(
        sidecar.read_text(encoding="utf-8") == f"{final_digest}  {archive.name}\n",
        "final ZIP checksum sidecar does not describe the final bytes",
    )
    evidence = {
        "format": EVIDENCE_FORMAT,
        "schemaVersion": 1,
        "identity": identity,
        "keyId": key_id,
        "archive": {
            "name": archive.name,
            "bytes": regular(archive, MAX_ARCHIVE).st_size,
            "sha256": final_digest,
        },
        "manifest": {
            "name": f"turboism-{version}-manifest.json",
            "bytes": len(manifest_bytes),
            "sha256": sha256_bytes(manifest_bytes),
        },
        "envelope": {
            "name": f"turboism-{version}-manifest.json.sig",
            "bytes": len(envelope_bytes),
            "sha256": sha256_bytes(envelope_bytes),
        },
        "payload": {
            "entries": len(manifest),
            "excluded": len(excluded),
            "digest": payload_digest,
        },
        "bootstrapDocuments": list(BOOTSTRAP_DOCUMENTS),
    }
    output = Path(evidence_output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(canonical_bytes(evidence) + b"\n")
    return evidence


# --------------------------------------------------------------------------- candidate evidence refresh


def refresh_evidence(
    *, source_root: Path, dist: Path, bundle: Path, version: str, channel: str
) -> Path:
    """Refresh only the artifact digests of the audited candidate after the final package is built.

    The candidate structure, source binding, changelog binding and plugin selection stay exactly as the
    candidate build produced them; only the ZIP/EXE digests change because the documents are now
    embedded. The same helpers promotion uses are reused, so the two views cannot drift.
    """
    source_root, dist, bundle = (
        Path(source_root).resolve(),
        Path(dist).resolve(),
        Path(bundle).resolve(),
    )
    if channel == "stable":
        path = bundle / "candidate.json"
        candidate = read_document(path, "candidate")
        framework = candidate.get("framework")
        require(isinstance(framework, dict), "candidate has no framework section")
        framework["artifacts"] = framework_artifacts(source_root, dist, version)
        framework["developerArtifacts"] = developer_artifacts(dist, version)
        write_document(path, candidate, "candidate")
        return path
    require(channel == "beta", "Nightly framework self-update is not supported")
    path = bundle / "prerelease-candidate.json"
    require(
        path.is_file() and not path.is_symlink(),
        "prerelease candidate evidence is missing",
    )
    candidate = strict_json(read_bytes(path, 128 * 1024))
    verifier = _load_script(
        "framework_release_payload_verifier", SCRIPTS / "verify-release.py"
    )
    candidate["manifest"] = verifier.artifact_manifest(
        dist, version, source_root / "packaging/release-plugins.txt", channel=channel
    )
    path.write_text(
        json.dumps(candidate, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    return path
