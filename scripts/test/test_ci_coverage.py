#!/usr/bin/env python3
"""Structural CI coverage contract: workflow execution, task reachability and sources."""
from __future__ import annotations

import fnmatch
import re
import shlex
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*|[A-Za-z_][\w:.-]*|[^\s]')
REGISTER = re.compile(
    r'(?:val\s+(\w+)\s+by\s+tasks\.registering(?:\([^\n]*?\))?'
    r'|tasks\.(?:register|named)(?:<[^>]+>)?\("([^"\n]+)"[^\n]*?\)|([A-Za-z_]\w*)\.configure)\s*\{')


def balanced(tokens: list[str], start: int, opening: str, closing: str) -> int:
    depth = 1
    for index in range(start + 1, len(tokens)):
        depth += (tokens[index] == opening) - (tokens[index] == closing)
        if depth == 0:
            return index
    raise AssertionError(f"unclosed {opening}")


def kotlin_tokens(text: str) -> list[str]:
    return [t for t in TOKEN.findall(text) if not t.startswith(('//', '/*'))]


def task_bodies(text: str) -> dict[str, list[str]]:
    bodies: dict[str, list[str]] = {}
    # Comments are removed before locating declarations; strings stay atomic when
    # matching braces, so embedded Java/shell fixtures cannot truncate a task.
    text = re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*',
                  lambda m: '' if m[0].startswith(('//', '/*')) else m[0], text)
    for match in REGISTER.finditer(text):
        tokens = kotlin_tokens(text[match.end() - 1:])
        end = balanced(tokens, 0, '{', '}')
        bodies.setdefault(match[1] or match[2] or match[3], []).extend(tokens[1:end])
    return bodies


def dependency_graph(bodies: dict[str, list[str]]) -> dict[str, set[str]]:
    graph = {}
    for name, tokens in bodies.items():
        edges = set()
        for index, token in enumerate(tokens[:-1]):
            if token == 'dependsOn' and tokens[index + 1] == '(':
                end = balanced(tokens, index + 1, '(', ')')
                edges.update(t.strip('"') for t in tokens[index + 2:end]
                             if t.strip('"') in bodies or t.startswith('\":'))
                if 'ordinaryTestTasks' in tokens[index + 2:end]:
                    edges.add('ordinaryTestTasks')
        graph[name] = edges
    return graph


def reachable(graph: dict[str, set[str]], roots: set[str]) -> set[str]:
    seen = set()
    pending = list(roots)
    while pending:
        name = pending.pop()
        if name not in seen:
            seen.add(name)
            pending.extend(graph.get(name, ()))
    return seen


def executable_commands(document: dict, release: bool = False) -> list[list[str]]:
    commands = []
    for job in document.get('jobs', {}).values():
        assert not job.get('continue-on-error'), 'CI job must propagate failures'
        for step in job.get('steps', []):
            if 'run' not in step:
                continue
            for line in step['run'].replace('\\\n', ' ').splitlines():
                if release and 'checkRelease' not in line:
                    continue
                tokens = shlex.split(line, comments=True)
                if release and 'checkRelease' not in tokens:
                    continue
                if tokens and './gradlew' in tokens:
                    assert not step.get('if') and not step.get('continue-on-error'), 'conditional gate'
                    assert release or not job.get('if'), 'conditional CI job'
                    assert tokens[0] in ('./gradlew', 'xvfb-run'), 'gate must execute directly'
                    assert not any(x in line for x in ('&&', '||', ';', '`', '$(', '|')), 'shell gate bypass'
                    prefix = tokens[:tokens.index('./gradlew')]
                    if prefix:
                        index = 1
                        while index < len(prefix):
                            option = prefix[index]
                            assert option in {'-a', '--auto-servernum', '-s', '--server-args'}, 'xvfb must execute Gradle'
                            index += 2 if option in {'-s', '--server-args'} else 1
                        assert index == len(prefix), 'missing xvfb option value'
                    args = tokens[tokens.index('./gradlew') + 1:]
                    assert not any(x in args for x in ('-m', '--dry-run', '-x', '--exclude-task')), 'disabled gate'
                    if release:
                        args = [x for x in args if not (x.startswith('-PinstallerVersion=') or x == '-PturboismRelease=true')]
                    assert not any(x.startswith(('-P', '-I', '--exclude-task=', '--tests')) for x in args), 'filtered gate'
                    assert set(args) <= {'devCheck', 'checkCompletedCommit', 'checkRelease',
                                         '--no-daemon', '--offline', '--console=plain',
                                         '--stacktrace', '--continue'}, 'unexpected gate argument'
                    commands.append(args)
    return commands


