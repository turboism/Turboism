# Operator runbook: native Editor edit ingress

`history-native-ui-probe.jar` answers one question that no automated probe can:
what does Turboism publish when **you** edit the model in Cubism's own user
interface? The automated probes drive the host through the SDK, so their edits
enter Turboism first and never exercise the native entry hook.

The probe changes nothing. It waits for one action, watches the native Undo
manager, and writes down what it saw.

## Before you start

- Use Cubism 5.3.02 with the history validation fixture. The task is declared for
  5302 only.
- Do not open other files. The probe records the whole Undo manager, so an
  unrelated edit becomes indistinguishable from the intended one.
- **Do not close the Editor until the terminal `summary` line is written.** The
  probe samples the Editor's own thread; closing it while a step is pending stops
  that thread and the run ends with no verdict and no summary. If you want to
  stop early, say so and leave the window open.
- Keep the model window and the Parts tree visible, because two of the steps are
  drag operations.

## Start the task

```bash
TURBOISM_ENV_FILE=/opt/dev/projects/turboism/.env \
  python3 scripts/preview/host_validation.py \
    --manifest build/research/history-capture/history-managed-tasks.json \
    prepare history-native-ui:5302 --run-label native-ui-r1
```

then submit the prepared request to the host-validation manager. Use a result
timeout of at least 5400 seconds: the probe waits up to 420 seconds per step and
there are eight of them, and each step's window opens the moment its instruction
is written, so read the instruction before you start moving the mouse.

## The eight steps

The probe announces each step in the runtime log and in its own evidence file as
`"type":"prompt"`. Do exactly one action per step, then stop and wait for the
next announcement. If you perform two actions during one step, the step still
passes, but the native entries it records cannot be attributed to a family, so
the whole run becomes useless for the decoder work.

| # | Step | Do this | Then |
| - | ---- | ------- | ---- |
| 1 | `parts-tree-drag` | Drag one Part onto a different Part in the Parts tree and release. | Wait. |
| 2 | `deformer-assign` | Assign a different Deformer target to one Part or ArtMesh and **confirm it** — the Deformers palette must show the new target. Selecting the object alone is not an assignment. | Wait. |
| 3 | `canvas-move` | Move ONE object **as a whole** on the canvas and release. Do not edit its vertices. | Wait. |
| 4 | `canvas-deform` | Deform ONE object instead of moving it: drag a single mesh point so its shape changes, then release. | Wait. |
| 5 | `native-parameter` | Change one Parameter value in the native Parameter palette only. | Wait. |
| 6 | `native-color` | Change only the multiply colour (正片叠底色) of one drawable in the native palette. Do not move it. | Wait. |
| 7 | `native-undo` | Press the native Undo shortcut once. | Wait. |
| 8 | `native-redo` | Press the native Redo shortcut once. | Wait for the summary. |

Do not use Turboism's own history panel for steps 7 and 8: the point is the
native shortcut, which is a different ingress than the one the panel uses.

For every step, act once and then **wait for the next instruction to appear
before doing anything else.** A step is closed by the first *significant*
Edit, and a selection is not significant — clicking an object to select it does
not consume the step, which is why the Part drag instructions say to drag rather
than to click first.

Steps 7 and 8 are closed by the position moving **in their own direction**: an
Undo by a step backwards, a Redo by a step forwards. A selection moves the
position forwards too, so it cannot close an Undo step, but a Redo landing inside
the Undo step's window would still end the Undo early. Press one shortcut, wait
for the next instruction, then press the other.

If a step is still pending after about a minute and a half the probe re-announces
it in the log as `STILL WAITING [step-id] …`. That is not a new step: the window
has not moved, and you can still perform it. A step whose instruction you never
saw is the single most common way a run is wasted — three of the last four runs
lost their first step this way — so read the log before you start acting.

**If you are not at the desk when the run starts, that is fine**: come to the
Editor and perform whichever instruction is currently announced. Do not catch up
by performing several steps in a row.

If a step's action fails or you are unsure it applied, say so and stop the run.
A step with no native change is recorded as a failure rather than silently
skipped, and re-running is cheaper than decoding a mislabelled artifact.

