# Product UI lifecycle host probe

Task-only secondary Java agent for the real ACP, MCP, command-palette and
perf-stats plugins. It uses the loaded production plugin management service for
disable/re-enable (including shutdown and disposable-scope cleanup), and the
production action registry for window entry. ACP uses only its Settings action;
no external agent session or chat is started. MCP stays on the local loopback.
No fixture plugin substitutes for a product plugin.

Build the combined product first, including `:plugins:acp:jar`,
`:plugins:mcp:jar`, `:plugins:command-palette:jar`, and `:plugins:perf-stats:jar`.
Then run:

```sh
bash validation/product-ui-lifecycle-host-probe/build.sh
bash validation/product-ui-lifecycle-host-probe/run-host-validation.sh 5304
```

Configure existing machine inputs using `TURBOISM_ENV_FILE`; additional arguments
are forwarded to the shared Runner. The Runner performs immutable preparation,
submission and observation through `host_validation.py`. An independently managed
`serve` worker must process the queue. Never launch Editor directly.

The agent refuses an unbound task home. Structured assertions and screenshots
are written to `state/product-ui-lifecycle` within the isolated task home. A
blocked-EDT scenario has a 2.5-second independent watchdog, a bounded EDT wait and
an unconditional release. Each product records actual blocked-disable time,
old window disposal, generation identity, queued-open fencing and replacement
window survival. Ordinary disable also exercises production shutdown. Success
requires the shared supervisor's identity, fixture and cleanup proofs in addition
to `status=PASS`; screenshots or process startup alone are insufficient.

A separate deterministic delayed-cleanup scenario installs a task-only scheduler
decorator on the old product instance. It retains the actual product open callback and cleanup
closure, lets production management unload/reload the plugin and opens the new
window, then replays the old callbacks and closure on the EDT. Assertions require old disposal
and new-window survival. This injected delay is explicitly distinguished from
the earlier real blocked-EDT scenario; it is not evidence that production delays
cleanup by that duration. The decorator never ships in a product JAR.

This tool uses reflection only inside validation to inspect plugin/window
identity. It does not add product APIs or change distributed artifacts. The
separate model-update and incremental-update probes remain necessary for #55.

Window screenshots use the showing product window's `printAll` on the actual
host EDT, at its live dimensions, and reject a capture with eight or fewer
distinct colors. These images prove native Swing window content, not physical
desktop presentation. Separate `*-desktop.png` Robot captures are retained as
diagnostics; Wine may return a black desktop image, which is never accepted as
visual proof. Lifecycle assertions and safe cleanup remain independently required.