def check_workflow(root: Path) -> None:
    workflow = yaml.safe_load((root / '.github/workflows/ci.yml').read_text())
    triggers = workflow.get('on', workflow.get(True, {}))
    assert 'pull_request' in triggers and 'push' in triggers, 'missing ordinary CI event'
    assert not triggers['pull_request'], 'pull requests must not be path/branch filtered'
    assert triggers['push'] == {'branches': ['main']}, 'main pushes must run CI'
    assert workflow.get('permissions') == {'contents': 'read'}, 'CI must be read-only'
    assert 'secrets.' not in str(workflow), 'ordinary CI must not consume secrets'
    assert any({'devCheck', 'checkCompletedCommit'} <= set(command)
               for command in executable_commands(workflow)), 'CI must execute both aggregates'
    guard_runs = [step for job in workflow['jobs'].values() for step in job.get('steps', [])
                  if step.get('run', '').strip() == 'python3 scripts/test/test_ci_coverage.py']
    assert guard_runs and all(not step.get('if') and not step.get('continue-on-error')
                              for step in guard_runs), 'CI must execute its coverage guard'
    for job in workflow['jobs'].values():
        assert not job.get('permissions'), 'job must inherit read-only permissions'

    for name in ('unified-channel-checks.yml', 'release.yml'):
        document = yaml.safe_load((root / '.github/workflows' / name).read_text())
        assert any('checkRelease' in command for command in executable_commands(document, release=True)), name + ' must execute checkRelease'


def check_tasks(root: Path) -> tuple[dict[str, list[str]], set[str]]:
    bodies = {}
    for file in [root / 'build.gradle.kts', *sorted((root / 'gradle').glob('*.gradle.kts'))]:
        for name, tokens in task_bodies(file.read_text()).items():
            bodies.setdefault(name, []).extend(tokens)
    graph = dependency_graph(bodies)
    covered = reachable(graph, {'devCheck', 'checkCompletedCommit'})
    release = reachable(graph, {'checkRelease'})
    assert 'checkCompletedCommit' in release, 'release gate must include completed commits'
    # Host gates intentionally launch/inspect a real host; release-only gates
    # belong to checkRelease. Every new ordinary gate or SelfTest must be wired.
    missing = {name for name, body in bodies.items()
               if (name.startswith('check') or name.endswith('SelfTest'))
               and name not in covered and name not in release and name not in MANUAL_GATES
               and '$' not in name
               and not any('host verification' in token for token in body)}
    assert not missing, f'unreachable gates: {sorted(missing)}'
    assert 'checkHostToolingSelfTest' in covered, 'host tooling selftest is orphaned'
    assert 'checkHostToolingSelfTest' not in reachable(graph, {'devCheck'}), 'host tooling belongs to completed commits'
    assert 'ordinaryTestTasks' in covered, 'all module tests must run on completed commits'
    for name in OPT_IN_TESTS:
        assert name in bodies, f'missing opt-in task: {name}'
        assert name not in reachable(graph, {'devCheck', 'checkIntegration', 'checkCompletedCommit'}), f'opt-in task became gated: {name}'
        assert '"host verification"' in bodies[name], f'opt-in task must be labelled: {name}'
    return bodies, covered


def check_sources(root: Path, bodies: dict[str, list[str]], covered: set[str]) -> None:
    # Discover patterns are executable selection, rather than mere input files.
    selected = []
    opt_in_commands = {}
    for name in covered | OPT_IN_TESTS.keys():
        body = bodies.get(name, [])
        for index, token in enumerate(body[:-1]):
            if token == 'commandLine' and body[index + 1] == '(':
                end = balanced(body, index + 1, '(', ')')
                if name in OPT_IN_TESTS:
                    opt_in_commands[name] = [t.strip('"') for t in body[index + 2:end] if t.startswith('"')]
                selected.extend(t.strip('"') for t in body[index + 2:end] if t.startswith('"'))
    assert opt_in_commands == OPT_IN_TESTS, 'missing or changed opt-in execution command'
    runner = root / 'scripts/test/run_host_tooling_selftest.py'
    assert str(runner.relative_to(root)) in selected, 'host tooling runner must execute'
    selected.extend(re.findall(r'"(test_[^"\n]+\.(?:py|sh))"', runner.read_text()))
    # Existing manual/evidence tests are an explicit inventory, never a wildcard:
    # adding a new source requires routing it or deliberately reviewing an exemption.
    manual = set(MANUAL_TESTS.split())
    for file in sorted((root / 'scripts').rglob('test_*')):
        if file.suffix not in ('.py', '.sh'):
            continue
        relative = file.relative_to(root).as_posix()
        module = relative[:-3].replace('/', '.') if file.suffix == '.py' else ''
        assert (relative in selected or module in selected or
                any(fnmatch.fnmatchcase(file.name, pattern) for pattern in selected) or
                relative in manual or relative == 'scripts/test/test_ci_coverage.py'), f'ungated test: {relative}'
    assert 'ordinaryTestTasks' in covered
    # Java test directories must belong to a module included in settings, where
    # checkCompletedCommit discovers its ordinary Test task automatically.
    settings = (root / 'settings.gradle.kts').read_text()
    for source in root.glob('**/src/test/java'):
        if 'build' in source.relative_to(root).parts:
            continue
        module = source.parents[2]
        path = ':' + module.relative_to(root).as_posix().replace('/', ':')
        assert (module / 'build.gradle.kts').is_file(), f'test source without module: {source}'
        assert f'"{path.lstrip(chr(58))}"' in settings or f'"{path}"' in settings, f'test module omitted from settings: {path}'


