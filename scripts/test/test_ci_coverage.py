#!/usr/bin/env python3
"""Structural CI coverage contract: workflow execution, manifest gate graph and sources.

The gate graph is read from `gradle/gates.txt`, the manifest `gradle/verification.gradle.kts`
registers the aggregates from, so gate membership has exactly one statement. The Kotlin in the gate
scripts is still tokenized for one purpose only: the inventory of commands a gate executes, which is
how test-source coverage is proven.
"""
from __future__ import annotations

import fnmatch
import re
import shlex
import sys
import unittest
from pathlib import Path
from typing import NamedTuple

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
    """Edges between two non-aggregate tasks, read from the script that wires them.

    Aggregate membership comes from the manifest; this is the leaf wiring a member's own
    helper tasks keep local, and the contract needs it to see what a gate really runs.
    """
    graph = {}
    for name, tokens in bodies.items():
        edges = set()
        for index, token in enumerate(tokens[:-1]):
            if token == 'dependsOn' and tokens[index + 1] == '(':
                end = balanced(tokens, index + 1, '(', ')')
                edges.update(t.strip('"') for t in tokens[index + 2:end]
                             if t.strip('"') in bodies or t.startswith('\":'))
        graph[name] = edges
    return graph


GATES_MANIFEST = Path('gradle/gates.txt')


class Aggregate(NamedTuple):
    """One declared aggregate gate, in declaration order."""

    name: str
    group: str
    owner: str
    harm: str
    description: str
    release_flags: bool
    require_xvfb: bool
    members: tuple[str, ...]


class Route(NamedTuple):
    """Why a gate is not an aggregate member."""

    task: str
    kind: str
    reason: str
    documentation: str


class Manifest(NamedTuple):
    """The parsed manifest: declaration order, aggregates and routes."""

    aggregates: dict[str, Aggregate]
    order: tuple[str, ...]
    routes: dict[str, Route]

    def is_routed(self, task: str) -> bool:
        return task in self.routes


def strip_comment(line: str) -> str:
    quoted = False
    for index, character in enumerate(line):
        if character == '"':
            quoted = not quoted
        elif character == '#' and not quoted:
            return line[:index]
    return line


def split_words(text: str) -> list[str]:
    """Whitespace split that keeps `key="value with spaces"` atomic."""
    words: list[str] = []
    current: list[str] = []
    quoted = False
    for character in text:
        if character == '"':
            quoted = not quoted
        if not quoted and character.isspace():
            if current:
                words.append(''.join(current))
                current = []
            continue
        current.append(character)
    if current:
        words.append(''.join(current))
    return words


def parse_manifest(source: str, path: str = str(GATES_MANIFEST)) -> Manifest:
    """Parse the manifest, failing closed on every malformed line at once.

    This mirrors `dev.turboism.gradle.internal.GateManifest`: the Java reader registers the tasks
    from the same file, so both must accept and reject exactly the same declarations.
    """
    declared: dict[str, dict] = {}
    members: dict[str, list[str]] = {}
    routes: dict[str, Route] = {}
    problems: list[str] = []
    for number, raw in enumerate(source.splitlines(), 1):
        where = f'{path}:{number}'
        line = strip_comment(raw).strip()
        if not line:
            continue
        words = split_words(line)
        directive = words[0]
        if len(words) < 2:
            problems.append(f'{where}: "{directive}" needs a name')
            continue
        name = words[1]
        attributes: dict[str, str] = {}
        flags: set[str] = set()
        if directive not in ('aggregate', 'member', 'manual', 'opt-in', 'orphan'):
            problems.append(f'{where}: unknown directive "{directive}"')
            continue
        for word in words[2:]:
            key, separator, value = word.partition('=')
            if not separator:
                flags.add(word)
            elif len(value) < 2 or not value.startswith('"') or not value.endswith('"'):
                problems.append(f'{where}: "{word}" must be written as {key}="value"')
            else:
                attributes.setdefault(key, value[1:-1])
        if directive == 'aggregate':
            if name in declared:
                problems.append(f'{where}: aggregate "{name}" is declared twice')
                continue
            declared[name] = {'attributes': attributes, 'flags': flags, 'where': where}
        elif directive == 'member':
            if len(words) < 3:
                problems.append(f'{where}: member of "{name}" names no task')
                continue
            members.setdefault(name, []).append(words[2])
        else:
            reason = attributes.get('reason', '')
            if not reason:
                problems.append(f'{where}: {directive} route for "{name}" is missing reason')
            if name in routes:
                problems.append(f'{where}: "{name}" is already routed')
            routes[name] = Route(name, directive, reason, attributes.get('doc', ''))
    for aggregate_name in sorted(members):
        if aggregate_name not in declared:
            problems.append(f'{path}: member belongs to undeclared aggregate "{aggregate_name}"')
    aggregates: dict[str, Aggregate] = {}
    for name, declaration in declared.items():
        attributes = declaration['attributes']
        for attribute in ('group', 'owner', 'harm', 'description'):
            if not attributes.get(attribute):
                problems.append(f'{declaration["where"]}: aggregate "{name}" is missing {attribute}')
        aggregates[name] = Aggregate(name, attributes.get('group', ''), attributes.get('owner', ''),
                                     attributes.get('harm', ''), attributes.get('description', ''),
                                     'release-flags' in declaration['flags'],
                                     'require-xvfb' in declaration['flags'],
                                     tuple(members.get(name, ())))
    for route in routes.values():
        if any(route.task in aggregate.members for aggregate in aggregates.values()):
            problems.append(f'{path}: routed task "{route.task}" is also an aggregate member')
    if problems:
        raise AssertionError('malformed gate manifest:\n  ' + '\n  '.join(problems))
    return Manifest(aggregates, tuple(declared), routes)


