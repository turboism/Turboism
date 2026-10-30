"""Offline Windows build-output signing. No scanning-based ownership or network operations.

Trust inputs are the candidate, ledger receipt, and build-task inventory from the same
reviewed CI build, plus a locally selected public keyring. None is a local installation
receipt. Authentication of that build provenance belongs to the protected caller.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import stat
import subprocess
import tempfile
import zipfile
from pathlib import Path

from .channels import require_version
from .contracts import ReleaseError, canonical_bytes, validate_document

MAX_FILE = 512 * 1024 * 1024
MAX_DOCUMENT = 1024 * 1024
MAX_ENTRIES = 512
MAX_ARCHIVE = 1024 * 1024 * 1024
MAX_TOTAL = 1024 * 1024 * 1024
SIGNATURE_FORMAT = "turboism-update-manifest-sig-v1"
PUBLIC_PREFIX = bytes.fromhex("302a300506032b6570032100")
POLICY = Path(__file__).resolve().parents[3] / "packaging/framework-update-layout.json"
DEVICES = {"con", "prn", "aux", "nul"} | {f"{prefix}{n}" for prefix in ("com", "lpt") for n in range(1, 10)}


def checked_path(path: Path, *, missing: bool = False) -> os.stat_result | None:
    """Static no-link/alias checks; not a defense against concurrent parent replacement."""
    path = path.absolute()
    if ".." in path.parts:
        raise ReleaseError("Non-normalized local path")
    for part in [*path.parents][::-1] + [path]:
        try:
            attributes = part.lstat()
        except FileNotFoundError:
            if missing:
                return None
            raise ReleaseError("Required local input is missing") from None
        if stat.S_ISLNK(attributes.st_mode) or getattr(attributes, "st_file_attributes", 0) & 0x400:
            raise ReleaseError("Links/reparse points are not permitted")
        if part != path and not stat.S_ISDIR(attributes.st_mode):
            raise ReleaseError("Local ancestor is not a directory")
        if part == path:
            return attributes
    raise ReleaseError("Invalid local path")


def regular(path: Path, limit: int) -> os.stat_result:
    attributes = checked_path(path)
    if not stat.S_ISREG(attributes.st_mode) or attributes.st_nlink != 1 or attributes.st_size > limit:
        raise ReleaseError("Input must be a bounded regular file without hard-link aliases")
    return attributes


def read_bytes(path: Path, limit: int = MAX_DOCUMENT) -> bytes:
    before = regular(path, limit)
    with path.open("rb") as source:
        data = source.read(limit + 1)
        after = os.fstat(source.fileno())
    if len(data) > limit or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
            after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
        raise ReleaseError("Input changed while reading")
    return data


def strict_json(data: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ReleaseError("Duplicate JSON key")
            result[key] = value
        return result
    try:
        value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=unique,
                           parse_constant=lambda _: (_ for _ in ()).throw(ReleaseError("Invalid JSON number")))
    except (ValueError, UnicodeError, RecursionError) as failure:
        raise ReleaseError("Invalid bounded UTF-8 JSON document") from failure
    if not isinstance(value, dict):
        raise ReleaseError("JSON document must be an object")
    return value


def document(path: Path, limit: int = MAX_DOCUMENT) -> dict:
    return strict_json(read_bytes(path, limit))


def integer(value, maximum: int, minimum: int = 0) -> bool:
    return type(value) is int and minimum <= value <= maximum


def path_name(name: str) -> None:
    if not isinstance(name, str) or len(name) > 240 or ".." in name or not re.fullmatch(r"[A-Za-z0-9._/-]+", name):
        raise ReleaseError("Unsafe payload path")
    if any(p in ("", ".", "..") or p.endswith(".") or p.split(".", 1)[0].lower() in DEVICES
           for p in name.split("/")):
        raise ReleaseError("Unsafe payload path segment")


def sha_file(path: Path, limit: int = MAX_FILE) -> str:
    regular(path, limit)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        total = 0
        for block in iter(lambda: source.read(65536), b""):
            total += len(block)
            if total > limit:
                raise ReleaseError("File grew beyond bounds")
            digest.update(block)
    return digest.hexdigest()


def openssl(arguments: list[str], *, data: bytes | None = None) -> bytes:
    try:
        result = subprocess.run(["openssl", *arguments], input=data, capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        raise ReleaseError("OpenSSL operation unavailable") from None
    if result.returncode:
        # Do not propagate command diagnostics, paths or secret-bearing input.
        raise ReleaseError("OpenSSL key/signature operation failed")
    return result.stdout


def sign(manifest: bytes, key_id: str, private_key: Path, trusted_keys: Path, temporary: Path) -> bytes:
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", key_id) or len(key_id) > 64:
        raise ReleaseError("Invalid signing key ID")
    keyring = document(trusted_keys, 16384)
    if key_id not in keyring or not isinstance(keyring[key_id], str):
        raise ReleaseError("Unknown signing key ID")
    try:
        public = base64.b64decode(keyring[key_id], validate=True)
    except ValueError:
        raise ReleaseError("Invalid public signing material") from None
    if len(public) != 44 or not public.startswith(PUBLIC_PREFIX):
        raise ReleaseError("Trusted key must be Ed25519 X.509 DER")
    attributes = regular(private_key, 8192)
    if os.name == "posix" and stat.S_IMODE(attributes.st_mode) & 0o077:
        raise ReleaseError("Signing file must be private to its owner")
    secret = read_bytes(private_key, 8192)
    # -passin pass: prevents prompting; encrypted/unavailable keys fail closed in this local tool.
    derived = openssl(["pkey", "-passin", "pass:", "-pubout", "-outform", "DER"], data=secret)
    if derived != public:
        raise ReleaseError("Signing key does not match selected trusted public key")
    payload = temporary / "manifest.json"
    payload.write_bytes(manifest)
    key = temporary / "signing.pem"
    fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(secret)
    try:
        signature = openssl(["pkeyutl", "-sign", "-rawin", "-passin", "pass:", "-inkey", str(key), "-in", str(payload)])
    finally:
        key.unlink()
    if len(signature) != 64:
        raise ReleaseError("Invalid Ed25519 signature length")
    public_file = temporary / "public.der"
    public_file.write_bytes(public)
    signature_file = temporary / "signature.bin"
    signature_file.write_bytes(signature)
    openssl(["pkeyutl", "-verify", "-pubin", "-keyform", "DER", "-inkey", str(public_file),
             "-rawin", "-in", str(payload), "-sigfile", str(signature_file)])
    return canonical_bytes({"format": SIGNATURE_FORMAT, "schemaVersion": 1, "algorithm": "Ed25519",
                            "keyId": key_id, "manifestSha256": hashlib.sha256(manifest).hexdigest(),
                            "signature": base64.b64encode(signature).decode("ascii")})


def jar_identity(path: Path, identity: dict) -> None:
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ReleaseError("Agent contains duplicate archive entries")
        def text(name):
            info = jar.getinfo(name)
            if info.file_size > 16384:
                raise ReleaseError("Agent identity metadata too large")
            return jar.read(info).decode("utf-8", errors="strict")
        raw = text("META-INF/MANIFEST.MF").replace("\r\n ", "").replace("\r\n", "\n")
        fields = {}
        for line in raw.split("\n\n", 1)[0].splitlines():
            if ": " in line:
                key, value = line.split(": ", 1)
                if key in fields:
                    raise ReleaseError("Duplicate agent identity field")
                fields[key] = value
        expected = {"Implementation-Version": identity["version"], "Turboism-Version": identity["version"],
                    "Turboism-Channel": identity["channel"], "Turboism-Build-Number": str(identity["buildNumber"]),
                    "Turboism-Source-Revision": identity["sourceRevision"]}
        if any(fields.get(k) != v for k, v in expected.items()):
            raise ReleaseError("Agent build identity differs from candidate")
        properties = {}
        for line in text("META-INF/turboism/framework-version.properties").splitlines():
            if line and not line.startswith("#"):
                key, value = line.split("=", 1)
                if key in properties:
                    raise ReleaseError("Duplicate runtime identity property")
                properties[key] = value
        for key, value in {**identity, "buildNumber": str(identity["buildNumber"]), "buildKind": "ci", "dirty": "false"}.items():
            if properties.get(key) != value:
                raise ReleaseError("Embedded runtime identity differs from candidate")


def validate_inputs(candidate: Path, receipt: Path, inventory: Path, archive: Path, identity: dict):
    require_version(identity["version"], identity["channel"])
    if not identity["version"].isascii():
        raise ReleaseError("Release version must use ASCII digits")
    if any(int(x) > 2147483647 for x in identity["version"].split("-", 1)[0].split(".")):
        raise ReleaseError("Version components exceed Java protocol bounds")
    if not integer(identity["buildNumber"], 9007199254740991, 1) or not re.fullmatch(r"[0-9a-f]{40}", identity["sourceRevision"]):
        raise ReleaseError("Invalid expected build/source")
    candidate_doc = validate_document(document(candidate), "candidate")
    if type(candidate_doc["schemaVersion"]) is not int:
        raise ReleaseError("Invalid candidate schema type")
    receipt_doc = document(receipt, 8192)
    expected_receipt = {"schemaVersion", "version", "channel", "buildNumber", "sourceRevision", "runId", "runAttempt"}
    if set(receipt_doc) != expected_receipt or any(receipt_doc.get(k) != v for k, v in identity.items()):
        raise ReleaseError("Ledger receipt differs from expected identity")
    if not integer(receipt_doc["buildNumber"], 9007199254740991, 1) or type(receipt_doc["schemaVersion"]) is not int or receipt_doc["schemaVersion"] != 1 or not re.fullmatch(r"[1-9][0-9]{0,19}", str(receipt_doc["runId"])) or not integer(receipt_doc["runAttempt"], 2147483647, 1):
        raise ReleaseError("Invalid ledger receipt")
    source = candidate_doc.get("source", {})
    framework = candidate_doc.get("framework", {})
    if source.get("repository") != "turboism/Turboism" or source.get("revision") != identity["sourceRevision"] or framework.get("version") != identity["version"]:
        raise ReleaseError("Release candidate differs from expected source/version")
    if source.get("tag") not in (None, "v" + identity["version"]):
        raise ReleaseError("Candidate tag identity mismatch")
    archive_name = f"turboism-{identity['version']}.zip"
    artifacts = [a for a in framework.get("artifacts", []) if a.get("name") == archive_name]
    if len(artifacts) != 1 or archive.name != archive_name:
        raise ReleaseError("Exactly one standard Windows ZIP candidate is required")
    artifact = artifacts[0]
    attrs = regular(archive, MAX_ARCHIVE)
    if not integer(artifact.get("size"), MAX_ARCHIVE, 1) or attrs.st_size != artifact["size"] or sha_file(archive, MAX_ARCHIVE) != artifact.get("sha256"):
        raise ReleaseError("Candidate archive digest/size mismatch")
    policy = document(POLICY)
    stock = document(inventory)
    if set(stock) != {"format", "schemaVersion", "layout", "identity", "files", "excludedPaths"} or stock.get("format") != "turboism.framework-payload-inventory" or type(stock.get("schemaVersion")) is not int or stock["schemaVersion"] != 1 or stock.get("layout") != policy["layout"] or stock.get("identity") != identity or not integer(stock.get("identity", {}).get("buildNumber"), 9007199254740991, 1):
        raise ReleaseError("Build inventory identity/layout mismatch")
    files = stock["files"]
    if not isinstance(files, list) or not 1 <= len(files) <= MAX_ENTRIES:
        raise ReleaseError("Build inventory entry limit exceeded")
    owned = {}
    for item in files:
        if not isinstance(item, dict) or set(item) != {"relativePath", "bytes", "sha256"}:
            raise ReleaseError("Malformed build inventory entry")
        name = item["relativePath"]
        path_name(name)
        if name not in policy["frameworkRootFiles"] and not re.fullmatch(r"graal/lib/[A-Za-z0-9._-]+\.jar", name):
            raise ReleaseError("Inventory cannot authorize unknown framework paths")
        if name in owned or not integer(item["bytes"], MAX_FILE) or not isinstance(item["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]):
            raise ReleaseError("Invalid or duplicate build inventory entry")
        owned[name] = item
    if not set(policy["frameworkRootFiles"]) <= owned.keys() or not any(n.startswith("graal/lib/") for n in owned):
        raise ReleaseError("Incomplete standard framework payload inventory")
    # The SDK and Graal host are concrete build products; the resolved dependency closure is inventory-bound.
    # java-conventions adds a worktree classifier to Graal host jars; SDK opts out.
    host_pattern = rf"graal/lib/graal-host-{re.escape(identity['version'])}(?:-[A-Za-z0-9._-]+)?\.jar"
    if f"graal/lib/sdk-{identity['version']}.jar" not in owned or sum(bool(re.fullmatch(host_pattern, n)) for n in owned) != 1:
        raise ReleaseError("Inventory lacks unambiguous SDK/Graal host build products")
    excluded = stock["excludedPaths"]
    if not isinstance(excluded, list) or len(excluded) > MAX_ENTRIES or len(excluded) != len(set(excluded)):
        raise ReleaseError("Invalid excluded-path inventory")
    for name in excluded:
        path_name(name)
        if name not in policy["excludedStateFiles"] and not re.fullmatch(r"plugins/[a-z0-9-]+\.jar", name):
            raise ReleaseError("Unknown excluded file ownership")
    if not set(policy["excludedStateFiles"]) <= set(excluded) or set(excluded) & owned.keys():
        raise ReleaseError("Incomplete or overlapping exclusion inventory")
    return owned, sorted(excluded)


def unpack(archive: Path, owned: dict, excluded: list[str], temporary: Path, identity: dict):
    entries, sources = [], {}
    with zipfile.ZipFile(archive) as zipped:
        infos = zipped.infolist()
        if len(infos) > MAX_ENTRIES * 2 or sum(i.file_size for i in infos) > MAX_TOTAL:
            raise ReleaseError("Archive expanded size/entry limit exceeded")
        seen = set()
        for info in infos:
            path_name(info.filename)
            folded = info.filename.lower()
            mode = info.external_attr >> 16
            if folded in seen or info.is_dir() or stat.S_IFMT(mode) not in (0, stat.S_IFREG) or info.flag_bits & 1 or info.extra:
                raise ReleaseError("ZIP links, directories, encryption or aliases are not permitted")
            seen.add(folded)
            if info.filename not in owned and info.filename not in excluded:
                raise ReleaseError("Unknown archive file ownership")
            if not integer(info.file_size, MAX_FILE):
                raise ReleaseError("Archive file exceeds payload bounds")
        if {i.filename for i in infos} != owned.keys() | set(excluded):
            raise ReleaseError("Archive does not exactly match build inventory and exclusions")
        for name in sorted(owned):
            item = owned[name]
            if zipped.getinfo(name).file_size != item["bytes"]:
                raise ReleaseError("Archive payload size differs from build inventory")
            target = temporary / f"payload-{len(entries)}"
            digest = hashlib.sha256()
            total = 0
            with zipped.open(name) as source, target.open("xb") as destination:
                while block := source.read(65536):
                    total += len(block)
                    if total > item["bytes"]:
                        raise ReleaseError("Expanded payload grew beyond inventory")
                    digest.update(block)
                    destination.write(block)
            actual = digest.hexdigest()
            if total != item["bytes"] or actual != item["sha256"]:
                raise ReleaseError("Payload digest differs from trusted build inventory")
            content_id = "sha256:" + actual
            entries.append({**item, "contentId": content_id})
            sources[actual] = target
            if name == "turboism-agent.jar":
                jar_identity(target, identity)
    return entries, sources


def publish(output: Path, blobs: dict[str, Path], documents: dict[str, bytes]) -> None:
    """Preflight conflicts before writing. Reuse exact immutable bytes, never overwrite."""
    targets = {f"objects/{digest}/payload": (source.stat().st_size, digest, source)
               for digest, source in blobs.items()}
    targets.update({name: (len(data), hashlib.sha256(data).hexdigest(), data) for name, data in documents.items()})
    missing = []
    for name, (size, digest, source) in targets.items():
        target = output / name
        attrs = checked_path(target, missing=True)
        if attrs is None:
            missing.append((target, source))
        elif not stat.S_ISREG(attrs.st_mode) or attrs.st_nlink != 1 or attrs.st_size != size or sha_file(target, max(MAX_FILE, MAX_DOCUMENT)) != digest:
            raise ReleaseError("Immutable local output conflict")
    # Objects are installed first. Documents (manifest/envelope/inventory) never reference missing bytes.
    for target, source in missing:
        target.parent.mkdir(parents=True, exist_ok=True)
        checked_path(target.parent)
        with target.open("xb") as destination:
            if isinstance(source, bytes):
                destination.write(source)
            else:
                with source.open("rb") as payload:
                    for block in iter(lambda: payload.read(65536), b""):
                        destination.write(block)


def generate(*, candidate: Path, receipt: Path, inventory: Path, archive: Path, identity: dict,
             key_id: str, private_key: Path, trusted_keys: Path, minimum_updater: str, output: Path) -> dict:
    if len(minimum_updater) > 128 or not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?", minimum_updater):
        raise ReleaseError("Invalid minimum updater version")
    if any(int(x) > 2147483647 for x in minimum_updater.split("-", 1)[0].split("+", 1)[0].split(".")):
        raise ReleaseError("Minimum updater exceeds Java bounds")
    owned, excluded = validate_inputs(candidate, receipt, inventory, archive, identity)
    for path in (candidate, receipt, inventory, archive, private_key, trusted_keys):
        if path.absolute().is_relative_to(output.absolute()):
            raise ReleaseError("Output must not contain trusted inputs or signing material")
    with tempfile.TemporaryDirectory(prefix="turboism-update-sign-") as directory:
        temporary = Path(directory)
        entries, blobs = unpack(archive, owned, excluded, temporary, identity)
        manifest = canonical_bytes({"schemaVersion": 1, "keyId": key_id, **identity,
                                    "minimumUpdaterVersion": minimum_updater, "targetPlatform": "windows-x64",
                                    "entries": entries})
        if len(manifest) > MAX_DOCUMENT:
            raise ReleaseError("Manifest exceeds document limit")
        envelope = sign(manifest, key_id, private_key, trusted_keys, temporary)
        prefix = f"turboism-{identity['version']}"
        manifest_name = prefix + "-manifest.json"
        objects = [{"contentId": "sha256:" + digest, "sha256": digest, "bytes": path.stat().st_size,
                    "relativeObjectPath": f"objects/{digest}/payload"} for digest, path in sorted(blobs.items())]
        stock = {"format": "turboism.framework-update-objects", "schemaVersion": 1,
                 "layout": "windows-standard-zip", "identity": identity, "manifest": manifest_name,
                 "manifestSha256": hashlib.sha256(manifest).hexdigest(), "objects": objects,
                 "excludedPaths": excluded}
        # Versioned offline admission input. The service MUST recompute the object
        # closure from the authenticated manifest; this descriptor conveys no trust.
        closure = [{"sha256": digest, "bytes": path.stat().st_size} for digest, path in sorted(blobs.items())]
        admission = {"format": "turboism.incremental-candidate", "schemaVersion": 1,
                     "repository": "turboism/Turboism", "identity": identity,
                     "manifest": {"sha256": hashlib.sha256(manifest).hexdigest(), "bytes": len(manifest)},
                     "envelope": {"sha256": hashlib.sha256(envelope).hexdigest(), "bytes": len(envelope)},
                     "objectsSha256": hashlib.sha256(canonical_bytes(closure)).hexdigest()}
        publish(output, blobs, {manifest_name: manifest, manifest_name + ".sig": envelope,
                                prefix + "-objects.json": canonical_bytes(stock),
                                prefix + "-incremental.json": canonical_bytes(admission)})
        return stock


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("candidate", "receipt", "inventory", "archive", "private-key", "trusted-keys", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("version", "channel", "source-revision", "key-id", "minimum-updater"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--build-number", type=int, required=True)
    args = vars(parser.parse_args())
    identity = {"version": args.pop("version"), "channel": args.pop("channel"),
                "sourceRevision": args.pop("source_revision"), "buildNumber": args.pop("build_number")}
    try:
        generate(identity=identity, **args)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError):
        # Do not print input values, key data or subprocess diagnostics.
        print("Offline framework signing failed: invalid inputs, signing material or output conflict", file=__import__("sys").stderr)
        return 1
    print("Offline framework manifest, signature and object inventory generated")
    return 0
