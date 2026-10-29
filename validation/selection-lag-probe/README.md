# selection-lag-probe

Task-local diagnostic probe for the user-reported "select object, drag, editor
briefly stalls" regression on the exact Cubism host.

## What it measures

- **EDT heartbeat latency**: a 10 ms `invokeLater` heartbeat records scheduling
  delay, so any EDT block (GC pause, layout storm, host work) shows up as a
  latency spike.
- **Mouse event gaps**: dispatch timing between consecutive EDT mouse events
  during interaction.
- **Top-stack capture**: when a gap or heartbeat latency exceeds
  `turboism.validation.thresholdMs` (default 100 ms) the probe dumps the EDT
  stack, including a Turboism-attributed frame list.
- **Robot interaction**: `java.awt.Robot` performs press-drag-release sequences
  inside the main host window (the select-and-drag path), phase-tagged
  BASELINE → INTERACTION → SETTLE.

## Evidence

- `state/selection-lag-result.properties` — terminal status (`status=PASS` when
  interaction mouse events reached the EDT).
- `state/dev.turboism.validation.selection-lag/` — structured JSON evidence:
  `phases` contains `heartbeatLatencyMillis` and `eventGapMillis`, each with
  `p50`, `p95` and `max`, plus heartbeat/event counts. No p99 is calculated.
  `interactionAttempts` records coordinates, `robotMillis` and cumulative
  `mouseEventsTotal`; `stackCaptures` holds threshold-exceeding stacks and
  `overThresholdEvents` counts them. Selection observations are in the
  `SELECTION_LAG_ATTEMPT` runtime log records.

### Interpreting a run

The PASS condition requires `interactionMouseEvents > 0` during the drag
`INTERACTION` phase. Window activation clicks occur in a separate `ACTIVATION`
phase and do not count toward interaction events, ensuring activation alone
never satisfies PASS. Before interpreting latency as a select-and-drag
measurement, inspect increments between successive
`interactionAttempts[].mouseEventsTotal` values and the logged selection
observations. An unchanged cumulative count across attempts means the intended
workload was not demonstrated. A timed-out or failed selection read is tracked
explicitly in `selectionReadStatus` and does not count as a selection change.
Distinguish input delivery evidence (events reached EDT) from selection evidence
(objects actually selected/changed).

Report heartbeat samples with their counts, duration and workload limitation.
Empty `stackCaptures` means no threshold-exceeding stack was captured in that
run; it does not establish the absence of EDT contention. `-1` event-gap values
mean no samples, not negative latency. A performance comparison requires valid
workloads on both baseline and changed builds, the same fixture and host settings,
recorded actual GPU/renderer, and an exclusive quiet host without concurrent
builds. A terminal PASS or one build's sample is not evidence of improvement.

## Safety

Observation-only: never saves, never writes the model, never creates Undo.
Runs only inside the task-scoped exact-host runner (isolated prefix, isolated
Turboism home, purpose-named fixture copy).

## Build

```
bash validation/selection-lag-probe/build.sh   # → build/selection-lag-probe.jar
bash scripts/preview/package-selection-lag-validation.sh   # → bundle root
```