def load_manifest(root: Path) -> Manifest:
    return parse_manifest((root / GATES_MANIFEST).read_text(encoding='utf-8'), str(GATES_MANIFEST))


def aggregate_graph(manifest: Manifest) -> dict[str, set[str]]:
    """Declared membership as a graph: a member that is not an aggregate is a leaf."""
    return {name: set(aggregate.members) for name, aggregate in manifest.aggregates.items()}

def reachable(graph: dict[str, set[str]], roots: set[str]) -> set[str]:
    seen = set()
    pending = list(roots)
    while pending:
        name = pending.pop()
        if name not in seen:
            seen.add(name)
            pending.extend(graph.get(name, ()))
    return seen


SIMULATION_FLAGS = {'-s', '--simulate', '--just-print', '--dry-run', '--recon', '--no-act', '-d', '--download-only'}


def is_xvfb_install_line(line: str) -> bool:
    if any(x in line for x in ('&&', '||', ';', '`', '$(', '|')):
        return False
    try:
        tokens = shlex.split(line, comments=True)
    except ValueError:
        return False
    if not tokens:
        return False
    cmd_start = 1 if tokens[0] == 'sudo' else 0
    if cmd_start < len(tokens) and tokens[cmd_start] in ('apt', 'apt-get'):
        sub_tokens = tokens[cmd_start + 1:]
        subcmd = next((t for t in sub_tokens if not t.startswith('-')), None)
        if subcmd == 'install' and 'xvfb' in sub_tokens:
            return not any(flag in sub_tokens for flag in SIMULATION_FLAGS)
    return False


# Aggregates a workflow may launch directly. `checkReleasePackaging` is the
# separately invocable release-only gate; the formal candidate keeps the complete
# `checkRelease` gate. These four names are the contract's expectation of the manifest,
# not a copy of it: `check_manifest` asserts gradle/gates.txt declares them.
GATE_TASKS = ('devCheck', 'checkCompletedCommit', 'checkReleasePackaging', 'checkRelease')
ORDINARY_GATES = frozenset({'devCheck', 'checkCompletedCommit'})
PACKAGING_GATE = 'checkReleasePackaging'
FORMAL_RELEASE_GATE = 'checkRelease'
ALLOWED_GATE_ARGS = frozenset({
    *GATE_TASKS, ':buildSrc:test',
    '--no-daemon', '--offline', '--console=plain', '--stacktrace', '--continue',
})
# Both release gates bind a resolved installer identity and the release flag; only
# the formal candidate gate must also execute under the pinned Xvfb display.
RELEASE_FLAGS = (PACKAGING_GATE, FORMAL_RELEASE_GATE)
XVFB_GATES = (FORMAL_RELEASE_GATE,)


def gate_options(manifest: Manifest) -> dict[str, dict[str, bool]]:
    """The display and identity requirements the manifest declares, per launched gate."""
    return {gate: {'release_flags': manifest.aggregates[gate].release_flags,
                   'require_xvfb': manifest.aggregates[gate].require_xvfb}
            for gate in GATE_TASKS if gate in manifest.aggregates}


def gate_commands(document: dict, gate: str | None = None, *, release_flags: bool = False,
                  require_xvfb: bool = False) -> list[list[str]]:
    """Validate deterministic Gradle aggregate invocations and return their arguments.

    `gate=None` collects every aggregate invocation. A release gate additionally has
    to bind an explicit installer identity (`-PinstallerVersion` that comes from the
    resolved build identity, never a literal) plus `-PturboismRelease=true`; the
    formal candidate gate also has to execute under the pinned Xvfb display.
    """
    commands: list[list[str]] = []
    for job in document.get('jobs', {}).values():
        assert not job.get('continue-on-error'), 'CI job must propagate failures'
        xvfb_installed = False
        for step in job.get('steps', []):
            if 'run' not in step:
                continue
            is_unconditional_step = not step.get('if') and not step.get('continue-on-error')
            for line in step['run'].replace('\\\n', ' ').splitlines():
                if is_unconditional_step and is_xvfb_install_line(line):
                    xvfb_installed = True
                if './gradlew' not in line:
                    continue
                tokens = shlex.split(line, comments=True)
                if not tokens or './gradlew' not in tokens:
                    continue
                if gate is None:
                    if not any(name in tokens for name in GATE_TASKS) and ':buildSrc:test' not in tokens:
                        continue
                elif gate not in tokens:
                    continue
                assert not step.get('if') and not step.get('continue-on-error'), 'conditional gate'
                assert require_xvfb or not job.get('if'), 'conditional CI job'
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
                if require_xvfb:
                    assert prefix and tokens[0] == 'xvfb-run', 'release gate must execute under xvfb-run'
                    assert '-screen 0 1920x1080x24' in prefix, 'xvfb-run must specify standard screen geometry'
                    assert xvfb_installed, 'release gate requires xvfb to be installed in a preceding unconditional step'
                args = tokens[tokens.index('./gradlew') + 1:]
                assert not any(x in args for x in ('-m', '--dry-run', '-x', '--exclude-task')), 'disabled gate'
                if release_flags:
                    identities = [x for x in args if x.startswith('-PinstallerVersion=')]
                    assert len(identities) == 1, 'release gate must bind one installer version'
                    assert '$' in identities[0], 'release gate installer identity must come from the build identity'
                    assert '-PturboismRelease=true' in args, 'release gate must set -PturboismRelease=true'
                    args = [x for x in args if x not in identities and x != '-PturboismRelease=true']
                assert not any(x.startswith(('-P', '-I', '--exclude-task=', '--tests')) for x in args), 'filtered gate'
                assert set(args) <= ALLOWED_GATE_ARGS, 'unexpected gate argument'
                commands.append(args)
    return commands


