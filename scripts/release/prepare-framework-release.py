#!/usr/bin/env python3
"""Sign, package and verify the framework self-update documents of one numbered build.

Run only from the protected release signing job: the private key arrives through the environment of a
single step and is never written into the workspace, a Gradle invocation, a log line or an artifact.
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from turboism_release import framework_release
from turboism_release.contracts import ReleaseError


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    sign = commands.add_parser(
        "sign", help="sign the audited payload ZIP into producer output"
    )
    sign.add_argument("--dist", required=True, type=Path)
    sign.add_argument(
        "--identity",
        required=True,
        type=Path,
        help="ledger build receipt (build-identity.json)",
    )
    sign.add_argument(
        "--inventory", required=True, type=Path, help="framework payload inventory"
    )
    sign.add_argument("--evidence", type=Path, help="stable candidate.json")
    sign.add_argument(
        "--prerelease-evidence", type=Path, help="beta prerelease-candidate.json"
    )
    sign.add_argument(
        "--client-trusted-keys",
        type=Path,
        help="optional cross-check: must equal the keyring shipped inside the agent JAR",
    )
    sign.add_argument("--key-id", required=True)
    sign.add_argument(
        "--private-key-env",
        required=True,
        help="environment variable holding the private key",
    )
    sign.add_argument("--minimum-updater", required=True)
    sign.add_argument("--output", required=True, type=Path)

    verify = commands.add_parser(
        "verify", help="prove the final package payload matches the signature"
    )
    verify.add_argument("--dist", required=True, type=Path)
    verify.add_argument("--documents", required=True, type=Path)
    verify.add_argument("--inventory", required=True, type=Path)
    verify.add_argument("--identity", required=True, type=Path)
    verify.add_argument(
        "--client-trusted-keys",
        type=Path,
        help="optional cross-check: must equal the keyring shipped inside the agent JAR",
    )
    verify.add_argument("--evidence-output", required=True, type=Path)

    refresh = commands.add_parser(
        "refresh-evidence", help="refresh only the candidate artifact digests"
    )
    refresh.add_argument("--source-root", required=True, type=Path)
    refresh.add_argument("--dist", required=True, type=Path)
    refresh.add_argument("--bundle", required=True, type=Path)
    refresh.add_argument("--version", required=True)
    refresh.add_argument(
        "--channel", required=True, choices=framework_release.SUPPORTED_CHANNELS
    )
    return root


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "sign":
            stock = framework_release.sign(
                dist=args.dist,
                identity_path=args.identity,
                inventory=args.inventory,
                evidence=args.evidence,
                prerelease_evidence=args.prerelease_evidence,
                client_trusted_keys=args.client_trusted_keys,
                key_id=args.key_id,
                private_key_env=args.private_key_env,
                minimum_updater=args.minimum_updater,
                output=args.output,
            )
            print(f"framework update manifest signed: {stock['manifest']}")
            print(f"framework update objects: {len(stock['objects'])}")
            return 0
        if args.command == "verify":
            evidence = framework_release.verify(
                dist=args.dist,
                documents=args.documents,
                inventory=args.inventory,
                identity_path=args.identity,
                client_trusted_keys=args.client_trusted_keys,
                evidence_output=args.evidence_output,
            )
            print(
                "final package verified: "
                f"version={evidence['identity']['version']} channel={evidence['identity']['channel']} "
                f"build={evidence['identity']['buildNumber']} entries={evidence['payload']['entries']} "
                f"keyId={evidence['keyId']} zipSha256={evidence['archive']['sha256']}"
            )
            return 0
        path = framework_release.refresh_evidence(
            source_root=args.source_root,
            dist=args.dist,
            bundle=args.bundle,
            version=args.version,
            channel=args.channel,
        )
        print(f"candidate evidence refreshed from the final package: {path.name}")
        return 0
    except ReleaseError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except zipfile.BadZipFile:
        print(
            "error: the release package is not a readable ZIP archive", file=sys.stderr
        )
        return 1
    except (OSError, KeyError, TypeError, ValueError):
        print(
            "error: the framework release handoff failed; inspect the job log for the failing step",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
