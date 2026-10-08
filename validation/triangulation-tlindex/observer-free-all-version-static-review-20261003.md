# T076 three-version pinned static inventory

The three official archive digests match the existing frozen version pins. No JVM,
host window, fixture edit or native command was started. This is static inventory
only; it does not grant all-version plugin admission or production acceptance.

| Version | autoConnect normal return offsets | Generator return | Loop reader return |
|---|---|---|---|
| 5203 | 54, 324 | 674 | 1430 |
| 5302 | 58, 328 | 674 | 1430 |
| 5303 | 58, 328 | 674 | 1430 |

The early return precedes generation. The existing 5303 driver's complete-source
nonempty-boundary-loop check remains necessary. 5203 uses util/i/a while 5302/5303
use util/j/a for progress callbacks; the current builder remains 5303-only.

For these selected methods, 5302 and 5303 have identical code hashes for autoConnect,
updateIndices, cached-index accessors and the four-argument generator. 5203 differs.
All three generators have six integer-array store instructions at offsets
596/609/622/636/649/662 and a single normal return at 674. The cache-index setter
writes cached_indices at offset2; updateIndices writes its cache version at125
in5203 and129 in5302/5303. These are instruction facts, not evidence that a path
executed, arrays are fresh, or loop-reader callees are free of side effects.

Reproduce with diagnostic/inspect-observer-free-versions.py, passing the three
pinned official jars and a new output directory. Full resolved instruction lists,
method hashes, exception tables, field writes and native mesh calls are retained
under build/t076-production-acceptance-preflight-r1/observer-free-all-version-static-r1;
the adjacent JSON pins every raw report. Archive mismatches refuse admission.

Full interprocedural/exceptional-path proof, exact-production cold startup, the
separate balanced production comparison, long-run gates and Lane C review remain
pending. FIFO2588's frozen destruction-gate refusal and its distinct postmortem are
unchanged. T075 original four legs and T057 delivery baseline remain unchanged.
