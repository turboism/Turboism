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
python3 scripts/check_remote_hygiene.py --all
```

Local `pre-commit` and `pre-push` hooks are installed (untracked, local-only)
with:

```sh
python3 scripts/check_remote_hygiene.py --install-hooks
```

The hooks fail closed: if the checker is missing or errors, the commit or
push is refused.

## Verification

```sh
python3 scripts/test/test_check_remote_hygiene.py
```

## Build and test

- **Java**: a JDK 17 toolchain is required (`java` and `javac` on `PATH`).
- **Build from source**:

  ```sh
  ./gradlew devCheck
  ```

- **Runtime and packaged integration**:

  ```sh
  ./gradlew checkIntegration
  ```

- **Release-oriented verification**:

  ```sh
  ./gradlew checkRelease
  ```

- **Exact-host validation** is opt-in and requires a separately installed,
  licensed Live2D Cubism Editor; it is not part of any default gate.

See the project documentation at https://docs.turboism.dev for the current
architecture and contributor guidance.