def workflow_document(root: Path, name: str) -> dict:
    return yaml.safe_load((root / '.github/workflows' / name).read_text(encoding='utf-8'))


def job_runs(job: dict) -> str:
    return '\n'.join(step.get('run', '') for step in job.get('steps', []))


def workflow_gates(root: Path, options: dict[str, dict[str, bool]]) -> dict[str, set[str]]:
    """Every aggregate gate each workflow really launches, validated for direct execution."""
    gates = {}
    for path in sorted((root / '.github/workflows').glob('*.yml')):
        document = yaml.safe_load(path.read_text(encoding='utf-8'))
        gates[path.name] = {gate for gate in GATE_TASKS
                            if gate_commands(document, gate, **options.get(gate, {}))}
    return gates


# A full regression suite executes once in the repository; a second copy is
# duplication, not extra coverage.
DEDUP_MARKERS = (('node --test distribution/test/*.test.mjs', 'the Node distribution suite'),
                 ('wrangler@4.127.0 deploy --dry-run', 'the Worker dry-run'),
                 ('scripts/check_remote_hygiene.py --worktree', 'the worktree hygiene scan'))

CHANNEL_SWITCH_CHAIN = ['beta', 'nightly', 'stable']


def check_workflow(root: Path, gates: dict[str, set[str]]) -> None:
    workflow = workflow_document(root, 'ci.yml')
    triggers = workflow.get('on', workflow.get(True, {}))
    assert 'pull_request' in triggers and 'push' in triggers, 'missing ordinary CI event'
    assert not triggers['pull_request'], 'pull requests must not be path/branch filtered'
    assert triggers['push'] == {'branches': ['main']}, 'main pushes must run CI'
    assert workflow.get('permissions') == {'contents': 'read'}, 'CI must be read-only'
    assert 'secrets.' not in str(workflow), 'ordinary CI must not consume secrets'
    assert 'devCheck' in {job.get('name') for job in workflow['jobs'].values()}, \
        'the required devCheck check name must stay'
    assert gates['ci.yml'] == set(ORDINARY_GATES), \
        f'CI must execute both ordinary aggregates: {sorted(gates["ci.yml"])}'
    assert any(ORDINARY_GATES <= set(command) for command in gate_commands(workflow)), \
        'CI must execute both aggregates in one invocation'
    guard_runs = [step for job in workflow['jobs'].values() for step in job.get('steps', [])
                  if step.get('run', '').strip() == 'python3 scripts/test/test_ci_coverage.py']
    assert guard_runs and all(not step.get('if') and not step.get('continue-on-error')
                              for step in guard_runs), 'CI must execute its coverage guard'
    for job in workflow['jobs'].values():
        assert not job.get('permissions'), 'job must inherit read-only permissions'

    # One owner per responsibility: the required lane, the release-only delta and the
    # formal candidate. Any other workflow launching an aggregate duplicates a closure.
    assert gates['unified-channel-checks.yml'] == {PACKAGING_GATE}, \
        'unified must execute the release packaging gate and no other closure'
    assert gates['release.yml'] == {FORMAL_RELEASE_GATE}, \
        'release.yml must execute the complete release gate'
    assert {name for name, found in gates.items() if found} == {
        'ci.yml', 'unified-channel-checks.yml', 'release.yml'}, 'an extra workflow executes an aggregate gate'

    for marker, label in DEDUP_MARKERS:
        owners = [path.name for path in sorted((root / '.github/workflows').glob('*.yml'))
                  if marker in path.read_text(encoding='utf-8')]
        assert owners == ['unified-channel-checks.yml'], f'{label} must run exactly once: {owners}'
    unified = workflow_document(root, 'unified-channel-checks.yml')
    runs = '\n'.join(job_runs(job) for job in unified['jobs'].values())
    # Unique archival responsibility: exact source plus the tag history bundle.
    for marker in ('git archive HEAD', 'git bundle create artifacts/history.bundle'):
        assert marker in runs, f'unified must keep its source/history archive: {marker}'
    events = unified.get('on', unified.get(True)) or {}
    assert all('release-notes/**' not in ((events.get(event) or {}).get('paths') or [])
               for event in ('pull_request', 'push')), \
        'notes-only data changes must not schedule the channel packaging matrix'
    check_channel_topology(unified)
    check_history_gate(root)


