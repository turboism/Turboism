# 0.70.0 installer and Windows acceptance findings

## Scope

This pass uses native Windows and Java 17.
It does not establish final completed-tree, signed-candidate, or Cubism host acceptance.
The user deferred the repeated-input investigation and all GIF acceptance.

## Fixed: piped Java installer ignored standard input

A task-owned installer stopped at language selection despite receiving piped answers.
Increasing the timeout to 360 seconds did not resolve the problem.

The thread dump identifies the input path:

```text
Kernel32.ReadConsoleInputW
WindowsTerminal.readConsoleInput
ConsoleReader.readLine
LanguageConsoleDialog.initLangPack
```

JLine selected its native Windows terminal instead of the verifier's standard-input pipe.
The global IzPack lock did not cause this failure.

Commit `362f9e853` adds a stream-backed terminal argument to verification-owned Java commands:

```text
-Djline.terminal=jline.UnsupportedTerminal
```

A focused replay reached the Welcome panel and honored the quit answer.
The production installer and interactive launch commands retain their existing terminal behavior.

## Fixed: Thin verification deselected the first plugin

The Windows answer generator supplied a managed-Graal answer for every installation mode.
The Thin console flow has no managed-Graal prompt.
That extra answer therefore deselected ACP, the first optional plugin.

The first replay installed successfully but produced this unexpected selection:

```json
{"disabledPlugins":["dev.turboism.plugin.acp"]}
```

Commit `362f9e853` restricts that answer to Full and Lite.
The existing Thin assertion remains unchanged.

## Installer verification results

All commands use these version arguments:

```text
-PinstallerVersion=0.70.0 -PturboismVersion=0.70.0 -PturboismRelease=true
```

| Command | Result | Evidence |
|---|---|---|
| `./gradlew --offline --console=plain checkJavaInstaller` | PASS, 2m19s | `build/acceptance/070/check-java-installer-2.log` |
| `./gradlew --offline --console=plain checkWindowsInstaller checkInstallerLocalization` | PASS, 20s | `build/acceptance/070/check-windows-installer.log` |

The second command also runs `checkWindowsInstallerPackaging`.

The Java installer pass covers four locales, plugin selection, reselection, and exact-byte no-op preservation.
It also covers migration, malformed-input rejection, retired-plugin cleanup, uninstall retention, relocation safety, and global-lock isolation.
The run reports an unavailable symbolic-link backup fixture and the unsupported Windows FIFO fixture as skips.

The Windows packaging pass exercises the production generator and PowerShell payload helper.
It verifies plugin identity backup, skipped selections, failed-copy recovery, localized README generation, and offline engine pins.
The Windows installer check verifies the script and payload simulation, not a running NSIS wizard.

The earlier pass had no native compiler or EXE artifact.
The native wizard pass below now accepts plugin-selection and README interaction within its stated scope.
Full native installation and signed-candidate acceptance remain separate.

## Fixed: Windows log filtering introduced incompatible document separators

The focused baseline reproduced two failing tests in `PaletteFilterHostOperationsTest`.
The filter joined lines with `System.lineSeparator()`.
A replacement Swing document also lacked an explicit serialization separator.
On Windows, those choices introduced CRLF differences and duplicate serialized line breaks.

Commit `7bb24a557` uses LF inside filtered documents and sets `DefaultEditorKit.EndOfLineStringProperty` explicitly.
The original exact assertions remain unchanged.
One additional assertion covers CRLF input normalization.

Verification:

```text
./gradlew --offline --console=plain :runtime:spotlessJavaApply \
  :runtime:test --tests dev.turboism.ui.filter.PaletteFilterHostOperationsTest \
  :runtime:spotlessJavaCheck \
  -PinstallerVersion=0.70.0 -PturboismVersion=0.70.0 -PturboismRelease=true
```

Result: 28 tests, zero failures, zero errors, and zero skips.
Java formatting also passed.
Evidence: `build/acceptance/070/log-filter-before.log` and `build/acceptance/070/log-filter-after-2.log`.
This focused pass does not replace the outstanding final verification gates.

## Old Cubism session cleanup

The user explicitly authorized closing the old sessions.
The inspection recorded eight Cubism 5.3.04 process identities before sending close requests.
Only PID 19420 had visible windows and a responsive JVM.
It used the isolated acceptance home and model copy.

Normal window-close requests did not complete.
An identity-checked forced termination ended PID 19420.
Record that session as an abnormal exit, not successful lifecycle acceptance.

