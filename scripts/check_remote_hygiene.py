#!/usr/bin/env python3
"""Fail-closed remote-hygiene checker for the Turboism repository.

Policy: forbidden from remote history
  - environment-variable values and environment files;
  - local configuration/state;
  - AI instructions, prompts, transcripts, tool state;
  - agent/task/research artifacts and editor swap files.

Allowed:
  - ordinary program local-variable names;
  - source references such as System.getenv("APPDATA");
  - CI secret-name references such as ${{ secrets.NAME }};
  - policy/guard code that names forbidden path classes.

Modes (exactly one):
  --staged             check the index (files staged for commit)
  --outgoing FROM..TO  check every commit in the pushed range, including an
                       add-then-delete intermediate commit and merge results
                       against every parent
  --outgoing-stdin     pre-push hook mode: read '<local-ref> <local-sha>
                       <remote-ref> <remote-sha>' lines from stdin and check
                       each push range; malformed non-empty input fails closed
  --all                check all reachable history
  --install-hooks      write local untracked pre-commit / pre-push hooks that
                       invoke this checker and fail closed; refuses to
                       overwrite a pre-existing differing hook

Never prints forbidden file content or secret values. Violation lines are
'<path>: <rule>' and for secret-value signatures '<path>: value-signature=<rule>'
(rule name only, never the matched value). Any diagnostic or path is redacted
of high-confidence token matches before printing. Path and content rules are
case-insensitive; env templates are forbidden like every other .env.* file.
Exit codes: 0 clean, 1 violations, 2 error (fail closed). Stdlib only.
"""

import argparse
import os
import stat
import re
import subprocess
import sys
from pathlib import PurePosixPath

# --------------------------------------------------------------------------
# path rules (canonical names are lowercase; matching is case-insensitive)
# --------------------------------------------------------------------------

FORBIDDEN_SEGMENTS = frozenset({
    ".agent-artifacts", ".artifacts", ".research-artifacts", ".pi-subagents",
    ".claude", ".cursor", ".pi", ".windsurf", "prompts",
})

FORBIDDEN_PATHS = frozenset({
    "runtime/logs",
})

FORBIDDEN_BASENAMES = frozenset({
    "agents.md", "claude.md", "gemini.md", "copilot.md",
    ".agents.md.swp", ".env", ".envrc", "local.properties",
})

SWAP_SUFFIXES = (".swp", ".swo", ".swn", ".swx")
PROMPT_SUFFIX = ".prompt.md"
ZERO_SHA = "0" * 40

# --------------------------------------------------------------------------
# content rules: high-confidence secret-value signatures (name only on report)
# --------------------------------------------------------------------------

SECRET_RULES = (
    (re.compile(r"^-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----$", re.MULTILINE), "private-key-block"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36}\b"), "github-pat"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b"), "github-pat-fine"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "aws-access-key"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"), "slack-token"),
    (re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"), "openai-api-key"),
    (re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"), "google-api-key"),
)

QUOTE_ESCAPES = {b"a": b"\a", b"b": b"\b", b"f": b"\f", b"n": b"\n",
                 b"r": b"\r", b"t": b"\t", b"v": b"\v", b"\\": b"\\", b'"': b'"'}


def redact(text):
    """Replace high-confidence secret-shaped substrings with <redacted>.

    Unanchored on the left so a token embedded in a filename or prefix
    (e.g. 'zz_ghp_...log') is still redacted before any diagnostic echo.
    Over-redaction in diagnostics is safe. Accepts str or bytes (Git
    diagnostics) and never raises on bytes input."""
    if isinstance(text, bytes):
        text = text.decode("utf-8", errors="replace")
    for rx in (
        re.compile(r"gh[pousr]_[A-Za-z0-9]{36}"),
        re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}"),
        re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
        re.compile(r"AIza[0-9A-Za-z_-]{35}"),
        re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
    ):
        text = rx.sub("<redacted>", text)
    return text


def _unquote(path_token):
    """Undo git's C-style quoting of paths (used with the -z raw format)."""
    if not (path_token.startswith(b'"') and path_token.endswith(b'"')):
        return path_token
    out = bytearray()
    body = path_token[1:-1]
    i = 0
    while i < len(body):
        c = body[i:i + 1]
        if c == b"\\" and i + 1 < len(body):
            nxt = body[i + 1:i + 2]
            if nxt in QUOTE_ESCAPES:
                out += QUOTE_ESCAPES[nxt]
            elif nxt == b"x" and i + 3 < len(body):
                out.append(int(body[i + 2:i + 4], 16))
                i += 3
            elif nxt.isdigit() and i + 3 < len(body):
                out.append(int(body[i + 1:i + 4], 8))
                i += 3
            else:
                out += nxt
            i += 1
        else:
            out += c
        i += 1
    return bytes(out)


