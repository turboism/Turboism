#!/usr/bin/env python3
"""Stdlib runnable suite for scripts/check_remote_hygiene.py (review-fix round 2).

Run:  python3 scripts/test/test_check_remote_hygiene.py
Covers positive/negative path cases (case-insensitive, env templates
forbidden), secret-value signatures incl. fine-grained GitHub PAT (never the
value), the add-then-delete outgoing invariant, merge-resolution bypass,
rename under diff.renames=true, >1 MiB text tokens, malformed push stdin
fails closed, secret-shaped filename redaction, -C hook target resolution
from an unrelated cwd, and preservation/idempotence of existing hooks.
Runtime-constructed tokens so no literal signature exists in this source.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_remote_hygiene as crh  # noqa: E402

GIT = ["git"]
CHECKER = [sys.executable, str(Path(crh.__file__))]


def run_git(repo, *args):
    return subprocess.run(GIT + list(args), cwd=repo, capture_output=True,
                          text=True, check=True)


def fresh_repo(tmp):
    repo = os.path.join(tmp, "fixture")
    os.makedirs(repo)
    run_git(repo, "init", "-q", "-b", "main")
    run_git(repo, "config", "user.name", "Hygiene Test")
    run_git(repo, "config", "user.email", "hygiene@example.invalid")
    return repo


def commit_all(repo, msg):
    run_git(repo, "add", "-A")
    run_git(repo, "commit", "-q", "-m", msg)
    return run_git(repo, "rev-parse", "HEAD").stdout.strip()


def run_checker(repo, *args, stdin_text=None):
    return subprocess.run(CHECKER + list(args), cwd=repo,
                          input=stdin_text, capture_output=True, text=True)


def ghp(n):
    return "ghp_" + chr(ord("A") + n % 26) * 36


class PathRuleTest(unittest.TestCase):
    def test_positive_allowed_paths(self):
        for p in ["src/main/java/dev/turboism/App.java",
                  "packaging/plugin.json",
                  ".gitignore",
                  ".env.example",
                  "CONTRIBUTING.md",
                  "scripts/dev/build-worktree.sh",
                  "compatibility/cubism/verification/cubism-5.3.02-core-model-read.json",
                  "sdk/api-contracts/baselines/sdk-api-v2-exact.json",
                  "validation/mcp-host-probe/src/dev/turboism/Probe.java",
                  "validation/mcp-host-probe/src/META-INF/turboism/plugin.json",
                  ".github/workflows/remote-hygiene.yml"]:
            self.assertIsNone(crh.classify_path(p), p)

    def test_ordinary_words_allowed(self):
        for p in ["src/prompter/PromptFactory.java",  # 'Prompt', not 'prompts'
                  "src/main/resources/agent.properties",
                  "tests/env_test.py",
                  "src/App.env",                    # basename 'App.env', not '.env*'
                  "LocalConfig.java"]:              # 'Local' mid-name, no dot-separated .local.
            self.assertIsNone(crh.classify_path(p), p)

    def test_negative_forbidden_paths(self):
        cases = {
            ".agent-artifacts/x/result.md": "segment:.agent-artifacts",
            ".artifacts/cat/g.json": "segment:.artifacts",
            ".research-artifacts/x.md": "segment:.research-artifacts",
            ".pi-subagents/x": "segment:.pi-subagents",
            ".claude/settings.json": "segment:.claude",
            ".cursor/rules/x.mdc": "segment:.cursor",
            ".pi/session.jsonl": "segment:.pi",
            ".windsurf/x": "segment:.windsurf",
            ".specify/memory.json": "segment:.specify",
            "cubism-ref/core-api/observed/host.json": "segment:cubism-ref",
            "docs/migration/guide.md": "segment:docs",
            "docs-internal/notes.md": "segment:docs-internal",
            "generated-references/plugin-public-events.md": "segment:generated-references",
            "host-evidence/5.3.02/result.json": "segment:host-evidence",
            "research/selector-notes.md": "segment:research",
            "specs/plan.md": "segment:specs",
            "validation-artifact/report.md": "segment:validation-artifact",
            "validation/mcp-host-probe/out/Probe.class": "validation-output:out",
            "validation/mcp-host-probe/results/result.json": "validation-output:results",
            "validation/mcp-host-probe/probe.jar": "validation-output:*.jar",
            "validation/mcp-host-probe/host.log": "validation-output:*.log",
            "prompts/foo.md": "segment:prompts",
            "AGENTS.md": "basename:agents.md",
            "nested/CLAUDE.md": "basename:claude.md",
            "GEMINI.md": "basename:gemini.md",
            "deep/COPILOT.md": "basename:copilot.md",
            ".AGENTS.md.swp": "basename:.agents.md.swp",
            ".env": "basename:.env",
            "a/.env.production": "basename:.env.*",
            "svc/.env.example": "basename:.env.*",
            "svc/.env.sample": "basename:.env.*",
            ".envrc": "basename:.envrc",
            "local.properties": "basename:local.properties",
            "config.local.json": "basename:*.local.*",
            "notes.swp": "basename:editor-swap",
            "x/.swo": "basename:editor-swap",
            ".#lockfile.txt": "basename:emacs-lock",
            "backup~": "basename:editor-backup",
            ".aider.tags.cache.v3": "basename:.aider*",
            "notes/plan.prompt.md": "suffix:*.prompt.md",
            "packaging/tool/SPEC.md": "basename:spec*.md",
            "packaging/tool/SPEC-LAUNCH.md": "basename:spec*.md",
            "runtime/logs/dialog-transform.log": "path:runtime/logs",
            "RUNTIME/LOGS/host-trace.txt": "path:runtime/logs",
            ".github/copilot-instructions.md": "basename:copilot-instructions.md",
            ".GITHUB/COPILOT-INSTRUCTIONS.MD": "basename:copilot-instructions.md",
        }
        for p, rule in cases.items():
            self.assertEqual(crh.classify_path(p), rule, p)

    def test_case_variant_paths_forbidden(self):
        for p in [".ENV", "nested/AGENTS.MD", "GEMINI.MD", "Prompts/x.md",
                  "CUBISM-REF/host.json", "DOCS/migration/x.md",
                  "GENERATED-REFERENCES/report.md", "HOST-EVIDENCE/result.json",
                  "RESEARCH/notes.md", ".Agent-Artifacts/x.md",
                  ".CLAUDE/set.json", ".PI/session.jsonl", ".SPECIFY/task.json",
                  "App.LOCAL.yaml", "nested/COPILOT.MD", "RUNTIME/LOGS/trace.log"]:
            self.assertIsNotNone(crh.classify_path(p), p)

    def test_force_added_local_reference_is_rejected_in_staged_mode(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-local-ref-"))
        (Path(repo) / ".gitignore").write_text("/cubism-ref/\n")
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "base")
        path = Path(repo) / "cubism-ref" / "host.json"
        path.parent.mkdir(parents=True)
        path.write_text("{}\n")
        run_git(repo, "add", "-f", "cubism-ref/host.json")
        out = run_checker(repo, "--staged")
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn("segment:cubism-ref", out.stdout)

    def test_runtime_log_is_rejected_in_staged_mode(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-runtime-log-"))
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "base")
        path = Path(repo) / "runtime" / "logs" / "dialog-transform.log"
        path.parent.mkdir(parents=True)
        path.write_text("generated\n")
        run_git(repo, "add", "-f", "runtime/logs/dialog-transform.log")
        out = run_checker(repo, "--staged")
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn("path:runtime/logs", out.stdout)


class ContentRuleTest(unittest.TestCase):
    def test_allowed_content(self):
        allowed = [
            'String dir = System.getenv("APPDATA");',
            "run: echo \${{ secrets.NAME }} > /dev/null",
            "int prompt = 0;",
            "const env = process.env.NODE_ENV;",
            "ak = compute_average(query)",
            "# see the github_pat style docs",  # prefix + <20 chars: not a token
        ]
        for text in allowed:
            self.assertEqual(crh.scan_content(text.encode()), [], text)

    def test_forbidden_secret_signatures(self):
        cases = {
            "-----BEGIN OPENSSH" + " PRIVATE KEY-----\n"
            "AAAA\n-----END OPENSSH PRIVATE KEY-----": "private-key-block",
            "token=" + ghp(0): "github-pat",
            "fine=" + "github_pat_" + "G" * 40: "github-pat-fine",
            "AKIA" + "0123456789ABCDEF": "aws-access-key",
            "xoxb-" + "123456789012-abcdefghijklmn": "slack-token",
            "sk-proj-" + "abcdefghijklmnopqrstuvwxyz": "openai-api-key",
            "AIza" + "A" * 35: "google-api-key",
            "ghp_" + "B" * 36: "github-pat",  # bare token with no key= prefix
        }
        for text, name in cases.items():
            self.assertIn(name, crh.scan_content(text.encode()), text)

    def test_repository_local_machine_values_are_forbidden(self):
        cases = {
            "fixture=" + "/home/" + "r" + "ain/project.cmo3": "local-machine-home",
            "ssh=" + "r" + "ain" + "@172.17.0.1": "local-machine-ssh-host",
            "key=id_ed25519_" + "turboism_arch_rebuild": "local-machine-ssh-key-name",
            "cwd=/workspace/projects/" + "turboism/.worktrees/release": "local-machine-workspace",
        }
        for text, rule in cases.items():
            self.assertIn(rule, crh.scan_repository_content("script.sh", text.encode()))

    def test_all_history_rejects_real_local_workspace(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-local-history-"))
        (Path(repo) / "note.txt").write_text(
            "cwd=/workspace/projects/" + "turboism/.worktrees/private\n"
        )
        commit_all(repo, "add local workspace")
        out = run_checker(repo, "--all")
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn("value-signature=local-machine-workspace", out.stdout)

    def test_secret_value_never_printed(self):
        secret = ghp(1)
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-secret-"))
        (Path(repo) / "bad.txt").write_text("key=" + secret + "\n")
        commit_all(repo, "add secret")
        out = run_checker(repo, "--all")
        self.assertEqual(out.returncode, 1)
        self.assertNotIn(secret, out.stdout)
        self.assertIn("value-signature=", out.stdout)

    def test_large_text_token_after_old_threshold(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-big-"))
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "base")
        secret = ghp(2)
        big = "x" * 1_100_000 + "\nsecret=" + secret + "\n"
        (Path(repo) / "big.txt").write_text(big)
        run_git(repo, "add", "big.txt")
        out = run_checker(repo, "--staged")
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn("value-signature=github-pat", out.stdout)
        self.assertNotIn(secret, out.stdout)

    def test_secret_shaped_filename_redacted(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-redact-"))
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "base")
        secret = ghp(3)
        os.makedirs(os.path.join(repo, ".pi"))
        (Path(repo) / ".pi" / ("zz_" + secret + ".log")).write_text("x\n")  # token embedded after a word-chars prefix
        run_git(repo, "add", "-A")
        out = run_checker(repo, "--staged")
        self.assertEqual(out.returncode, 1)
        self.assertNotIn(secret, out.stdout)
        self.assertIn("<redacted>", out.stdout)
        self.assertIn("segment:.pi", out.stdout)


class ModeTest(unittest.TestCase):
    def test_outgoing_deletion_of_legacy_forbidden_path_passes(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-out-delete-"))
        runtime_logs = Path(repo) / "runtime" / "logs"
        runtime_logs.mkdir(parents=True)
        tracked_log = runtime_logs / "dialog-transform.log"
        tracked_log.write_text("legacy generated output\n")
        base = commit_all(repo, "legacy tracked log")
        tracked_log.unlink()
        head = commit_all(repo, "delete legacy log")
        out = run_checker(repo, "--outgoing", f"{base}..{head}")
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)

    def test_add_then_delete_outgoing_invariant(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-atd-"))
        (Path(repo) / "ok.txt").write_text("fine\n")
        base = commit_all(repo, "base")
        os.makedirs(os.path.join(repo, ".agent-artifacts"))
        (Path(repo) / ".agent-artifacts" / "x.md").write_text("leak\n")
        commit_all(repo, "add artifact")
        (Path(repo) / ".agent-artifacts" / "x.md").unlink()
        del_sha = commit_all(repo, "delete artifact")
        out = run_checker(repo, "--outgoing", f"{base}..{del_sha}")
        self.assertEqual(out.returncode, 1)
        self.assertIn(".agent-artifacts/x.md", out.stdout)

    def test_merge_resolution_bypass_closed(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-merge-"))
        (Path(repo) / "config.txt").write_text("value=one\n")
        base = commit_all(repo, "base")
        run_git(repo, "checkout", "-q", "-b", "side")
        (Path(repo) / "config.txt").write_text("value=two\n")
        run_git(repo, "commit", "-q", "-am", "side change")
        run_git(repo, "checkout", "-q", "main")
        (Path(repo) / "config.txt").write_text("value=three\n")
        run_git(repo, "commit", "-q", "-am", "main change")
        p = subprocess.run(GIT + ["merge", "side"], cwd=repo,
                           capture_output=True, text=True)
        self.assertNotEqual(p.returncode, 0)  # conflict expected
        secret = ghp(4)
        # resolution introduces BOTH a secret value and a forbidden path that
        # exist in no parent tree: only the merge result carries them
        (Path(repo) / "config.txt").write_text(
            "value=merged\nSECRET=" + secret + "\n")
        os.makedirs(os.path.join(repo, ".agent-artifacts"))
        (Path(repo) / ".agent-artifacts" / "report.md").write_text("resolution-only\n")
        run_git(repo, "add", "-A")
        run_git(repo, "commit", "-q", "--no-edit")  # completes the merge
        merge_sha = run_git(repo, "rev-parse", "HEAD").stdout.strip()
        parents = run_git(repo, "rev-list", "--parents", "-n", "1", "HEAD").stdout.split()
        self.assertEqual(len(parents), 3, "expected a real 2-parent merge commit")
        out = run_checker(repo, "--outgoing", f"{base}..{merge_sha}")
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn(".agent-artifacts/report.md", out.stdout)
        self.assertIn("value-signature=github-pat", out.stdout)
        self.assertNotIn(secret, out.stdout)

    def test_rename_mutation_detected_with_renames_enabled(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-rename-"))
        run_git(repo, "config", "diff.renames", "true")
        lines = "\n".join("line %03d alpha beta gamma delta" % i for i in range(300))
        (Path(repo) / "similar.txt").write_text(lines + "\n")
        base = commit_all(repo, "base")
        os.rename(os.path.join(repo, "similar.txt"), os.path.join(repo, "renamed.txt"))
        with open(os.path.join(repo, "renamed.txt"), "a") as fh:
            fh.write("secret=" + ghp(5) + "\n")
        head = commit_all(repo, "rename+mutation")
        out = run_checker(repo, "--outgoing", f"{base}..{head}")
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn("value-signature=github-pat", out.stdout)

    def test_outgoing_clean_range_passes(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-clean-"))
        (Path(repo) / "a.txt").write_text("x\n")
        base = commit_all(repo, "base")
        (Path(repo) / "b.txt").write_text("y\n")
        head = commit_all(repo, "add clean")
        out = run_checker(repo, "--outgoing", f"{base}..{head}")
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)

    def test_git_error_fails_closed_without_traceback(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-giterr-"))
        (Path(repo) / "a.txt").write_text("x\n")
        commit_all(repo, "base")
        head = run_git(repo, "rev-parse", "HEAD").stdout.strip()
        out = run_checker(repo, "--outgoing", "not-a-revision..%s" % head)
        self.assertEqual(out.returncode, 2)
        self.assertNotIn("Traceback", out.stderr)
        self.assertNotIn("TypeError", out.stderr)
        self.assertIn("refusing (fail closed)", out.stderr)
    def test_worktree_mode_scans_tracked_index_without_reading_ignored_env(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-worktree-"))
        (Path(repo) / ".gitignore").write_text(".env\n")
        (Path(repo) / "config.txt").write_text("fixture=/remote/fixture.cmo3\n")
        commit_all(repo, "base")
        (Path(repo) / ".env").write_text(
            "TURBOISM_HOST_VALIDATION_SSH_HOST=" + "r" + "ain" + "@172.17.0.1\n"
        )
        out = run_checker(repo, "--worktree")
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)

        (Path(repo) / "config.txt").write_text(
            "fixture=" + "/home/" + "r" + "ain/project.cmo3\n"
        )
        out = run_checker(repo, "--worktree")
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn("value-signature=local-machine-home", out.stdout)

    def test_staged_mode(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-staged-"))
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "base")
        (Path(repo) / ".env").write_text("KEY=value\n")
        run_git(repo, "add", ".env")
        out = run_checker(repo, "--staged")
        self.assertEqual(out.returncode, 1)
        self.assertIn(".env", out.stdout)
        self.assertNotIn("KEY=value", out.stdout)

    def test_staged_deletion_of_forbidden_path_passes(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-staged-delete-"))
        runtime_logs = Path(repo) / "runtime" / "logs"
        runtime_logs.mkdir(parents=True)
        tracked_log = runtime_logs / "dialog-transform.log"
        tracked_log.write_text("legacy generated output\n")
        commit_all(repo, "legacy tracked log")
        tracked_log.unlink()
        run_git(repo, "add", "-u")
        out = run_checker(repo, "--staged")
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)

    def test_all_history_mode(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-all-"))
        os.makedirs(os.path.join(repo, "prompts"))
        (Path(repo) / "prompts" / "x.prompt.md").write_text("leak\n")
        commit_all(repo, "add prompt")
        (Path(repo) / "prompts" / "x.prompt.md").unlink()
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "cleanup")
        out = run_checker(repo, "--all")
        self.assertEqual(out.returncode, 1)
        self.assertIn("prompts/x.prompt.md", out.stdout)


class HookTest(unittest.TestCase):
    def test_install_hooks_targets_requested_repo_from_other_cwd(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-ctgt-"))
        caller = tempfile.mkdtemp(prefix="crh-caller-")
        out = subprocess.run(
            CHECKER + ["--install-hooks", "-C", repo],
            cwd=caller, capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertTrue(os.path.exists(
            os.path.join(repo, ".git", "hooks", "pre-commit")))
        self.assertTrue(os.path.exists(
            os.path.join(repo, ".git", "hooks", "pre-push")))
        # nothing may land in the caller cwd
        self.assertEqual(os.listdir(caller), [])

    def test_existing_different_hook_preserved_fail_closed(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-presrv-"))
        hooks = os.path.join(repo, ".git", "hooks")
        os.makedirs(hooks, exist_ok=True)
        custom = "#!/bin/sh\necho custom\n"
        path = os.path.join(hooks, "pre-commit")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(custom)
        os.chmod(path, 0o700)
        mode_before = os.stat(path).st_mode
        out = run_checker(repo, "--install-hooks")
        self.assertEqual(out.returncode, 2)
        self.assertIn("refusing to overwrite", out.stderr)
        with open(path, encoding="utf-8") as fh:
            self.assertEqual(fh.read(), custom)          # bytes preserved
        self.assertEqual(os.stat(path).st_mode, mode_before)  # mode preserved
        self.assertFalse(os.path.exists(os.path.join(hooks, "pre-push")))

    def test_identical_existing_hook_made_executable_and_stable(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-idem-"))
        hooks = os.path.join(repo, ".git", "hooks")
        os.makedirs(hooks, exist_ok=True)
        path = os.path.join(hooks, "pre-commit")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(crh.PRE_COMMIT_HOOK)
        os.chmod(path, 0o600)
        mode_before = os.stat(path).st_mode
        out = run_checker(repo, "--install-hooks")
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        with open(path, encoding="utf-8") as fh:
            self.assertEqual(fh.read(), crh.PRE_COMMIT_HOOK)  # untouched bytes
        self.assertEqual(os.stat(path).st_mode & 0o111, 0o100)  # user-exec bit on
        self.assertEqual(os.stat(path).st_mode & 0o077, mode_before & 0o077)  # other bits kept
        # idempotent second install: bytes and mode stable
        mode_after = os.stat(path).st_mode
        out2 = run_checker(repo, "--install-hooks")
        self.assertEqual(out2.returncode, 0, out2.stdout + out2.stderr)
        with open(path, encoding="utf-8") as fh:
            self.assertEqual(fh.read(), crh.PRE_COMMIT_HOOK)
        self.assertEqual(os.stat(path).st_mode, mode_after)
        self.assertTrue(os.path.exists(os.path.join(hooks, "pre-push")))
        self.assertTrue(os.path.exists(os.path.join(hooks, "pre-push")))

    def test_hook_install_atomic_reverse_order(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-atomic-"))
        hooks = os.path.join(repo, ".git", "hooks")
        os.makedirs(hooks, exist_ok=True)
        custom = "#!/bin/sh\necho custom-push\n"
        path = os.path.join(hooks, "pre-push")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(custom)
        os.chmod(path, 0o700)
        mode_before = os.stat(path).st_mode
        out = run_checker(repo, "--install-hooks")
        self.assertEqual(out.returncode, 2)
        self.assertIn("refusing to overwrite", out.stderr)
        with open(path, encoding="utf-8") as fh:
            self.assertEqual(fh.read(), custom)              # differing hook preserved
        self.assertEqual(os.stat(path).st_mode, mode_before)  # mode preserved
        # no partial write: the missing pre-commit must not have been created
        self.assertFalse(os.path.exists(os.path.join(hooks, "pre-commit")))
    def test_hook_dangling_symlink_fails_closed_no_partial_writes(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-sym-"))
        hooks = os.path.join(repo, ".git", "hooks")
        os.makedirs(hooks, exist_ok=True)
        target = os.path.join(hooks, "pre-push-target.sh")  # must never be created
        link = os.path.join(hooks, "pre-push")
        os.symlink(target, link)
        out = run_checker(repo, "--install-hooks")
        self.assertEqual(out.returncode, 2)
        self.assertIn("symlink", out.stderr)
        self.assertTrue(os.path.islink(link))            # the symlink itself untouched
        self.assertFalse(os.path.exists(target))         # dangling target NOT created
        self.assertFalse(os.path.exists(os.path.join(hooks, "pre-commit")))

    def test_hook_live_symlink_fails_closed_target_untouched(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-syml-"))
        hooks = os.path.join(repo, ".git", "hooks")
        os.makedirs(hooks, exist_ok=True)
        target = os.path.join(hooks, "shared-hook.sh")
        with open(target, "w") as fh:
            fh.write("#!/bin/sh\necho shared\n")
        os.chmod(target, 0o600)
        mode_before = os.stat(target).st_mode
        link = os.path.join(hooks, "pre-push")
        os.symlink(target, link)
        out = run_checker(repo, "--install-hooks")
        self.assertEqual(out.returncode, 2)
        self.assertIn("symlink", out.stderr)
        self.assertTrue(os.path.islink(link))
        with open(target) as fh:
            self.assertEqual(fh.read(), "#!/bin/sh\necho shared\n")  # target bytes untouched
        self.assertEqual(os.stat(target).st_mode, mode_before)          # target mode untouched
        self.assertFalse(os.path.exists(os.path.join(hooks, "pre-commit")))
    def test_parse_push_lines_and_malformed(self):
        a = "1" * 40
        b = "2" * 40
        c = "3" * 40
        entries = crh.parse_push_lines(
            "refs/heads/main %s refs/heads/main %s\n"
            "refs/heads/main %s refs/heads/main %s\n"
            % (a, ZERO := "0" * 40, b, c))
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0], ("refs/heads/main", a, "refs/heads/main", ZERO))
        self.assertEqual(entries[1], ("refs/heads/main", b, "refs/heads/main", c))

    def test_rewrite_history_prepush_gate_operable(self):
        # first history-rewrite push: remote SHA is unavailable locally
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-rw-"))
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "clean base")
        tip = run_git(repo, "rev-parse", "HEAD").stdout.strip()
        out = run_checker(repo, "--outgoing-stdin",
                          stdin_text="refs/heads/main %s refs/heads/main %s\n"
                          % (tip, "07801110645aedbccb433e81e06109c631f1e026"))
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertIn("clean", out.stdout)

    def test_unavailable_remote_sha_full_scan_not_skip(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-rw2-"))
        os.makedirs(os.path.join(repo, ".agent-artifacts"))
        (Path(repo) / ".agent-artifacts" / "early.md").write_text("leak\n")
        early = commit_all(repo, "early forbidden")
        (Path(repo) / ".agent-artifacts" / "early.md").unlink()
        (Path(repo) / "ok.txt").write_text("fine\n")
        commit_all(repo, "clean tip")
        tip = run_git(repo, "rev-parse", "HEAD").stdout.strip()
        out = run_checker(repo, "--outgoing-stdin",
                          stdin_text="refs/heads/main %s refs/heads/main %s\n"
                          % (tip, "07801110645aedbccb433e81e06109c631f1e026"))
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn(".agent-artifacts/early.md", out.stdout)

    def test_non_ancestor_remote_sha_scans_all_local_history(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-na-"))
        os.makedirs(os.path.join(repo, ".agent-artifacts"))
        (Path(repo) / ".agent-artifacts" / "early.md").write_text("leak\n")
        commit_all(repo, "early forbidden")
        (Path(repo) / ".agent-artifacts" / "early.md").unlink()
        (Path(repo) / "ok.txt").write_text("fine\n")
        main_tip = commit_all(repo, "main tip")
        # divergent branch tip: available locally but NOT an ancestor of main_tip
        run_git(repo, "checkout", "-q", "-b", "side", main_tip + "~1")
        (Path(repo) / "side.txt").write_text("side\n")
        run_git(repo, "add", "-A")
        run_git(repo, "commit", "-q", "-m", "side")
        side = run_git(repo, "rev-parse", "HEAD").stdout.strip()
        self.assertEqual(crh.resolve_push_base(repo, main_tip, side), None)  # non-ff
        out = run_checker(repo, "--outgoing-stdin",
                          stdin_text="refs/heads/main %s refs/heads/main %s\n"
                          % (main_tip, side))
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn(".agent-artifacts/early.md", out.stdout)

    def test_fast_forward_forbidden_deletion_passes(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-ff-delete-"))
        runtime_logs = Path(repo) / "runtime" / "logs"
        runtime_logs.mkdir(parents=True)
        tracked_log = runtime_logs / "dialog-transform.log"
        tracked_log.write_text("legacy generated output\n")
        base = commit_all(repo, "remote legacy log")
        tracked_log.unlink()
        tip = commit_all(repo, "delete legacy log")
        out = run_checker(
            repo,
            "--outgoing-stdin",
            stdin_text="refs/heads/main %s refs/heads/main %s\n" % (tip, base),
        )
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)

    def test_fast_forward_scans_only_outgoing_range(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-ff-"))
        os.makedirs(os.path.join(repo, ".agent-artifacts"))
        (Path(repo) / ".agent-artifacts" / "early.md").write_text("leak\n")
        commit_all(repo, "early forbidden")
        (Path(repo) / ".agent-artifacts" / "early.md").unlink()
        (Path(repo) / "ok.txt").write_text("fine\n")
        base = commit_all(repo, "remote base")   # available local ancestor
        self.assertEqual(crh.resolve_push_base(repo, base, base), base)
        (Path(repo) / "more.txt").write_text("more\n")
        tip = commit_all(repo, "outgoing")
        out = run_checker(repo, "--outgoing-stdin",
                          stdin_text="refs/heads/main %s refs/heads/main %s\n"
                          % (tip, base))
        # only the outgoing range is scanned: the early forbidden commit is
        # already on the remote and must NOT block a clean fast-forward
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)

    def test_malformed_push_stdin_fails_closed(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-badstdin-"))
        out = run_checker(repo, "--outgoing-stdin",
                          stdin_text="refs/heads/main %s refs/heads/main\n" % ("1" * 40))
        self.assertEqual(out.returncode, 2)
        self.assertIn("malformed", out.stderr)

    def test_empty_push_stdin_fails_closed(self):
        repo = fresh_repo(tempfile.mkdtemp(prefix="crh-emptystdin-"))
        out = run_checker(repo, "--outgoing-stdin", stdin_text="")
        self.assertEqual(out.returncode, 2)
        self.assertIn("no push refs", out.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)