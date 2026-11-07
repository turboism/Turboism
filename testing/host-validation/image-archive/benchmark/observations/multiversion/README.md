# Multi-version observation files

Sanitized numbers only; raw logs, copied models, JFRs and AppData stay in the
ignored local build tree.

- `loads.csv` — every successful off/on run, including the earlier diagnostic
  cohort and the 398 KB sanity run. The `cohort` column is `clean`, `diagnostic`
  or `sanity`; only `clean` big-fixture rows are used in
  [MULTIVERSION.md](../../MULTIVERSION.md).
- `shadow.csv` — successful shadow runs. `prepared`/`comparedTextures` count the
  real native texture preparations; `mismatches` is a hard correctness result.
- `attempts.csv` — every run directory that produced an envelope, including
  failed preflights and load interruptions. `auxSha256` identifies the test-only
  auxiliary build used for that run; the production agent hash is constant.
- `summary.json` — medians/ranges derived from the clean cohort, plus the full
  shadow/attempt lists.

`alloc_gb` is the sum of observed per-thread allocation maxima divided by 1e9.
It is a sampled lower bound, not retained RAM, RSS, peak commit or VRAM. `wall_s`
is agent entry to stable document publication; `prep_ms` is the in-hook
preparation callback time reported by the auxiliary observer.