def classify_path(path):
    """Return a lowercase canonical rule name if the path is forbidden, else None."""
    parts = list(PurePosixPath(path).parts)
    lows = [p.lower() for p in parts]
    normalized = "/".join(lows)
    for forbidden in FORBIDDEN_PATHS:
        if normalized == forbidden or normalized.startswith(forbidden + "/"):
            return "path:" + forbidden
    for low in lows:
        if low in FORBIDDEN_SEGMENTS:
            return "segment:" + low
    base = parts[-1]
    low = base.lower()
    if low in FORBIDDEN_BASENAMES:
        return "basename:" + low
    if low == "copilot-instructions.md":
        return "basename:copilot-instructions.md"
    if low.startswith(".env."):
        return "basename:.env.*"
    if re.search(r"\.local\.", low):
        return "basename:*.local.*"
    if base.startswith(".#"):  # emacs lock names are case-sensitive literals
        return "basename:emacs-lock"
    if low.endswith("~") and low != "~":
        return "basename:editor-backup"
    if low.endswith(SWAP_SUFFIXES):
        return "basename:editor-swap"
    if low.startswith(".aider"):
        return "basename:.aider*"
    if low.endswith(PROMPT_SUFFIX):
        return "suffix:*.prompt.md"
    return None


def scan_content(data):
    """Secret-value signature rules over blob text; binaries are skipped."""
    if b"\x00" in data[:8192]:
        return []
    text = data.decode("utf-8", errors="replace")
    return [name for rx, name in SECRET_RULES if rx.search(text)]


# --------------------------------------------------------------------------
# git plumbing (deterministic: --no-renames, merge-aware -m, one helper)
# --------------------------------------------------------------------------

def _git_bytes(repo, args):
    return subprocess.run(["git"] + args, cwd=repo, capture_output=True,
                          check=True).stdout


def commit_list(repo, revs):
    """All commit SHAs in rev-list order for the given rev argument(s)."""
    out = _git_bytes(repo, ["rev-list", "--reverse"] + revs).split()
    return [s.decode("ascii") for s in out]


def changed_paths(repo, sha):
    """Added/modified paths in a commit, against every parent for merges.

    Pure deletions are cleanup and carry no new remote path or blob to reject.
    Rename detection stays disabled so a rename is represented as deletion plus
    addition and the new destination is checked normally."""
    raw = _git_bytes(repo, ["diff-tree", "-m", "--no-commit-id", "--root",
                            "--no-renames", "--raw", "-r", "-z", sha])
    toks = [t for t in raw.split(b"\x00") if t]
    results = []
    i = 0
    while i + 1 < len(toks):
        meta, path = toks[i], _unquote(toks[i + 1])
        i += 2
        if not meta.startswith(b":") or meta.startswith(b"::"):
            continue
        fields = meta.split()
        if len(fields) >= 5 and fields[4] in (b"A", b"M") and path:
            results.append(path.decode("utf-8", errors="replace"))
    return results


def changed_blobs(repo, sha, read_blob):
    """(path, rule) for secret signatures in added/modified blobs, scanning
    every parent of a merge so merge-resolution content is never skipped."""
    raw = _git_bytes(repo, ["diff-tree", "-m", "--no-commit-id", "--root",
                            "--no-renames", "--raw", "-r", "-z", sha])
    toks = [t for t in raw.split(b"\x00") if t]
    results = []
    i = 0
    while i + 1 < len(toks):
        meta, path = toks[i], _unquote(toks[i + 1])
        i += 2
        if not meta.startswith(b":") or meta.startswith(b"::"):
            continue
        fields = meta.split()
        # per-parent raw record: :mode oldmode oldsha newsha status
        if len(fields) < 5 or fields[4] not in (b"A", b"M"):
            continue
        new_sha = fields[3].decode("ascii")
        if new_sha == ZERO_SHA or not path:
            continue
        for rule in scan_content(read_blob(new_sha)):
            results.append((path.decode("utf-8", errors="replace"),
                            "value-signature=" + rule))
    return results


