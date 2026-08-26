# Formal bundle — history-panel baseline (research worktree)

This directory is the ONLY formal runtime bundle for the history-panel
baseline work. Content rule: exactly one production plugin plus the agent.

| Entry | Role |
| --- | --- |
| turboism-agent.jar | Turboism agent (runtime + bootstrap) |
| plugins/history-panel.jar | The history-panel production plugin |
| home-config.json | Runtime config; `disabledPlugins` stays empty |

Deliberately excluded (error-legacy from the all-plugins bundle):

- clipmask-viewer (clipping-mask inspector tab)
- parameter (parameter-tool top-level menu)
- any plugin contributing the tool top-level menu
- texture-atlas (migration shell placeholder), demo (placeholder)
- all other production plugins (backup, bounding-box, ...) — not part of
  this baseline

Test-only validation probes live OUTSIDE this bundle in
`../validation-probes/` and are injected at validation-run time by
`scripts/preview/run-history-baseline-validation.sh`; they are never part
of the formal bundle.