OPT_IN_TESTS = {
    'worktreeIdGateTest': ['bash', 'scripts/test/test_worktree_id_gate.sh'],
    'modelImport3dHostToolingTest': ['python3', '-m', 'unittest', 'discover', '-s', 'scripts/test', '-p', 'test_model_import_3d_*.py'],
}

# Explicit opt-in host probes and the Gradle lifecycle alias.
MANUAL_GATES = set("""check checkCubismHostValidationArguments
checkCubismHostValidationJavaEvidence checkFloatArrayValidationBundle
checkGraalPreviewLauncherContract checkGraalScriptHostValidation
checkGraalScriptHostValidationDryRun checkImageArchiveValidationBundle
checkResourceValidationBundle checkTextureUploadValidationBundle
checkWarpPositionValidationBundle""".split())

MANUAL_TESTS = """
scripts/monitor/test_release_api_monitor.py
scripts/release/test_build_identity.py
scripts/test/test_asm_supply_chain_admission.sh
scripts/test/test_atlas_host_validation.sh
scripts/test/test_atlas_image_host_validation.sh
scripts/test/test_atlas_image_shadow_host_validation.sh
scripts/test/test_backup_interactive_session_local.sh
scripts/test/test_build_channels.py
scripts/test/test_cubism_host_validation_arguments.sh
scripts/test/test_cubism_host_validation_cleanup.sh
scripts/test/test_cubism_host_validation_java_evidence.sh
scripts/test/test_cubism_host_validation_local.sh
scripts/test/test_cubism_host_validation_lock.sh
scripts/test/test_cubism_host_validation_project_copy.sh
scripts/test/test_external_psd_edit_persist_driver.sh
scripts/test/test_external_psd_performance_sampler.sh
scripts/test/test_fps_resize_driver.sh
scripts/test/test_graal_preview_launcher_contract.sh
scripts/test/test_graal_script_host_validation_dry_run.sh
scripts/test/test_host_memory_identity.py
scripts/test/test_host_resource_counters.py
scripts/test/test_host_validation_env.sh
scripts/test/test_mapping_review_wrapper_args.sh
scripts/test/test_mcp_host_validation_client.py
scripts/test/test_nightly.py
scripts/test/test_official_psd_fixture_preparation.sh
scripts/test/test_official_psd_replacement_baseline.sh
scripts/test/test_official_second_document_open.sh
scripts/test/test_prepare_market_release.py
scripts/test/test_prerelease.py
scripts/test/test_product_cli.py
scripts/test/test_release_details.py
scripts/test/test_release_orchestrator.py
scripts/test/test_release_promotion.py
scripts/test/test_release_tooling.py
scripts/test/test_sdk_api_reference_builder.sh
scripts/test/test_sdk_v8_linkage.sh
scripts/test/test_status_bar_host_probe_packaging.sh
scripts/test/test_texture_atlas_sdk_linkage.sh
scripts/test/test_uniform_location_trial.sh
scripts/test_corpus.py
"""


def self_test() -> None:
    bodies = task_bodies('''
        val devCheck by tasks.registering { dependsOn(checkOne) }
        val checkOne by tasks.registering(Exec::class) { commandLine("echo", "}") }
        tasks.register<Exec>("checkNewSelfTest") { commandLine("python3", "test_new.py") }
        tasks.named("devCheck") { dependsOn("checkNewSelfTest") }
    ''')
    graph = dependency_graph(bodies)
    assert reachable(graph, {'devCheck'}) == {'devCheck', 'checkOne', 'checkNewSelfTest'}
    graph['devCheck'].remove('checkNewSelfTest')
    assert 'checkNewSelfTest' not in reachable(graph, {'devCheck'})
    for option in ('--dry-run', '-x test', '--tests Foo'):
        workflow = {'jobs': {'ci': {'steps': [{'run': f'./gradlew devCheck checkCompletedCommit {option}'}]}}}
        try:
            executable_commands(workflow)
        except AssertionError:
            pass
        else:
            raise AssertionError(f'gate bypass accepted: {option}')

    try:
        executable_commands({'jobs': {'ci': {'steps': [
            {'run': 'xvfb-run -a echo ./gradlew devCheck checkCompletedCommit'}]}}})
    except AssertionError:
        pass
    else:
        raise AssertionError('xvfb wrapping echo accepted')


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    self_test()
    check_workflow(root)
    bodies, covered = check_tasks(root)
    check_sources(root, bodies, covered)
    print('PASS: CI aggregates, gate dependencies and test source coverage')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
