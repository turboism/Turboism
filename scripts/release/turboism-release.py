#!/usr/bin/env python3
"""Build, plan, dispatch, resume, and verify coordinated Turboism releases."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from turboism_release.candidate import build_candidate
from turboism_release.contracts import ReleaseError, read_document, write_document
from turboism_release.executor import dispatch_production, load_or_create_state, verify_plan_state
from turboism_release.planner import make_plan, read_json_source
from turboism_release.remote import github_release, updates_release, verified_catalog


DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BUILD = Path("build/release-orchestrator")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--repo-root", type=Path, default=DEFAULT_ROOT)
    commands = root.add_subparsers(dest="command", required=True)

    build = commands.add_parser("build", help="emit a deterministic release candidate")
    _candidate_arguments(build)

    plan = commands.add_parser("plan", help="classify a candidate against published state")
    _plan_arguments(plan)

    verify = commands.add_parser("verify", help="validate a plan and optional resume state")
    verify.add_argument("--plan", required=True, type=Path)
    verify.add_argument("--state", type=Path)

    publish = commands.add_parser("publish", help="dispatch the protected production publisher")
    _publish_arguments(publish)

    resume = commands.add_parser("resume", help="validate state and resume protected publication")
    _publish_arguments(resume)
    resume.add_argument("--state", required=True, type=Path)

    release = commands.add_parser("release", help="build and plan; dispatch only with production confirmation")
    _candidate_arguments(release)
    release.add_argument("--github-observation")
    release.add_argument("--updates-observation")
    release.add_argument("--catalog-observation")
    release.add_argument("--plugin-directory-repo", type=Path)
    release.add_argument("--channel", choices=("stable", "beta", "nightly"), default="stable")
    release.add_argument("--candidate-run-id")
    release.add_argument("--production", action="store_true")
    release.add_argument("--confirm")
    release.add_argument("--publisher-workflow", default="release-publisher.yml")
    release.add_argument("--github-repo", default="turboism/Turboism")
    return root


def _candidate_arguments(command: argparse.ArgumentParser) -> None:
    command.add_argument("--dist", type=Path)
    command.add_argument("--market-dir", type=Path)
    command.add_argument("--candidate", type=Path, default=DEFAULT_BUILD / "candidate.json")
    command.add_argument("--require-tag", action="store_true")


def _plan_arguments(command: argparse.ArgumentParser) -> None:
    command.add_argument("--candidate", required=True, type=Path)
    command.add_argument("--plan", type=Path, default=DEFAULT_BUILD / "plan.json")
    command.add_argument("--github-observation")
    command.add_argument("--updates-observation")
    command.add_argument("--catalog-observation")
    command.add_argument("--plugin-directory-repo", type=Path)
    command.add_argument("--channel", choices=("stable", "beta", "nightly"), default="stable")


def _publish_arguments(command: argparse.ArgumentParser) -> None:
    command.add_argument("--plan", required=True, type=Path)
    command.add_argument("--candidate-run-id")
    command.add_argument("--production", action="store_true")
    command.add_argument("--confirm")
    command.add_argument("--publisher-workflow", default="release-publisher.yml")
    command.add_argument("--github-repo", default="turboism/Turboism")


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    repo_root = args.repo_root.resolve()
    try:
        if args.command == "build":
            candidate = _build(args, repo_root)
            print(f"candidate={args.candidate.resolve()}")
            print(f"framework_eligible={str(candidate['framework']['eligible']).lower()}")
            print(f"store_eligible={len(candidate['plugins']['candidates'])}")
            return 0
        if args.command == "plan":
            plan = _plan(args)
            print(f"plan={args.plan.resolve()}")
            print(f"plan_id={plan['planId']}")
            print(f"intent={plan['intent']}")
            return 0
        if args.command == "verify":
            plan = read_document(args.plan, "plan")
            state = read_document(args.state, "state") if args.state else None
            if state is not None and state.get("planId") != plan.get("planId"):
                raise ReleaseError("state planId does not match the plan")
            verify_plan_state(plan, state)
            print(f"release plan verified: {plan['planId']} ({plan['intent']})")
            return 0
        if args.command in ("publish", "resume"):
            plan = read_document(args.plan, "plan")
            verify_plan_state(plan)
            if args.command == "resume":
                state = load_or_create_state(plan, args.state)
                verify_plan_state(plan, state)
            dispatch_production(
                args.plan.resolve(), plan,
                candidate_run_id=args.candidate_run_id,
                production=args.production,
                confirmation=args.confirm,
                workflow=args.publisher_workflow,
                repo=args.github_repo,
            )
            return 0
        if args.command == "release":
            candidate = _build(args, repo_root)
            args.candidate = args.candidate.resolve()
            args.plan = args.candidate.with_name("plan.json")
            plan = _plan(args, candidate)
            print(f"candidate={args.candidate}")
            print(f"plan={args.plan}")
            print(f"plan_id={plan['planId']}")
            print(f"intent={plan['intent']}")
            if args.production:
                dispatch_production(
                    args.plan, plan,
                    candidate_run_id=args.candidate_run_id,
                    production=True,
                    confirmation=args.confirm,
                    workflow=args.publisher_workflow,
                    repo=args.github_repo,
                )
            return 0
        raise ReleaseError(f"unsupported command: {args.command}")
    except ReleaseError as failure:
        print(f"release orchestration failed: {failure}", file=sys.stderr)
        return 1


def _build(args, repo_root: Path):
    candidate = build_candidate(
        repo_root,
        dist=args.dist,
        market_dir=args.market_dir,
        require_tag=args.require_tag,
    )
    write_document(args.candidate, candidate, "candidate")
    return candidate


def _plan(args, candidate=None):
    candidate = candidate or read_document(args.candidate, "candidate")
    framework = candidate["framework"]
    tag = candidate["source"].get("tag")
    github = _observation_or_live(
        args.github_observation,
        "GitHub Release",
        lambda: github_release(args.github_repo if hasattr(args, "github_repo") else "turboism/Turboism", tag),
        required=False,
    ) if tag else None
    updates = _observation_or_live(
        args.updates_observation,
        "updates service",
        lambda: updates_release(framework["version"]),
        required=False,
    ) if framework.get("eligible") else None
    catalog = _catalog_observation(args, candidate)
    plan = make_plan(
        candidate,
        github=github,
        updates=updates,
        catalog=catalog,
        channel=args.channel,
    )
    write_document(args.plan, plan, "plan")
    return plan


def _catalog_observation(args, candidate):
    if args.catalog_observation:
        return read_json_source(args.catalog_observation, "plugin catalog", required=True)
    candidates = candidate["plugins"]["candidates"]
    if not candidates:
        return None
    if args.plugin_directory_repo is None:
        raise ReleaseError("--plugin-directory-repo is required to verify a non-empty remote catalog")
    return verified_catalog(args.plugin_directory_repo.resolve())


def _observation_or_live(source, label, live, *, required):
    if source:
        return read_json_source(source, label, required=required)
    return live()


if __name__ == "__main__":
    sys.exit(main())
