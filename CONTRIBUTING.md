# Contributing to Turboism

## Repository hygiene policy

Remote history must never contain:

- environment-variable values and environment files;
- local configuration/state;
- AI instructions, prompts, transcripts, or tool state;
- agent/task/research artifacts and editor swap files.

Allowed in committed code, and not flagged:

- ordinary program local-variable names (e.g. `prompt`, `agent`, `apiKey`);
- source references such as `System.getenv("APPDATA")`;
- CI secret-name references such as `${{ secrets.NAME }}`;
- policy/guard code (like this policy) that names the forbidden path classes
  without any real prompt/transcript/value.

## Before you push

Run the checker on your staged files and on the outgoing commit range:

```sh
python3 scripts/check_remote_hygiene.py --staged
python3 scripts/check_remote_hygiene.py --outgoing main..HEAD
```

Replace `main` with the intended remote base when pushing another branch lineage. The `--all` mode audits all reachable historical commits and is reserved for history-cleanup work; existing remote history is not a normal change gate.

Local `pre-commit` and `pre-push` hooks are installed (untracked, local-only)
with:

```sh
python3 scripts/check_remote_hygiene.py --install-hooks
```

The hooks fail closed: if the checker is missing or errors, the commit or
push is refused.

## Verification

During implementation, run the narrowest affected compile or test task. Examples:

```sh
./gradlew :sdk:test --tests '<affected test class>'
./gradlew :runtime:test --tests '<affected test class>'
./gradlew :plugins:<plugin>:test
```

After a meaningful implementation slice, run the fast structural gate:

```sh
./gradlew devCheck
```

When a coherent change is complete, run the full automated repository gate once:

```sh
./gradlew checkCompletedCommit
```

`checkRelease -PinstallerVersion=<release-version>` adds supply-chain, historical, Java-installer, and other release-artifact checks and is reserved for release-oriented work. Exact-host validation is selected explicitly by feature and version; it requires a separately installed, licensed Live2D Cubism Editor and is never part of a default aggregate.

The public SDK has one tier. `@CubismEditor` and exact command catalogs describe Editor-version availability; permissions, session state, verified adapters, and capabilities remain separate runtime checks.

Do not commit generated runtime logs, prompts or transcripts, agent/tool output, local absolute paths, proprietary Cubism material, raw host traces, credentials, or verification claims without a reproducible tracked command or accepted evidence source.

Run repository hygiene checks before completing and pushing a change:

```sh
python3 scripts/test/test_check_remote_hygiene.py
python3 scripts/check_remote_hygiene.py --staged
python3 scripts/check_remote_hygiene.py --outgoing main..HEAD
```

Use `--all` only for an explicit audit of all reachable repository history, not as the normal completed-change or push gate.

## Build requirements

- **Java**: a JDK 17 toolchain is required (`java` and `javac` on `PATH`).

See the project documentation at https://docs.turboism.dev for the current
architecture and contributor guidance.