def check_channel_topology(document: dict) -> None:
    """One serial no-clean channel chain and one real per-channel package matrix."""
    chains = {name: job for name, job in document['jobs'].items()
              if 'FrameworkBuildInfoTest' in job_runs(job)}
    assert len(chains) == 1, f'the no-clean channel chain must run in exactly one job: {sorted(chains)}'
    job = next(iter(chains.values()))
    assert not job.get('strategy'), 'the channel switch chain must not be duplicated per channel'
    found = re.findall(r'-PturboismChannel=([a-z]+)', job_runs(job))
    assert found == CHANNEL_SWITCH_CHAIN, f'the chain must run beta->nightly->stable once: {found}'
    # A QA identity injected first makes the beta/nightly runs fail the channel
    # consistency check instead of exercising a real switch.
    runs = [step.get('run', '') for step in job['steps']]
    chain = next(index for index, run in enumerate(runs) if 'FrameworkBuildInfoTest' in run)
    identity = next((index for index, run in enumerate(runs) if 'TURBOISM_BUILD_CHANNEL=' in run), None)
    assert identity is None or chain < identity, \
        'the QA identity must be injected after the no-clean channel chain'

    packages = [(name, job) for name, job in document['jobs'].items()
                if ((job.get('strategy') or {}).get('matrix') or {}).get('channel')]
    assert len(packages) == 1, f'exactly one channel package matrix is allowed: {[n for n, _ in packages]}'
    name, package = packages[0]
    assert package['strategy']['matrix']['channel'] == ['stable', 'beta', 'nightly'], 'channel matrix changed'
    assert 'release-gates' in str(package.get('needs')), 'channel packages must consume the shared upstream gate'
    package_runs = job_runs(package)
    for marker in ('assemble-release.sh', 'scripts/release/verify-release.py',
                   'scripts/test/verify_built_identity.py'):
        assert marker in package_runs, f'channel packaging lost real artifact verification: {marker}'


def check_history_gate(root: Path) -> None:
    """The required history gate scans reachable history and rotates its boundary cache."""
    document = workflow_document(root, 'remote-hygiene.yml')
    assert 'pull_request' in (document.get('on', document.get(True)) or {}), \
        'history hygiene must gate every pull request'
    job = next(iter(document['jobs'].values()))
    assert job.get('name') == 'scan reachable history', 'the required history check name must stay'
    assert 'scripts/check_remote_hygiene.py --all' in job_runs(job), 'the history gate must scan reachable history'
    fingerprint = "hashFiles('scripts/check_remote_hygiene.py')"
    for name in ('remote-hygiene.yml', 'release.yml'):
        steps = [step for job in workflow_document(root, name)['jobs'].values()
                 for step in job.get('steps', [])]
        assert any('--boundary-file' in step.get('run', '') for step in steps), \
            f'{name} must consume the verified boundary file'
        cache = next(step for step in steps
                     if 'remote-hygiene-boundary.json' in str((step.get('with') or {}).get('path', '')))
        key, restore = str(cache['with']['key']), str(cache['with'].get('restore-keys', ''))
        assert fingerprint in key and 'github.run_id' in key, \
            f'{name} boundary cache key must rotate per run (immutable cache keys)'
        assert fingerprint in restore, f'{name} boundary cache must restore by the rules fingerprint'


def build_script_bodies(root: Path) -> dict[str, list[str]]:
    """Every declared task body in the build scripts, keyed by task name.

    The manifest owns aggregate membership; this inventory exists so the contract can see which
    commands a gate executes and which gate tasks a script declares without a manifest line.
    """
    bodies: dict[str, list[str]] = {}
    for file in sorted(root.glob('**/*.gradle.kts')):
        relative = file.relative_to(root).parts
        if 'build' in relative or 'buildSrc' in relative:
            continue
        for name, tokens in task_bodies(file.read_text(encoding='utf-8')).items():
            bodies.setdefault(name, []).extend(tokens)
    return bodies


def member_names(gates: set[str]) -> set[str]:
    """Members keyed the way a build script declares them: `:module:task` also answers to `task`."""
    return {name.rsplit(':', 1)[-1] for name in gates if not name.startswith('@')}


def check_manifest(manifest: Manifest, graph: dict[str, set[str]]) -> None:
    """The manifest declares the launched aggregates and composes them as the contract expects."""
    for gate in GATE_TASKS:
        assert gate in manifest.aggregates, f'gradle/gates.txt must declare {gate}'
    release = reachable(graph, {FORMAL_RELEASE_GATE})
    assert 'checkCompletedCommit' in release, 'release gate must include completed commits'
    # The formal gate is exactly the public completed-commit closure plus the
    # separately invocable release-only gate. A release check that is added to one
    # side but not the other fails here instead of silently leaving the PR flow.
    composition = reachable(graph, {'checkCompletedCommit', PACKAGING_GATE}) | {FORMAL_RELEASE_GATE}
    assert composition == release, (
        'checkRelease must equal checkCompletedCommit plus the release packaging gate; '
        f'missing: {sorted(release - composition)}; unexpected: {sorted(composition - release)}')
    # The identity and display requirements stay bound to the gates that need them: the
    # workflow invocation check below is only as strong as these flags.
    options = gate_options(manifest)
    for gate in GATE_TASKS:
        expected = {'release_flags': gate in RELEASE_FLAGS, 'require_xvfb': gate in XVFB_GATES}
        assert options[gate] == expected, \
            f'{gate} declares {options[gate]} but the workflow contract requires {expected}'