class BlobReader:
    def __init__(self, repo):
        self._p = subprocess.Popen(["git", "-C", repo, "cat-file", "--batch"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self._cache = {}

    def read(self, sha):
        if sha in self._cache:
            return self._cache[sha]
        self._p.stdin.write(sha.encode("ascii") + b"\n")
        self._p.stdin.flush()
        header = self._p.stdout.readline()
        parts = header.split()
        if len(parts) < 3 or parts[1] != b"blob":
            data = b""
        else:
            size = int(parts[2])
            data = self._p.stdout.read(size + 1)[:-1]
        self._cache[sha] = data
        return data

    def close(self):
        try:
            self._p.stdin.close()
            self._p.wait(timeout=10)
        except Exception:
            self._p.kill()


# --------------------------------------------------------------------------
# scan drivers
# --------------------------------------------------------------------------

def scan_staged(repo):
    violations = []
    reader = BlobReader(repo)
    try:
        raw = _git_bytes(repo, ["diff", "--cached", "--no-renames",
                                "--raw", "-z"])
        toks = [t for t in raw.split(b"\x00") if t]
        i = 0
        while i + 1 < len(toks):
            meta, path = toks[i], _unquote(toks[i + 1])
            i += 2
            if not meta.startswith(b":") or meta.startswith(b"::"):
                continue
            fields = meta.split()
            if len(fields) < 5:
                continue
            status = fields[4]
            decoded_path = path.decode("utf-8", errors="replace")
            if status in (b"A", b"M"):
                rule = classify_path(decoded_path)
                if rule:
                    violations.append((decoded_path, rule))
                new_sha = fields[3].decode("ascii")
                if new_sha != ZERO_SHA:
                    for rule in scan_content(reader.read(new_sha)):
                        violations.append((decoded_path, "value-signature=" + rule))
    finally:
        reader.close()
    return violations


def scan_commits(repo, shas):
    violations = []
    reader = BlobReader(repo)
    try:
        for sha in shas:
            for p in changed_paths(repo, sha):
                rule = classify_path(p)
                if rule:
                    violations.append((p, rule))
            for p, rule in changed_blobs(repo, sha, reader.read):
                violations.append((p, rule))
    finally:
        reader.close()
    return violations


def parse_push_lines(raw):
    """Parse pre-push stdin: '<local-ref> <local-sha> <remote-ref> <remote-sha>'.
    Any malformed non-empty line raises (fail closed). Base resolution is done
    separately by resolve_push_base (remote may be unavailable locally)."""
    entries = []
    for lineno, line in enumerate(raw.splitlines(), 1):
        parts = line.split()
        if len(parts) != 4:
            raise ValueError("malformed push line %d: got %d fields, need 4"
                             % (lineno, len(parts)))
        local_ref, local_sha, remote_ref, remote_sha = parts
        entries.append((local_ref, local_sha, remote_ref, remote_sha))
    return entries


def resolve_push_base(repo, local_sha, remote_sha):
    """Scan base for a pushed ref.

    - remote all-zero (new remote ref) -> None (scan the whole local branch);
    - remote commit unavailable locally (first history-rewrite push: the old
      remote SHA was sanitized away) or present but not an ancestor of the
      local tip (non-fast-forward) -> None (scan the whole local branch -
      strictly more coverage, never a skip);
    - remote available and an ancestor -> remote_sha (scan only remote..local).

    Unexpected Git errors raise (fail closed, redacted, no traceback); the
    expected missing-object (rev-parse --verify -q status 1) and the expected
    non-ancestor (merge-base --is-ancestor status 1) are handled."""
    if remote_sha == ZERO_SHA:
        return None
    probe = subprocess.run(
        ["git", "rev-parse", "--verify", "-q", remote_sha + "^{commit}"],
        cwd=repo, capture_output=True)
    if probe.returncode == 1:
        return None  # expected: object unavailable locally
    if probe.returncode != 0:
        raise subprocess.CalledProcessError(
            probe.returncode,
            ["git", "rev-parse", "--verify", "-q", remote_sha + "^{commit}"],
            output=probe.stdout, stderr=probe.stderr)
    ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", remote_sha, local_sha],
        cwd=repo, capture_output=True)
    if ancestor.returncode == 0:
        return remote_sha
    if ancestor.returncode == 1:
        return None  # expected: non-fast-forward
    raise subprocess.CalledProcessError(
        ancestor.returncode,
        ["git", "merge-base", "--is-ancestor", remote_sha, local_sha],
        output=ancestor.stdout, stderr=ancestor.stderr)


# --------------------------------------------------------------------------
# hooks
# --------------------------------------------------------------------------

PRE_COMMIT_HOOK = """#!/bin/sh
# Generated by scripts/check_remote_hygiene.py --install-hooks (untracked, local-only).
# Fail closed: if the checker is missing or errors, the commit is refused.
set -eu
repo="$(git rev-parse --show-toplevel)"
if [ ! -f "$repo/scripts/check_remote_hygiene.py" ]; then
  echo "remote-hygiene: checker missing; refusing commit (fail closed)" >&2
  exit 1
fi
exec python3 "$repo/scripts/check_remote_hygiene.py" --staged
"""

PRE_PUSH_HOOK = """#!/bin/sh
# Generated by scripts/check_remote_hygiene.py --install-hooks (untracked, local-only).
# Fail closed: if the checker is missing or errors, the push is refused.
set -eu
repo="$(git rev-parse --show-toplevel)"
if [ ! -f "$repo/scripts/check_remote_hygiene.py" ]; then
  echo "remote-hygiene: checker missing; refusing push (fail closed)" >&2
  exit 1
fi
exec python3 "$repo/scripts/check_remote_hygiene.py" --outgoing-stdin
"""


def install_hooks(repo):
    """Preflight-atomic hook installation.

    First inspect both target hooks completely: any existing hook that is a
    symlink or differs from the generated bytes fails closed (exit 2)
    before anything is written or chmodded. After the full preflight passes,
    byte-identical existing hooks get the user-execute bit (all other mode
    bits preserved) and missing hooks are created executable. File creation
    and chmod only happen once every target has been validated."""
    hooks_dir = _git_bytes(repo, ["rev-parse", "--git-path", "hooks"]).decode().strip()
    if not os.path.isabs(hooks_dir):
        hooks_dir = os.path.join(repo, hooks_dir)
    hooks_dir = os.path.abspath(hooks_dir)
    os.makedirs(hooks_dir, exist_ok=True)
    generated = {"pre-commit": PRE_COMMIT_HOOK, "pre-push": PRE_PUSH_HOOK}
    existing = {}
    for name, body in generated.items():
        path = os.path.join(hooks_dir, name)
        if os.path.lexists(path):
            if os.path.islink(path):
                raise RuntimeError(
                    "existing hook at %s is a symlink; refusing to touch it "
                    "(fail closed)" % path)
            with open(path, "rb") as fh:
                existing[name] = (path, fh.read())
    # preflight: every existing hook must be byte-identical
    for name, body in generated.items():
        if name in existing:
            path, data = existing[name]
            if data != body.encode("utf-8"):
                raise RuntimeError(
                    "existing hook at %s differs from the generated hook; "
                    "refusing to overwrite (fail closed)" % path)
    # apply (only after the whole preflight passed)
    installed = []
    for name, body in generated.items():
        path = os.path.join(hooks_dir, name)
        if name in existing:
            mode = os.stat(path).st_mode
            os.chmod(path, mode | stat.S_IXUSR)  # user-executable, other bits kept
        else:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(body)
            os.chmod(path, 0o755)
        installed.append(path)
    return installed


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def run(argv, repo=None):
    repo = repo or os.getcwd()
    parser = argparse.ArgumentParser(
        prog="check_remote_hygiene.py",
        description="Fail-closed remote-hygiene checker (stdlib only).")
    parser.add_argument("-C", "--repo", default=repo,
                        help="repository root to scan (default: cwd)")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--staged", action="store_true",
                       help="check staged (index) changes")
    modes.add_argument("--outgoing", metavar="FROM..TO", action="append", default=[],
                       help="check every commit in the pushed range")
    modes.add_argument("--outgoing-stdin", action="store_true",
                       help="read pre-push stdin push lines and check ranges")
    modes.add_argument("--all", action="store_true",
                       help="check all reachable history")
    modes.add_argument("--install-hooks", action="store_true",
                       help="write local untracked pre-commit/pre-push hooks")
    args = parser.parse_args(argv)

    try:
        if args.install_hooks:
            for path in install_hooks(args.repo):
                print("installed hook:", path)
            return 0

        if args.staged:
            violations = scan_staged(args.repo)
        elif args.outgoing:
            violations = scan_commits(args.repo, commit_list(args.repo, args.outgoing))
        elif args.outgoing_stdin:
            entries = parse_push_lines(sys.stdin.read())
            if not entries:
                print("remote-hygiene: no push refs on stdin; refusing (fail closed)",
                      file=sys.stderr)
                return 2
            violations = []
            for _local_ref, local_sha, _remote_ref, remote_sha in entries:
                base = resolve_push_base(args.repo, local_sha, remote_sha)
                if base is None:
                    ranges = [local_sha]  # new/unavailable/non-ff: whole branch
                else:
                    ranges = [base + ".." + local_sha]
                violations.extend(scan_commits(args.repo, commit_list(args.repo, ranges)))
        elif args.all:
            violations = scan_commits(
                args.repo, sorted(set(commit_list(args.repo, ["--all"])), key=lambda s: s))
    except subprocess.CalledProcessError as exc:
        print("remote-hygiene: git error (%s); refusing (fail closed)"
              % redact(exc.stderr or exc.stdout or str(exc)), file=sys.stderr)
        return 2
    except Exception as exc:  # noqa: BLE001 - fail closed on any checker error
        print("remote-hygiene: checker error: %s; refusing (fail closed)"
              % redact(str(exc)), file=sys.stderr)
        return 2

    if violations:
        for path, rule in sorted(set(violations)):
            print("%s: %s" % (redact(path), rule))
        return 1
    print("remote-hygiene: clean")
    return 0


def main():
    sys.exit(run(sys.argv[1:]))


if __name__ == "__main__":
    main()