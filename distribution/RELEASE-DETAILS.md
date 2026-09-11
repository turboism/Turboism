# Localized release details and file-level statistics

Release JSON adds `notesByLanguage` (`en`, `zh`, `ja`) and `notesCompareUrl`.
`notes` remains the English fallback. Invalid optional display metadata never
disables verified downloads. Language selection happens locally in the website.

New Beta/Nightly candidates use evidence schema 2. Nightly freezes a published,
ancestral baseline and real Git commit subjects in `notesContext` during candidate
preparation. Verification checks that frozen history, without querying a moving
baseline. Schema-1 candidates retain their original notes and publication binding.
The 04:20 Asia/Shanghai schedule and changed-only behavior are unchanged.

Nightly headings and warnings are translated; raw commit subjects remain in their
original language, explicitly labeled. Reviewed Stable/Beta translations live in
`release-notes/X.Y.Z.json`: `schemaVersion: 1`, `version`, `englishSha256` of the
trimmed exact English changelog section, and `locales` containing `zh` and `ja`.
Stale translations are rejected. Missing translations fall back to original English.

Existing release supplements in `release-notes-overrides.json` match exact release
ID, source SHA and original visible body. Regenerate with
`python3 scripts/release/build-historical-display.py`. They enrich API presentation
without rewriting public Release bodies, tags, receipts, binary files or numbers.

The statistics endpoint adds four `assets` records containing `name`, `key`,
`sha256`, `official`, `github`, and `total`. These read the existing persistent
counter; there is no reset or second collection. Missing counts remain null.
The version aggregate reconciles with the file totals, excluding checksum files.
Consumers match complete name/key/hash identities before displaying file counts.
