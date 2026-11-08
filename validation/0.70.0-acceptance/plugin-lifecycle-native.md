# 0.70.0 native plugin lifecycle acceptance

## Scope

The main agent verified the native Plugin Market on Windows with Cubism Editor 5.3.04.
The disposable installation used the current preparation runtime plus the board fixes under review.
The run did not change the user's installation, official host files, or original models.

This result covers a representative downloaded plugin with its canonical installation filename.
It does not claim that every plugin, dependency combination, or deferred-install path passed.
Framework self-update remains a separate acceptance item.

## Bound inputs

| Input | Identity |
| --- | --- |
| Preparation baseline | `9994a3aa7460485d452e8065e442a95703fddad9` |
| Candidate agent | `ca243ff6dd3ea19fc553ed19d5cc752eec48161f76c867f582bdd097de397084` |
| Build kind | Local, unnumbered, dirty source; not a signed release candidate |
| Native host | Cubism 5.3.04, build `503040001` |
| Editor JAR | `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d` |
| Plugin | `dev.turboism.plugin.parameter-lock` |
| Upgrade | Genuine catalog artifacts, `0.67.2` to `0.68.0` |
| Catalog | Version 15, published `2026-11-01T08:36:12Z` |
| Installed 0.68.0 JAR | `0cca4b22fceaff7b7c4027c65c172d0e434bc9f77c3f72f133e6fb5e30f894be` |

The retained source bindings include the embedded framework identity and workspace patch digest.
They also hash every changed source file, including new utility classes.
The client used its production signed-catalog validation path.
Independent checks matched the installed JAR size, raw SHA-256, and descriptor SHA-256 against that catalog.

## Native observations

| Operation | Observed result |
| --- | --- |
| Upgrade | The market showed `0.67.2 -> 0.68.0`, then installed `0.68.0` with immediate-application feedback. |
| Disable | The button changed to Enable; the runtime disabled and unloaded the active generation. |
| Enable | The button changed to Disable; the runtime hot-loaded the plugin. |
| Uninstall | The market showed Not installed; the JAR disappeared and no pending transaction remained. |
| Reinstall | The market downloaded and verified `0.68.0`, then hot-loaded it without restarting. |
| Disable and restart | The disabled configuration persisted; the restarted market offered Enable. |
| Re-enable after restart | The runtime hot-loaded the installed plugin and reported immediate application. |
| Uninstall and restart | The market still showed Not installed; the JAR and pending transaction remained absent. |

The evidence capsule checks 11 native snapshots across three sessions.
It preserves screenshots, component-tree text, artifact checks, and timestamp-bound lifecycle logs.
The runtime recorded 75 plugin lifecycle events in the first session and 20 in the second.
The third session recorded no events for the uninstalled plugin.
None of those plugin events reported a timeout, circuit rejection, or failed replacement.

## Lifecycle and verification

All three sessions exited normally with code 0.
Each Windows Job Object reported zero active processes and safe cleanup.
Post-exit hashes matched every bound official host file.

The main agent ran the affected verification command:

```sh
./gradlew --offline --console=plain :runtime:test \
  --tests '*PluginMarketServiceTest' \
  --tests '*RuntimePluginAutoUpdaterTest' \
  --tests '*PluginHotReloadIntegrationTest' \
  -PturboismVersion=0.70.0 -PturboismRelease=true
```

The command passed.
The separate typed-config symbolic-link gate remains unresolved; this result does not waive it.

## Retained evidence

The ignored directory `build/acceptance/070/` contains:

- `plugin-lifecycle-acceptance.json`: verified observations and artifact hashes.
- `plugin-lifecycle-focused.log`: affected test output.
- `host-5304-r5/`: upgrade, disable, enable, uninstall, reinstall, and artifact binding.
- `host-5304-r6/`: persisted disable state, re-enable, and uninstall.
- `host-5304-r7/`: persisted uninstall state.

Each session contains `source-binding.json`, native screenshots, component trees, lifecycle logs, and kernel cleanup proof.
Final release acceptance must bind the final candidate after all remaining fixes merge.