def check_gate_ownership(root: Path, manifest: Manifest, gates: dict[str, set[str]]) -> None:
    """`owner=` names the workflow and line that launch the aggregate, and every gate is reached."""
    for gate in GATE_TASKS:
        owner = manifest.aggregates[gate].owner
        assert not owner.startswith('via:'), f'{gate} is launched by a workflow, not through another gate'
        path, _, line = owner.rpartition(':')
        assert line.isdigit(), f'{gate} owner must be <workflow>:<line>: {owner}'
        workflows = sorted(name for name, found in gates.items() if gate in found)
        assert workflows == [Path(path).name], f'{gate} is launched by {workflows}, not {path}'
        source = (root / path).read_text(encoding='utf-8').splitlines()
        assert int(line) <= len(source), f'{owner} is past the end of {path}'
        invocation = source[int(line) - 1]
        assert './gradlew' in invocation and gate in invocation, \
            f'{owner} does not launch {gate}: {invocation!r}'
    graph = aggregate_graph(manifest)
    launched = {gate for gate in GATE_TASKS if not manifest.aggregates[gate].owner.startswith('via:')}
    unreachable = [name for name in manifest.order if name not in reachable(graph, launched)]
    assert not unreachable, f'aggregates no workflow reaches: {unreachable}'


def check_declarations(root: Path, manifest: Manifest) -> None:
    """The manifest is the only statement of aggregate membership.

    Every aggregate is registered from gradle/gates.txt in gradle/verification.gradle.kts and no
    other script re-opens one, so a member is added in one place instead of being patched from
    whichever script owns the task.
    """
    reopen = re.compile(r'tasks\.(?:named|register|getByName)(?:<[^>]+>)?\(\s*"([\w:]+)"')
    for file in [root / 'build.gradle.kts', *sorted((root / 'gradle').glob('*.gradle.kts'))]:
        if file.name == 'verification.gradle.kts':
            continue
        patched = sorted({name for name in reopen.findall(file.read_text(encoding='utf-8'))
                          if name in manifest.aggregates})
        assert not patched, f'{file.name} re-opens aggregate gates: {patched}'
    verification = (root / 'gradle/verification.gradle.kts').read_text(encoding='utf-8')
    assert 'GateManifest.load' in verification, \
        'gradle/verification.gradle.kts must register the aggregates from gradle/gates.txt'
    for gate in manifest.order:
        inline = re.search(rf'(?:val\s+{gate}\s+by\s+tasks\.registering'
                           rf'|tasks\.register(?:<[^>]+>)?\("{gate}"\))', verification)
        assert not inline, f'{gate} must be registered from gradle/gates.txt, not declared inline'
        assert not re.search(rf'tasks\.(?:named|getByName)\("{gate}"\)', verification), \
            f'{gate} must not be patched after its registration'


def ungated_tasks(bodies: dict[str, list[str]], graph: dict[str, set[str]],
                  manifest: Manifest) -> list[str]:
    """Declared gate tasks that no aggregate reaches and no route explains.

    The inventory is every `check*`/`*SelfTest`/`verify*` task any build script declares, including
    a module build file, so a gate that nobody wires cannot pass by being unlisted. Reachability
    starts at every declared aggregate; whether an aggregate itself is launched is asserted
    separately, so an aggregate that exists but no workflow starts fails on that line instead.
    """
    members = member_names(reachable(graph, set(manifest.aggregates)))
    return sorted(name for name in bodies
                  if (name.startswith('check') or name.endswith('SelfTest') or name.startswith('verify'))
                  and name not in members and not manifest.is_routed(name))


def check_gates(bodies: dict[str, list[str]], manifest: Manifest,
                graph: dict[str, set[str]]) -> set[str]:
    """Every gate task is a member or explicitly routed, so a gate cannot shrink silently.

    The inventory is every `check*`/`*SelfTest`/`verify*` task any build script declares, including
    a module build file: a gate that no aggregate reaches and no route explains fails here, instead
    of passing because nobody looked at it.
    """
    covered = reachable(graph, set(ORDINARY_GATES))
    release = reachable(graph, {FORMAL_RELEASE_GATE})
    orphans = ungated_tasks(bodies, graph, manifest)
    assert not orphans, ('ungated gates: add a member line, or a manual/opt-in/orphan route, '
                         f'in gradle/gates.txt: {orphans}')
    assert 'checkHostToolingSelfTest' in covered, 'host tooling selftest is orphaned'
    assert 'checkHostToolingSelfTest' not in reachable(graph, {'devCheck'}), \
        'host tooling belongs to completed commits'
    assert '@all-module-tests' in covered, 'all module tests must run on completed commits'
    assert 'checkLocalizedChangelogs' in covered, \
        'release-notes data changes must stay gated by the required changelog render check'
    for name in OPT_IN_TESTS:
        assert name in bodies, f'missing opt-in task: {name}'
        assert name not in covered | release, f'opt-in task became gated: {name}'
        assert '"host verification"' in bodies[name], f'opt-in task must be labelled: {name}'
    check_opt_in_gradle_tests(bodies, member_names(covered))
    for name in RETIRED_META_GATE_SELFTESTS:
        assert name not in bodies, f'retired meta-gate selftest is back: {name}'
    return covered


