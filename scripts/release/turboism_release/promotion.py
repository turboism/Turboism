"""Verify a candidate before creating an immutable GitHub release binding."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

from .candidate import _load_script, framework_artifacts
from .contracts import ReleaseError, read_document
from .versions import CHANGELOG_HEADING, SOURCE_SHA, STRICT_VERSION, compare_versions, framework_version, git_source

REPOSITORY = "turboism/Turboism"
WORKFLOW = ".github/workflows/release.yml"
SCRIPTS = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def validate_run(run, run_id, source_sha, attempt=None):
    require(re.fullmatch(r"[1-9][0-9]{0,19}", str(run_id)), "invalid candidate run id")
    require(SOURCE_SHA.fullmatch(source_sha), "invalid candidate source SHA")
    require(isinstance(run, dict), "candidate run response must be an object")
    require(str(run.get("id")) == str(run_id), "candidate run id mismatch")
    for key in ("repository", "head_repository"):
        require(isinstance(run.get(key), dict)
                and run[key].get("full_name", "").lower() == REPOSITORY.lower(),
                "candidate run must originate in the release repository")
    require(run.get("path") == WORKFLOW and run.get("name") == "Product release candidate",
            "unsupported candidate workflow")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "candidate run must have completed successfully")
    require(run.get("head_sha") == source_sha, "candidate run source mismatch")
    actual_attempt = run.get("run_attempt")
    require(type(actual_attempt) is int and actual_attempt > 0, "invalid candidate run attempt")
    event = run.get("event")
    if event == "workflow_dispatch":
        require(run.get("head_branch") == "main", "dispatched candidate must originate on main")
        require(attempt is not None, "dispatched candidate requires an explicit run attempt")
    else:
        branch = run.get("head_branch", "")
        require(event == "push" and isinstance(branch, str) and branch.startswith("v")
                and STRICT_VERSION.fullmatch(branch[1:]), "unsupported candidate event/ref")
    if attempt is not None:
        require(str(attempt) == str(actual_attempt), "candidate run attempt mismatch")
    return {"source_sha": source_sha, "run_attempt": actual_attempt,
            "artifact_name": f"turboism-release-candidate-{source_sha}-{run_id}-{actual_attempt}"}


def prepare_candidate_tag(repo_root, expected_source_sha):
    """Local-only compatibility tag, called AFTER candidate checks, never pushed."""
    require(SOURCE_SHA.fullmatch(expected_source_sha), "invalid expected source SHA")
    source = git_source(repo_root, require_tag=False)
    require(source["revision"] == expected_source_sha, "candidate source changed")
    version = framework_version(repo_root)
    tag = f"v{version}"
    require(source["tag"] in (None, tag), "candidate has a different local release tag")
    if source["tag"] is None:
        result = subprocess.run([
            "git", "-C", str(repo_root), "-c", "user.name=Turboism release candidate",
            "-c", "user.email=release@turboism.dev", "tag", "-a", tag,
            "-m", f"Verified candidate for {tag} at {expected_source_sha}", expected_source_sha,
        ], capture_output=True, text=True)
        require(result.returncode == 0, "cannot create local candidate tag (possible version conflict)")
    return tag


def unique_file(root, name):
    files = list(root.rglob(name))
    require(len(files) == 1 and files[0].is_file(), f"candidate requires exactly one {name}")
    return files[0]


def verify_bundle(source_root, bundle_root, run, source_sha):
    require(bundle_root.is_dir() and not bundle_root.is_symlink(), "candidate bundle missing")
    require(not any(path.is_symlink() for path in bundle_root.rglob("*")), "candidate contains symlinks")
    result = subprocess.run(["git", "-C", str(source_root), "rev-parse", "HEAD"],
                            capture_output=True, text=True)
    require(result.returncode == 0 and result.stdout.strip() == source_sha, "source checkout mismatch")
    clean = subprocess.run(["git", "-C", str(source_root), "diff", "--quiet", "HEAD", "--",
                            "gradle/common-java.gradle.kts", "CHANGELOG.md", "packaging/release-plugins.txt"],
                           capture_output=True)
    require(clean.returncode == 0, "source release metadata was modified after checkout")
    document = read_document(unique_file(bundle_root, "candidate.json"), "candidate")
    version = framework_version(source_root)
    tag = f"v{version}"
    require(document.get("source") == {"repository": REPOSITORY, "revision": source_sha, "tag": tag},
            "candidate source/version binding mismatch")
    if run["event"] == "push":
        require(run["head_branch"] == tag, "legacy candidate tag mismatch")
    framework = document.get("framework")
    require(isinstance(framework, dict) and framework.get("eligible") is True
            and framework.get("version") == version, "candidate framework is not eligible")
    text = (source_root / "CHANGELOG.md").read_text(encoding="utf-8")
    dates = [m.group(2) for m in CHANGELOG_HEADING.finditer(text) if m.group(1) == version]
    require(len(dates) == 1, "candidate changelog section must be unique")
    extractor = _load_script("promotion_release_notes", SCRIPTS / "extract-release-notes.py")
    notes = extractor.extract(text, version)
    require(framework.get("changelog") == {
        "date": dates[0], "sha256": hashlib.sha256(notes.encode("utf-8")).hexdigest()},
        "candidate changelog binding mismatch")
    require(unique_file(bundle_root, "release-notes.md").read_bytes() == notes.encode("utf-8"),
            "candidate release notes differ from source")
    dist = unique_file(bundle_root, f"turboism-{version}-full.zip").parent
    artifacts = framework_artifacts(source_root, dist, version)
    require(framework.get("artifacts") == artifacts, "candidate artifact hashes/sizes differ")
    return tag, dist, notes, {item["name"]: {"size": item["size"], "sha256": item["sha256"]}
                             for item in artifacts}


def tag_binding(github, tag, source_sha, binding=None):
    ref = github.api(f"git/ref/tags/{tag}", optional=True)
    if ref is None:
        return False
    require(isinstance(ref, dict) and isinstance(ref.get("object"), dict), "malformed tag ref")
    obj = ref["object"]
    require(obj.get("type") == "tag" and SOURCE_SHA.fullmatch(obj.get("sha", "")),
            "release ref must be an annotated tag")
    annotation = github.api(f"git/tags/{obj['sha']}")
    require(isinstance(annotation, dict) and annotation.get("tag") == tag
            and annotation.get("object", {}).get("type") == "commit"
            and annotation["object"].get("sha") == source_sha, "release tag source conflict")
    if binding is not None:
        require(f"Candidate payload SHA-256: {binding}" in annotation.get("message", "").splitlines(),
                "release tag candidate payload conflict")
    return True


def missing_assets(release, expected, tag):
    require(isinstance(release, dict) and release.get("tag_name") == tag
            and release.get("prerelease") is False and type(release.get("draft")) is bool
            and type(release.get("id")) is int and release["id"] > 0, "invalid remote release identity")
    verifier = _load_script("promotion_github_assets", SCRIPTS / "verify-github-assets.py")
    require(verifier.main(["--expected-json", json.dumps(expected), "--release-json", json.dumps(release),
                           "--allow-missing"]) == 0, "remote release asset conflict")
    names = {asset["name"] for asset in release["assets"]}
    missing = sorted(set(expected) - names)
    require(release["draft"] or not missing, "published release is incomplete")
    return missing


def ensure_tag(github, tag, source_sha, binding=None):
    if tag_binding(github, tag, source_sha, binding):
        return
    annotation = github.api("git/tags", method="POST", data={
        "tag": tag, "message": f"Turboism {tag[1:]} ({source_sha})"
        + (f"\nCandidate payload SHA-256: {binding}" if binding else ""),
        "object": source_sha, "type": "commit"})
    require(isinstance(annotation, dict) and SOURCE_SHA.fullmatch(annotation.get("sha", "")),
            "invalid created tag object")
    try:
        github.api("git/refs", method="POST", data={"ref": f"refs/tags/{tag}", "sha": annotation["sha"]})
    except ReleaseError:
        # A lost response or another publisher may have bound the same ref. Never update it.
        if not tag_binding(github, tag, source_sha, binding):
            raise
    require(tag_binding(github, tag, source_sha, binding), "tag binding was not persisted")


def promote(github, source_root, bundle_root, run_id, source_sha, attempt, confirmation):
    require(confirmation == f"publish-github-only:{source_sha}", "explicit source confirmation required")
    require(re.fullmatch(r"[1-9][0-9]{0,19}", str(run_id)), "invalid candidate run id")
    run = github.api(f"actions/runs/{run_id}")
    validate_run(run, run_id, source_sha, attempt)
    tag, dist, notes, expected = verify_bundle(source_root, bundle_root, run, source_sha)
    binding = None
    if run["event"] == "workflow_dispatch":
        identity = {"source": source_sha, "tag": tag, "assets": expected, "notes": notes}
        binding = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":"))
                                 .encode("utf-8")).hexdigest()
    bound = tag_binding(github, tag, source_sha, binding)
    release = github.api(f"releases/tags/{tag}", optional=True)
    if release is not None:
        require(bound, "release exists without its annotated tag")
        missing_assets(release, expected, tag)
        if not release["draft"]:
            return tag  # Published and identical: strictly no mutation, including notes/latest.
    latest = github.api("releases/latest", optional=True)
    if latest is not None:
        latest_tag = latest.get("tag_name", "")
        require(isinstance(latest_tag, str) and latest_tag.startswith("v")
                and STRICT_VERSION.fullmatch(latest_tag[1:]), "invalid latest release version")
        require(compare_versions(tag[1:], latest_tag[1:]) >= 0, "newer release already published")
    # All local verification and remote conflict observations precede the first remote write.
    validate_run(github.api(f"actions/runs/{run_id}"), run_id, source_sha, run["run_attempt"])
    ensure_tag(github, tag, source_sha, binding)
    if release is None:
        release = github.api("releases", method="POST", data={
            "tag_name": tag, "target_commitish": source_sha, "name": f"Turboism {tag[1:]}",
            "body": notes, "draft": True, "prerelease": False})
    for name in missing_assets(release, expected, tag):
        github.upload(tag, dist / name)
    release = github.api(f"releases/tags/{tag}")
    require(not missing_assets(release, expected, tag), "release uploads incomplete")
    require(tag_binding(github, tag, source_sha, binding), "release tag disappeared")
    github.api(f"releases/{release['id']}", method="PATCH", data={
        "name": f"Turboism {tag[1:]}", "body": notes, "draft": False, "make_latest": "true"})
    final = github.api(f"releases/tags/{tag}")
    require(not missing_assets(final, expected, tag) and final["draft"] is False,
            "release did not become published")
    return tag


class GitHub:
    """Narrow gh transport: confirmed HTTP 404 only, never stderr-as-absence."""
    def api(self, path, *, method="GET", data=None, optional=False):
        command = ["gh", "api", "--hostname", "github.com", "--include", "--method", method,
                   f"repos/{REPOSITORY}/{path}"]
        if data is not None:
            command += ["--input", "-"]
        result = subprocess.run(command, input=json.dumps(data) if data is not None else None,
                                text=True, capture_output=True, timeout=120)
        response = result.stdout.replace("\r\n", "\n")
        headers, separator, body = response.partition("\n\n")
        status = re.match(r"HTTP/\S+ (\d{3})\b", headers)
        code = int(status.group(1)) if status else None
        if optional and method == "GET" and code == 404:
            return None
        require(result.returncode == 0 and code is not None and 200 <= code < 300 and separator,
                f"GitHub {method} {path} failed (HTTP {code or 'unknown'})")
        try:
            return json.loads(body)
        except ValueError as failure:
            raise ReleaseError(f"invalid GitHub response for {path}") from failure

    def upload(self, tag, path):
        result = subprocess.run(["gh", "release", "upload", tag, str(path.resolve()),
                                 "--repo", REPOSITORY], capture_output=True, text=True, timeout=600)
        require(result.returncode == 0, f"GitHub asset upload failed: {path.name}")
