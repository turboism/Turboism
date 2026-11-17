# triangulation-tlindex Evidence Index and Retention Policy

This directory is the evidence store for the triangulation and TLI (triangulation index) validation series: about 500 dated files (~133 `*.md` review and protocol records, ~127 structured `*.json` evidence files, and the rest source and logs, ~5.5MB), appended by date.

## Evidence Semantics

- `*-YYYY-MM-DD.md` / `*-YYYYMMDD.md`: manual review records, protocol notes, and stage conclusions. Narrative evidence.
- `*.json`: structured evidence produced by a probe or comparison run (matrix signatures, metrics, serialized state).
- `*.java` / `src/`: probe and harness source, not evidence.
- `build.sh` / `run.sh`: scenario driver scripts. `./build.sh` compiles, and `./run.sh` runs and produces new evidence.

## Commit Rules (What Enters Git)

- Commit: the verdict of this run, the minimal structured evidence that supports it, and the protocol or review records (md).
- Do not commit: duplicate snapshots, intermediate `work/` products, and raw dumps beyond the diagnostic need. Put them in the `work/` directory, which Git ignores.

## Retention and Archiving Policy

- **Keep every historical evidence file.** Never delete a committed file; date naming is naturally ordered, so a lookup reads the file name directly.
- When a sub-stage closes (protocol closed or review passed), write a `*-closeout-YYYYMMDD.md` summary that links the key evidence. Do not move old files.
- Search: `ls | grep <date or keyword>`. Search by topic with `grep -l <term> *.md`. A json evidence file corresponds to the md record of the same name or the same period.
- If the file count grows hard to browse, archive by year into `archive/YYYY/` and update this file at that point.

## Adding Evidence

After `run.sh`, commit the new evidence under the existing `<topic>-<YYYYMMDD>.<md|json>` naming. The review record states the conclusion (PASS/FAIL, open items) in the commit message.