def check_opt_in_gradle_tests(bodies: dict[str, list[str]], covered: set[str]) -> None:
    """Opt-in host-evidence Test tasks must stay registered and stay ungated."""
    module_bodies: dict[str, list[str]] = {}
    for relative in OPT_IN_GRADLE_TEST_SOURCES:
        for name, tokens in task_bodies((ROOT / relative).read_text(encoding='utf-8')).items():
            module_bodies.setdefault(name, []).extend(tokens)
    gated_tokens = set()
    for name in covered:
        gated_tokens.update(bodies.get(name, ()))
    for name, evidence in OPT_IN_GRADLE_TESTS.items():
        assert name not in OPT_IN_TESTS, f'{name} cannot be both an Exec and a Test opt-in lane'
        assert name in module_bodies, f'missing opt-in Gradle test task: {name}'
        assert f':runtime:{name}' not in gated_tokens, f'opt-in Gradle test became gated: {name}'
        body = module_bodies[name]
        assert any(token in ('"verification"', '"host verification"') for token in body), \
            f'opt-in Gradle test must declare a verification group: {name}'
        assert 'includeTestsMatching' in body or 'excludeTestsMatching' in body, \
            f'opt-in Gradle test must define its own explicit selection: {name}'
        for required in evidence:
            assert f'"{required}"' in body, \
                f'opt-in Gradle test must declare its evidence input: {name} -> {required}'
    # The ordinary runtime suite must keep excluding the opt-in evidence classes so
    # enabling one lane cannot silently double-run them in the default suite.
    ordinary = module_bodies.get('test', [])
    for class_name in ('WarpAltMirrorReviewedArtifactsTest', 'ParameterLockReviewedArtifactsTest'):
        assert any(class_name in token for token in ordinary), \
            f'opt-in evidence class must stay excluded from :runtime:test: {class_name}'
    assert any(token.startswith('legacyEvidenceTests') for token in ordinary), \
        'the ordinary runtime suite must keep excluding legacy evidence tests'


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
    assert runner.relative_to(root).as_posix() in selected, 'host tooling runner must execute'
    selected.extend(re.findall(r'"(test_[^"\n]+\.(?:py|sh))"', runner.read_text(encoding='utf-8')))
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
    # Java test directories must belong to a module included in settings, where
    # checkCompletedCommit discovers its ordinary Test task automatically.
    # buildSrc is a special Gradle build that settings.gradle.kts cannot list; its
    # tests run through the explicit `:buildSrc:test` CI step instead.
    settings = (root / 'settings.gradle.kts').read_text(encoding='utf-8')
    ci_workflow = (root / '.github/workflows/ci.yml').read_text(encoding='utf-8')
    for source in root.glob('**/src/test/java'):
        if 'build' in source.relative_to(root).parts:
            continue
        module = source.parents[2]
        path = ':' + module.relative_to(root).as_posix().replace('/', ':')
        assert (module / 'build.gradle.kts').is_file(), f'test source without module: {source}'
        if path == ':buildSrc':
            assert ':buildSrc:test' in ci_workflow, (
                'buildSrc tests are not run by CI; add a `:buildSrc:test` step to ci.yml')
            continue
        assert f'"{path.lstrip(chr(58))}"' in settings or f'"{path}"' in settings, f'test module omitted from settings: {path}'


OPT_IN_TESTS = {
    'worktreeIdGateTest': ['bash', 'scripts/test/test_worktree_id_gate.sh'],
    'modelImport3dHostToolingTest': ['python3', 'validation/model-import-3d-host-probe/test-tooling.py', 'test_model_import_3d_*.py'],
}

# Opt-in Gradle Test tasks that need separately supplied Cubism bytecode evidence.
# They are declared in runtime/build.gradle.kts, are unreachable from devCheck,
# checkIntegration and checkCompletedCommit, and stay out of the ordinary `test`
# task through explicit filters. Listing them here keeps each one an intentional,
# reviewable lane instead of an orphaned task nobody runs.
OPT_IN_GRADLE_TESTS = {
    'warpAltMirrorArtifactTest': ['TURBOISM_CUBISM_REF_ROOT'],
    'parameterLockArtifactTest': ['TURBOISM_CUBISM_REF_ROOT'],
    'legacyCubismEvidenceTest': ['turboism.test.cubismEditorJar', 'turboism.test.verificationDir'],
}

# Test tasks that are deliberately only reachable through their own opt-in lane.
OPT_IN_GRADLE_TEST_SOURCES = ('runtime/build.gradle.kts',)

# Third-order meta-gates removed by the centralization slice: they proved the wiring
# and negative fixtures of other gates rather than the product. Keeping the names
# here makes an accidental re-introduction a review decision instead of a silent
# return of the task the slice deleted.
RETIRED_META_GATE_SELFTESTS = frozenset("""
checkAstGrepSelfTest checkCodeQualitySelfTest checkCompatibilityMatrixSelfTest
checkDependencyInventorySelfTest checkDraftPackMetadataSelfTest
checkEditorModelAliasesSelfTest checkExternalPluginTemplateSelfTest
checkGateWiringSelfTest checkGoldenSnapshotManifestSelfTest
checkInstallerPluginSectionsGateSelfTest checkMaterializedRecordsSelfTest
checkModuleBoundariesSelfTest checkRemoteHygieneSelfTest checkReviewedReleasesSelfTest
checkSdkCubismEditorAnnotationsSelfTest checkTestCorpusSelfTest
checkThrowableContainmentSelfTest checkVerificationSourcesSelfTest
generateClassPinsSelfTest
""".split())