## Automated mode

`TURBOISM_HISTORY_NATIVE_UI_AUTOMATE=1` on the wrapper (or
`--jvm-option "-Dturboism.history.nativeUi.automate=true"` on the Runner) makes
the probe drive the bounded steps itself through the real host UI. The prompt
lines are still written and each step still closes only on the native change the
undo manager reports — automation changes who acts, never what counts.

- `parts-tree-drag`, `canvas-move`, `native-undo` and `native-redo` have actors:
  a Parts-tree row drag, a canvas drag, and the Ctrl+Z / Ctrl+Y accelerators.
  Undo/Redo prefer the enabled menu accelerator and only fall back to a
  focused-window `Robot` keystroke when no menu item claims the shortcut.
- Automated runs share the host display with other work, so each step's window
  defaults to 60 seconds instead of the manual 420 — the actors verify every
  attempt against the undo manager themselves, and the window only catches a
  late commit or an operator who happens to be present. Override with
  `--jvm-option "-Dturboism.history.nativeUi.stepTimeoutMillis=<ms>"` on the
  Runner when a longer window is wanted.
- Every other step (`deformer-assign`, `canvas-deform`, `native-parameter`,
  `native-color`) has no actor and simply waits out its window; with nobody at
  the desk they are recorded as `no-native-change` failures.
- An actor that cannot resolve its control reports `unresolved:<detail>` on its
  `actor` artifact line and the step stays open for the operator.
- An automated run writes a bounded `ui-map` line for every step whose actor
  ran: the visible window/component tree with class names and screen bounds,
  captured at action time once the document UI is populated. It exists so the
  remaining steps can be targeted at real controls instead of guessed ones —
  review it before trusting an actor result.

A failed actor never produces a passing step: the step still passes only when
the native manager moved the way the step requires. Do not touch the mouse or
keyboard while an automated run is in progress — real input and operator input
are indistinguishable to the undo manager, and your action would be attributed
to the current step's window.

## What the artifact says

```text
data/dev.turboism.validation.history-native-ui/history-native-ui-ingress.jsonl
```

- `paired-snapshot` lines are the native Undo managers plus the Turboism
  history, sampled on the Editor thread at a step boundary. The native side
  carries every entry's class name, its presentation name, and the fields the
  sampler knows how to read. For a `SimpleUndo` at the manager tip the sampler
  reads the live target as its post state — the same rule the decoder applies —
  and marks it `history.detail.post-state-live-target`; anything else keeps
  `post-state-unavailable`.
- `actor` lines (automated runs only) name what the step's actor did or why it
  could not. `ui-map` is the bounded component dump an automated run writes
  once, after the baseline snapshot.
- `semantic-event` lines are the events Turboism published for the step, with
  phase (`before`, `on`, `after`), operation, origin, sequence and label.
- `check` lines are the verdicts. `hook-fired`, `observer-fired` and
  `label-transported` are the overall ones. The two halves of the ingress fail
  independently, so read them separately: `hook-fired` false is the `before`
  entry hook, `observer-fired` false is the undo-manager listener.

## What each verdict means

- `ACTION` steps must be announced by a `before` event before the confirmed
  `on`, and must advance the native Undo manager. A missing `before` means the
  native entry hook did not see an operator edit, which is the one thing this
  run exists to test.
- `UNDO` and `REDO` steps must be attributed to `UNDO`/`REDO` and must produce
  **no** `before` event. A native Undo does not enter an edit; if it announces
  one, the hook is over-reporting.
- `label-transported` is recorded, not gated. An empty label is a finding about
  that family, not a failed run: the label is presentation, and the operation is
  identified structurally.

## Afterwards

Send the collected artifact back for decoding. The native classes and the
geometry summaries recorded in the `parts-tree-drag`, `deformer-assign`,
`canvas-move`, `canvas-deform`, `native-parameter` and `native-color` snapshots
are the input for the remaining decoder work; `canvas-move` and `canvas-deform`
are a pair on purpose, because a whole-object move translates every point by the
same vector while a deformation does not. The `before`/`on` sequence and the
labels are the input for the entry hook's own review.