Seven older process records remain with one thread each and non-`STILL_ACTIVE` exit codes.
Java attach reports access denied; forced `taskkill` reports no running task instance.
No corresponding windows remain.
Those results do not prove complete kernel cleanup.
Keep real-host admission blocked until the cleanup state becomes verifiable.

Evidence:

- `build/acceptance/070/shutdown/before.json`
- `build/acceptance/070/shutdown/close-results.json`
- `build/acceptance/070/shutdown/forced-close-results.json`

Do not terminate other Java applications or restart Windows without separate authorization.

## Native Windows wizard acceptance

Commit `ad2adef20` contains the fixes from this pass.
The production assembly builds the native EXE with `-WX`; no warning exemption applies.

### Toolchain and artifact binding

The task uses the official MSYS2 `mingw-w64-x86_64-nsis` package, version `3.13-2`.
It extracts the compiler only into the ignored task directory.
It does not modify the system toolchain or PATH permanently.

Package origin:
`https://repo.msys2.org/mingw/mingw64/mingw-w64-x86_64-nsis-3.13-2-any.pkg.tar.zst`.

The downloaded package matches the SHA-256 from the official package metadata page:

```text
92e4d39fff5acad5c314babc696d85cd94475831a84371bafff59f285a7a9aff
```

The native compiler selects `amd64-unicode`.
The accepted EXE contains 89,758,453 bytes and has this SHA-256:

```text
f246d6a3338a24cd1cbec77c1feea5c8ad0511ee0c01db427daf24b440100548
```

`build/acceptance/070/native-installer/source-binding.json` records the source, generated compiler inputs, driver, result, and toolchain hashes.
This artifact is unsigned and local only; it is not the final release candidate.

### Fixed: native compiler source paths

The native compiler could not resolve forward-slash source paths that referenced existing files.
A minimal include probe reproduced the failure and succeeded with a backslash before the filename.

The generator now creates compiler-local source copies with native Windows separators and UTF-8 BOMs.
It changes only quoted paths rooted in the staging, generated, EULA, and output macros.
It preserves runtime paths, URLs, checksum manifest paths, and payload bytes.
Non-Windows generation retains the original separators.
The assembly compiles the generated entry point and its adjacent plugin include.

The existing packaging suite checks all three generated compiler sources.
Linux compilation and native x86 wizard interaction remain outside this pass.

### Fixed: empty plugin names on amd64

The first native wizard showed 34 checked rows without names.
The README pane still loaded, so successful document rendering did not prove usable plugin selection.

The `LVITEM` layout placed its text pointer at the wrong offset on amd64.
The fix sizes the preceding mask slot through `NSIS_PTR_SIZE`, matching the layout in NSIS `WinMessages.nsh`.
The native replay verifies every localized row label against the staged plugin metadata.
It also verifies checkbox operations and selection retention.

### Fixed: stale strategy-page header

The Graal strategy page retained the plugin-selection header and instructions.
The fix publishes the localized strategy title and clears the previous subtitle.
The replay captures the corrected page before returning to plugin selection.

### Native interaction result

Result: PASS, 22 structured checks in Simplified Chinese.

- All 34 plugin names match their localized metadata and versions.
- All 34 plugins start selected.
- ACP, deformer rename, parameter transfer, recent-project board, and WebDAV show their own README content.
- Every sampled README contains its final source heading; screenshots also capture the scrolled document.
- Individual deselection updates the counter to 33 of 34.
- Back navigation preserves the deselection and counter.
- Select-all, select-none, and select-all again update every row and the counter.

The driver requests cancellation before installation.
The process exits through the normal cancellation path with code 1, without forced termination.
The selected installation directory does not exist after exit.
This pass makes no full native installation, uninstall, or host-launch claim.

The evidence retains four earlier failed attempts and the initial empty-label observation.
The final attempt uses a new process after source and driver corrections; it does not overwrite those failed reports.

Evidence root: `build/acceptance/070/native-installer/`.

- `final-gates.log`: packaging, installer-script, and localization gates pass.
- `final-build.log`: complete production assembly and native compiler pass.
- `native-gui-result.json`: checks, artifact hash, cancellation code, and target-directory check.
- `native-readmes.json`: native RichEdit text for the five sampled plugins.
- `08-fixed-plugin-selection.png`: visible plugin labels and initial selections.
- `10-strategy.png`: corrected strategy-page header.
- `11-select-all-restored.png`: restored complete selection.