# Negative-fixture suites whose meta-gate selftest task was retired above. The
# checker each one exercises is still an executing gate; the fixtures stay an
# explicit on-demand inventory so they can be re-wired deliberately rather than
# reappearing inside devCheck/checkCompletedCommit by accident.
MANUAL_TESTS = """
scripts/monitor/test_release_api_monitor.py
scripts/release/test_build_identity.py
scripts/test/test_asm_supply_chain_admission.sh
scripts/test/test_atlas_host_validation.sh
scripts/test/test_atlas_image_host_validation.sh
scripts/test/test_atlas_image_shadow_host_validation.sh
scripts/test/test_backup_interactive_session_local.sh
scripts/test/test_build_channels.py
scripts/test/test_check_ast_grep.py
scripts/test/test_check_code_quality.py
scripts/test/test_check_golden_snapshots.py
scripts/test/test_check_remote_hygiene.py
scripts/test/test_check_sdk_cubism_annotations.py
scripts/test/test_check_throwable_containment.py
scripts/test/test_cubism_host_validation_arguments.sh
scripts/test/test_cubism_host_validation_cleanup.sh
scripts/test/test_cubism_host_validation_java_evidence.sh
scripts/test/test_cubism_host_validation_local.sh
scripts/test/test_cubism_host_validation_lock.sh
scripts/test/test_cubism_host_validation_project_copy.sh
scripts/test/test_dependency_inventory.py
scripts/test/test_editor_model_aliases.py
scripts/test/test_external_psd_edit_persist_driver.sh
scripts/test/test_external_psd_performance_sampler.sh
scripts/test/test_fps_resize_driver.sh
scripts/test/test_generate_class_pins.py
scripts/test/test_generate_compatibility_matrix.py
scripts/test/test_generate_verification_sources.py
scripts/test/test_graal_preview_launcher_contract.sh
scripts/test/test_graal_script_host_validation_dry_run.sh
scripts/test/test_host_memory_identity.py
scripts/test/test_host_resource_counters.py
scripts/test/test_host_validation_env.sh
scripts/test/test_mapping_review_wrapper_args.sh
scripts/test/test_materialize_verification_records.py
scripts/test/test_mcp_host_validation_client.py
scripts/test/test_module_boundaries.py
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
scripts/test/test_reviewed_releases.py
scripts/test/test_reviewed_releases_index.py
scripts/test/test_reviewed_releases_r1.py
scripts/test/test_reviewed_releases_r2.py
scripts/test/test_reviewed_releases_r3.py
scripts/test/test_reviewed_releases_r4.py
scripts/test/test_reviewed_releases_ranges.py
scripts/test/test_status_bar_host_probe_packaging.sh
scripts/test/test_sync_draft_pack_metadata.py
scripts/test/test_test_corpus.py
scripts/test/test_uniform_location_trial.sh
scripts/test_corpus.py
"""


