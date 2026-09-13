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
  heartbeat latency histogram per phase, event-gap stats, interaction attempts
  (coordinates, duration, selection state), threshold-exceeding stacks.

## Safety

Observation-only: never saves, never writes the model, never creates Undo.
Runs only inside the task-scoped exact-host runner (isolated prefix, isolated
Turboism home, purpose-named fixture copy).

## Build

```
bash validation/selection-lag-probe/build.sh   # → build/selection-lag-probe.jar
bash scripts/preview/package-selection-lag-validation.sh   # → bundle root
```
