"""Generate the framework self-update *activation* resources for one agent build.

The release agent ships two bundled documents that decide whether the updater may run at all:

* ``framework-update-client.json`` -- the activation policy (enabled flag and the pinned endpoint);
* ``framework-update-trusted-keys.json`` -- the dedicated framework signing **public** keyring.

Both are consumed by ``FrameworkUpdateClientPolicy``/``FrameworkUpdateTrust`` inside the runtime, which
only ever read them from the defining runtime JAR (never from an update feed, a staged directory, a
settings file or an unrelated classpath JAR). This module therefore produces exactly the documents the
runtime parser accepts, and nothing else:

* only public material -- an Ed25519 X.509 SubjectPublicKeyInfo (44 bytes, fixed prefix); the protected
  publisher keeps private keys and this module never reads, writes or prints them;
* no network access at all -- the endpoint is a fixed reviewed constant;
* disabled is the default, and disabling removes **only** the two files this module owns from the
  generated directory, so an unconfigured build can never ship a stale activation resource.

The generated directory is passed to Gradle through ``TURBOISM_FRAMEWORK_UPDATE_RESOURCES``; see
``bootstrap/build.gradle.kts``.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import stat
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence
from urllib.parse import urlsplit

from .contracts import ReleaseError, canonical_bytes

# The reviewed activation contract (validation/issue-50-updater/ACTIVATION.md). This is the only endpoint
# this generator can emit, and it is validated against the runtime's own rules before writing.
ENDPOINT = "https://api.turboism.dev/v1/framework/"

CLIENT_RESOURCE = "framework-update-client.json"
KEYS_RESOURCE = "framework-update-trusted-keys.json"
RESOURCE_NAMES = (CLIENT_RESOURCE, KEYS_RESOURCE)

ENV_ENABLED = "FRAMEWORK_UPDATES_ENABLED"
ENV_KEYS = "FRAMEWORK_UPDATE_TRUSTED_KEYS"

SCHEMA_VERSION = 1
MAX_KEYS = 8
MAX_REVOKED = 32
MAX_KEY_ID = 64
# Ed25519 SubjectPublicKeyInfo: SEQUENCE(SEQUENCE(OID 1.3.101.112), BIT STRING(32 bytes)).
SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
SPKI_LENGTH = 44
MAX_KEYRING_BYTES = 16384
MAX_POLICY_BYTES = 4096
MAX_INPUT_BYTES = 1024 * 1024
_KEY_ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*\Z")
_ENDPOINT_PATH = re.compile(r"/(?:[a-z0-9-]+/)+\Z")


@dataclass(frozen=True)
class Prepared:
    """Outcome of one preparation, without any key material."""

    enabled: bool
    written: tuple[Path, ...]
    removed: tuple[Path, ...]


def enabled_from_environment(environ: Mapping[str, str]) -> bool:
    """Strict flag: unset/absent means disabled; only ``true``/``false`` are accepted."""
    value = environ.get(ENV_ENABLED)
    if value is None or value == "":
        return False
    if value == "true":
        return True
    if value == "false":
        return False
    raise ReleaseError(f"{ENV_ENABLED} must be exactly 'true', 'false' or unset")


def client_policy() -> dict:
    """The activation policy document for an enabled build."""
    endpoint = _validated_endpoint(ENDPOINT)
    return {"schemaVersion": SCHEMA_VERSION, "enabled": True, "endpoint": endpoint}


def trusted_keyring(raw: str | None) -> dict:
    """Validate the provisioned public keyring and return it in canonical form.

    ``raw`` is the JSON object from ``FRAMEWORK_UPDATE_TRUSTED_KEYS``. It must contain the public
    Ed25519 SPKI of every usable framework signing key plus the revoked key ids; anything else
    (private key material, unknown fields, an unusable or empty key set) is rejected.
    """
    if raw is None or raw == "":
        raise ReleaseError(f"{ENV_KEYS} is required when {ENV_ENABLED}=true")
    if len(raw.encode("utf-8")) > MAX_INPUT_BYTES:
        raise ReleaseError("trusted keyring input is too large")
    document = _strict_json(raw, "trusted keyring")
    if not isinstance(document, dict) or set(document) != {"schemaVersion", "keys", "revoked"}:
        raise ReleaseError("trusted keyring must be an object with schemaVersion, keys and revoked")
    if not _is_int(document["schemaVersion"]) or document["schemaVersion"] != SCHEMA_VERSION:
        raise ReleaseError("trusted keyring schemaVersion must be 1")
    keys = document["keys"]
    if not isinstance(keys, dict) or not keys:
        raise ReleaseError("trusted keyring must provision at least one public key")
    if len(keys) > MAX_KEYS:
        raise ReleaseError(f"trusted keyring allows at most {MAX_KEYS} keys")
    validated: dict[str, str] = {}
    for key_id, encoded in keys.items():
        _require_key_id(key_id)
        if not isinstance(encoded, str):
            raise ReleaseError(f"trusted keyring entry {key_id} must be a base64 string")
        validated[key_id] = _validated_spki(key_id, encoded)
    revoked = document["revoked"]
    if not isinstance(revoked, list) or len(revoked) > MAX_REVOKED:
        raise ReleaseError(f"trusted keyring revoked must be a list of at most {MAX_REVOKED} ids")
    revoked_ids: list[str] = []
    for key_id in revoked:
        if not isinstance(key_id, str):
            raise ReleaseError("trusted keyring revoked entries must be key ids")
        _require_key_id(key_id)
        if key_id in revoked_ids:
            raise ReleaseError(f"trusted keyring revoked lists {key_id} twice")
        revoked_ids.append(key_id)
    if all(key_id in revoked_ids for key_id in validated):
        raise ReleaseError("trusted keyring has no usable (non-revoked) public key")
    keyring = {
        "schemaVersion": SCHEMA_VERSION,
        "keys": dict(sorted(validated.items())),
        "revoked": sorted(revoked_ids),
    }
    if len(canonical_bytes(keyring)) > MAX_KEYRING_BYTES:
        raise ReleaseError("trusted keyring exceeds the runtime document limit")
    return keyring


def active_keys(raw: str | None) -> dict[str, str]:
    """Bare ``{keyId: base64 SPKI}`` mapping of the non-revoked keys.

    This is the schema the protected signing producer reads for ``trusted-keys`` (a plain object of key id to
    public key, no ``schemaVersion``/``revoked`` wrapper), so the signing lane can validate the provisioned
    document once and export exactly the keys that may still authorize an installation.
    """
    keyring = trusted_keyring(raw)
    revoked = set(keyring["revoked"])
    return {key_id: value for key_id, value in keyring["keys"].items() if key_id not in revoked}


def prepare(output_dir: Path, environ: Mapping[str, str]) -> Prepared:
    """Write the activation resources into ``output_dir`` or remove this module's own stale copies.

    Only ``framework-update-client.json`` and ``framework-update-trusted-keys.json`` are ever written or
    deleted; the directory itself and every other file in it are left untouched.
    """
    target = _checked_directory(output_dir)
    enabled = enabled_from_environment(environ)
    if not enabled:
        removed = []
        for name in RESOURCE_NAMES:
            path = _owned_file(target, name)
            if path is None:
                continue
            path.unlink()
            removed.append(path)
        return Prepared(enabled=False, written=(), removed=tuple(removed))

    keyring = trusted_keyring(environ.get(ENV_KEYS))
    documents = {
        CLIENT_RESOURCE: canonical_bytes(client_policy()),
        KEYS_RESOURCE: canonical_bytes(keyring),
    }
    written = []
    for name in RESOURCE_NAMES:
        path = _owned_file(target, name)
        if path is None:
            path = target / name
        _write_atomic(path, documents[name])
        written.append(path)
    return Prepared(enabled=True, written=tuple(written), removed=())


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate the framework self-update activation resources (public material only)."
    )
    parser.add_argument(
        "--output-dir",
        required=True,
        type=Path,
        help="generated directory handed to Gradle as TURBOISM_FRAMEWORK_UPDATE_RESOURCES",
    )
    arguments = parser.parse_args(argv)
    try:
        prepared = prepare(arguments.output_dir, os.environ)
    except ReleaseError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"error: {error.strerror or error}", file=sys.stderr)
        return 2
    state = "enabled" if prepared.enabled else "disabled"
    print(f"framework-update client resources: {state} (wrote {len(prepared.written)}, removed {len(prepared.removed)})")
    return 0


def _checked_directory(output_dir: Path) -> Path:
    target = Path(output_dir).absolute()
    if ".." in Path(output_dir).parts:
        raise ReleaseError("output directory must be normalized")
    attributes = _lstat(target)
    if attributes is None:
        target.mkdir(parents=True)
    elif not stat.S_ISDIR(attributes.st_mode) or _is_link(attributes):
        raise ReleaseError("output directory is not a plain directory")
    return target


def _owned_file(directory: Path, name: str) -> Path | None:
    """Existing regular file this module owns, or ``None`` when it does not exist yet."""
    path = directory / name
    attributes = _lstat(path)
    if attributes is None:
        return None
    if not stat.S_ISREG(attributes.st_mode) or _is_link(attributes):
        raise ReleaseError(f"{name} is not a plain file; refusing to replace or delete it")
    return path


def _write_atomic(path: Path, document: bytes) -> None:
    payload = document + b"\n"
    limit = MAX_POLICY_BYTES if path.name == CLIENT_RESOURCE else MAX_KEYRING_BYTES
    if len(payload) > limit:
        raise ReleaseError(f"{path.name} exceeds the runtime document limit")
    handle, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".writing", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _strict_json(raw: str, label: str) -> object:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ReleaseError(f"{label} repeats the field {key}")
            result[key] = value
        return result

    try:
        return json.loads(raw, object_pairs_hook=unique)
    except ReleaseError:
        raise
    except (json.JSONDecodeError, RecursionError) as error:
        raise ReleaseError(f"{label} is not valid JSON: {error}") from None


def _validated_spki(key_id: str, encoded: str) -> str:
    if not isinstance(encoded, str) or len(encoded) > 128 or len(encoded) % 4 != 0:
        raise ReleaseError(f"trusted keyring entry {key_id} is not a base64 Ed25519 SPKI")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise ReleaseError(f"trusted keyring entry {key_id} is not valid base64") from None
    if len(raw) != SPKI_LENGTH or not raw.startswith(SPKI_PREFIX):
        raise ReleaseError(f"trusted keyring entry {key_id} is not an Ed25519 X.509 SPKI public key")
    if base64.b64encode(raw).decode("ascii") != encoded:
        raise ReleaseError(f"trusted keyring entry {key_id} is not canonical base64")
    return encoded


def _require_key_id(key_id: object) -> None:
    if not isinstance(key_id, str) or not key_id or len(key_id) > MAX_KEY_ID or not _KEY_ID.match(key_id):
        raise ReleaseError(f"invalid framework key id: {key_id!r}")


def _validated_endpoint(endpoint: str) -> str:
    """Mirror FrameworkUpdateSource.configured: https, official host, no port/user/query/fragment."""
    parts = urlsplit(endpoint)
    try:
        port = parts.port
    except ValueError:
        raise ReleaseError("activation endpoint has an invalid port") from None
    if (
        parts.scheme != "https"
        or parts.hostname != "api.turboism.dev"
        or port is not None
        or parts.username is not None
        or parts.password is not None
        or parts.query
        or parts.fragment
        or not _ENDPOINT_PATH.match(parts.path)
    ):
        raise ReleaseError("activation endpoint is not the pinned official framework endpoint")
    return endpoint


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _lstat(path: Path) -> os.stat_result | None:
    try:
        return path.lstat()
    except FileNotFoundError:
        return None


def _is_link(attributes: os.stat_result) -> bool:
    return stat.S_ISLNK(attributes.st_mode) or bool(getattr(attributes, "st_file_attributes", 0) & 0x400)