def self_test() -> None:
    # The manifest grammar: declaration order, route kinds and the fail-closed cases the
    # contract depends on. The Kotlin reader proves the same rules against the same shape.
    source = '''
        # A comment, and blank lines, are free.

        aggregate devCheck group="verification" owner=".github/workflows/ci.yml:76" harm="h" description="d"
        member devCheck checkOne
        member devCheck @all-module-tests
        aggregate release group="verification" owner="via:devCheck" harm="h" description="d" release-flags
        member release devCheck
        member release checkNewSelfTest
        manual checkManual reason="on demand" doc="docs/gates.md:1"
        opt-in verifySelectionBrushJar reason="module check anchor"
        orphan checkGraalHostTargetSelection reason="runs nowhere"
    '''
    manifest = parse_manifest(source, 'gates.txt')
    assert manifest.order == ('devCheck', 'release')
    assert manifest.aggregates['release'].release_flags
    assert manifest.aggregates['release'].members == ('devCheck', 'checkNewSelfTest')
    assert manifest.routes['checkManual'].kind == 'manual'
    assert manifest.routes['checkManual'].documentation == 'docs/gates.md:1'
    assert manifest.is_routed('checkGraalHostTargetSelection')
    assert not manifest.is_routed('checkOne')
    graph = aggregate_graph(manifest)
    assert reachable(graph, {'release'}) == {
        'release', 'devCheck', 'checkOne', '@all-module-tests', 'checkNewSelfTest'}
    # An orphan is a tombstone, not a free pass: dropping the route line must redden the rule
    # that keeps every declared gate wired.
    inventory = {name: [] for name in ('checkOne', 'checkNewSelfTest',
                                       'checkGraalHostTargetSelection', 'checkManual')}
    assert ungated_tasks(inventory, dependency_graph(inventory) | graph, manifest) == []
    unrouted = parse_manifest(source.replace(
        'orphan checkGraalHostTargetSelection reason="runs nowhere"', ''), 'gates.txt')
    assert ungated_tasks(inventory, dependency_graph(inventory) | aggregate_graph(unrouted),
                         unrouted) == ['checkGraalHostTargetSelection']
    graph['release'].remove('devCheck')
    assert 'checkOne' not in reachable(graph, {'release'})
    full = 'aggregate devCheck group="g" owner=".github/workflows/ci.yml:1" harm="h" description="d"'
    for broken, expected in (
            ('member devCheck checkOne', 'undeclared aggregate'),
            ('manual checkOne', 'missing reason'),
            (full + '\n' + full, 'declared twice')):
        try:
            parse_manifest(broken, 'gates.txt')
        except AssertionError as err:
            assert expected in str(err), (broken, expected, str(err))
        else:
            raise AssertionError(f'malformed manifest accepted: {broken}')

    for option in ('--dry-run', '-x test', '--tests Foo'):
        workflow = {'jobs': {'ci': {'steps': [{'run': f'./gradlew devCheck checkCompletedCommit {option}'}]}}}
        try:
            gate_commands(workflow)
        except AssertionError:
            pass
        else:
            raise AssertionError(f'gate bypass accepted: {option}')

    try:
        gate_commands({'jobs': {'ci': {'steps': [
            {'run': 'xvfb-run -a echo ./gradlew devCheck checkCompletedCommit'}]}}})
    except AssertionError:
        pass
    else:
        raise AssertionError('xvfb wrapping echo accepted')

    def rejected(steps: list[dict], expected: str, gate: str | None = None, **options) -> None:
        try:
            gate_commands({'jobs': {'gate': {'steps': steps}}}, gate, **options)
        except AssertionError as err:
            assert expected in str(err), (steps, expected, str(err))
        else:
            raise AssertionError(f'gate bypass accepted: {expected}')

    display = "xvfb-run -a -s '-screen 0 1920x1080x24' "
    identity = '-PinstallerVersion=${VERSION} -PturboismRelease=true'
    release = {'run': display + './gradlew --no-daemon checkRelease ' + identity}
    install = {'run': 'sudo apt-get install --yes xvfb'}
    formal = dict(gate=FORMAL_RELEASE_GATE, release_flags=True, require_xvfb=True)

    # The formal gate must execute under xvfb-run with xvfb installed beforehand.
    rejected([{'run': './gradlew --no-daemon checkRelease ' + identity}],
             'release gate must execute under xvfb-run', **formal)
    for disguise in ({'run': 'echo sudo apt-get install --yes xvfb'},
                     {'run': 'false && sudo apt-get install --yes xvfb'},
                     {'run': 'sudo apt-get install --yes --simulate xvfb'},
                     {'run': 'sudo apt-get remove install xvfb'}):
        rejected([disguise, release], 'release gate requires xvfb to be installed', **formal)
    for steps in ([release, install], [{'run': release['run'] + '\n' + install['run']}]):
        rejected(steps, 'release gate requires xvfb to be installed', **formal)
    assert gate_commands({'jobs': {'release': {'steps': [install, {'run': '# ' + release['run']}]}}},
                         **formal) == [], 'commented-out checkRelease must yield no executable command'

    # The separately invocable packaging gate needs no display but the same identity.
    packaging = {'run': './gradlew --no-daemon checkReleasePackaging ' + identity + ' --console=plain'}
    assert gate_commands({'jobs': {'packaging': {'steps': [packaging]}}},
                         PACKAGING_GATE, release_flags=True) == [['--no-daemon', PACKAGING_GATE, '--console=plain']]
    assert gate_commands({'jobs': {'packaging': {'steps': [release]}}}, PACKAGING_GATE) == [], \
        'gate filtering must not treat the formal gate as the packaging gate'
    for broken, expected in (
            ('./gradlew --no-daemon checkReleasePackaging -PinstallerVersion=1.0.0 -PturboismRelease=true',
             'installer identity must come from the build identity'),
            ('./gradlew --no-daemon checkReleasePackaging -PinstallerVersion=${VERSION}',
             'release gate must set -PturboismRelease=true')):
        rejected([{'run': broken}], expected, PACKAGING_GATE, release_flags=True)


def contract(root: Path) -> None:
    self_test()
    manifest = load_manifest(root)
    gates = workflow_gates(root, gate_options(manifest))
    check_workflow(root, gates)
    check_declarations(root, manifest)
    check_gate_ownership(root, manifest, gates)
    bodies = build_script_bodies(root)
    # The manifest owns what an aggregate covers; the scripts still own the edges between two
    # non-aggregate tasks, so a member's own helper chain stays visible without a manifest line.
    graph = dependency_graph(bodies) | aggregate_graph(manifest)
    check_manifest(manifest, graph)
    covered = check_gates(bodies, manifest, graph)
    # The pull-request flow must execute the complete formal release closure between
    # ci.yml and the merged channel workflow, and nothing outside it.
    executed = reachable(graph, gates['ci.yml'] | gates['unified-channel-checks.yml']) | {FORMAL_RELEASE_GATE}
    release = reachable(graph, {FORMAL_RELEASE_GATE})
    assert executed == release, (
        'PR workflows must cover the complete release closure; '
        f'missing: {sorted(release - executed)}; unexpected: {sorted(executed - release)}')
    check_sources(root, bodies, member_names(covered))


class CiCoverageContractTest(unittest.TestCase):
    """Runs the structural contract under `python3 -m unittest scripts/test/test_ci_coverage.py`."""

    def test_ci_coverage_contract(self) -> None:
        contract(ROOT)


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    contract(root)
    print('PASS: workflow gate ownership, release closure equivalence, channel topology and test source coverage')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
