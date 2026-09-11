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
- Do not save the document. The run discards the copy, and the source fixture is
  hash-checked before and after.
- Do not open other files. The probe records the whole Undo manager, so an
  unrelated edit becomes indistinguishable from the intended one.
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
timeout of at least 1800 seconds: the probe waits up to 300 seconds per step.

## The six steps

The probe announces each step in the runtime log and in its own evidence file as
`"type":"prompt"`. Do exactly one action per step, then stop and wait for the
next announcement. If you perform two actions during one step, the step still
passes, but the native entries it records cannot be attributed to a family, so
the whole run becomes useless for the decoder work.

| # | Step | Do this | Then |
| - | ---- | ------- | ---- |
| 1 | `parts-tree-drag` | Drag one Part onto a different Part in the Parts tree and release. | Wait. |
| 2 | `deformer-assign` | Assign a different Deformer target to one Part or ArtMesh and confirm. | Wait. |
| 3 | `canvas-move` | Move one model object on the canvas with the mouse and release. | Wait. |
| 4 | `parameter-or-color` | Change one Parameter value, or one drawable colour, in the native palette. | Wait. |
| 5 | `native-undo` | Press the native Undo shortcut once. | Wait. |
| 6 | `native-redo` | Press the native Redo shortcut once. | Wait for the summary. |

Do not use Turboism's own history panel for steps 5 and 6: the point is the
native shortcut, which is a different ingress than the one the panel uses.

If a step's action fails or you are unsure it applied, say so and stop the run.
A step with no native change is recorded as a failure rather than silently
skipped, and re-running is cheaper than decoding a mislabelled artifact.

## What the artifact says

```text
data/dev.turboism.validation.history-native-ui/history-native-ui-ingress.jsonl
```

- `paired-snapshot` lines are the native Undo managers plus the Turboism
  history, sampled on the Editor thread at a step boundary. The native side
  carries every entry's class name, its presentation name, and the fields the
  sampler knows how to read.
- `semantic-event` lines are the events Turboism published for the step, with
  phase (`before`, `on`, `after`), operation, origin, sequence and label.
- `check` lines are the verdicts. `hook-fired` and `label-transported` are the
  two overall ones.

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

Send the collected artifact back for decoding. The native classes recorded in
the `parts-tree-drag`, `deformer-assign`, `canvas-move` and `parameter-or-color`
snapshots are the input for the remaining decoder work; the `before`/`on`
sequence and the labels are the input for the entry hook's own review.
