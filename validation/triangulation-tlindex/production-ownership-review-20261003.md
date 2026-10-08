# T076 production ownership: native capture complete, JFR window clock unproven

FIFO2596/2597 succeeded once using the unchanged exact production artifacts and
same observer-free driver. Complete4266 ordered output rows,12 kernel-bracketed CPU
readings, normal exits/identity/fixture/cleanup pass. JFR recordings copied/pinned.
Guarded streaming parse passed with no shared performance jobs; maxsize256m and
profile settings were identical. No retries or replacement performance legs.

The nominal epoch-window parser exposes repeated index-settlement stacks, but
cross-clock audit prevents treating its window classifications as precise owners:

| Profile | Command1 matched JFR minus GC-log ms | Command2 ms | Command3 ms |
|---|---|---|---|
| baseline | about -316 | about -456 | about -596 |
| candidate | about -304 | about -416 | about -580 |

The same gcId/before-after phase binds each comparison. Some JFR heap summaries lack
matching console summaries and are retained as unmatched. Drift varies by command;
no constant-offset correction is justified. The original frozen nominal parser and
its outputs remain unchanged. Whole native capture passes, but exact JFR-to-native
window ownership remains unproven. Reflection-method allocation samples near these
boundaries must not be interpreted as synchronous native-command allocations.

CPU leaf stacks also include hundreds of AWT-Windows.eventLoop native-method samples;
these are not proof of active CPU consumption. Inclusive/leaf counts and method types
remain separate observations, not durations or causal savings. Repeated settle self
samples occur in both profiles; baseline also shows Arrays.copyOf, candidate shows
Objects.checkIndex. Source inspection confirms settle scans the actual survivor set
to prove removed identities absent. This is a concrete optimization lead, not an
accepted speedup or precise CPU-share estimate.

A naive identity-removal shortcut is not justified: all three official triangle
classes override equals/hashCode. The inspected5303 equals compares TriPoint corner
combinations, while hashCode returns0; native collision/equivalence victim semantics
must be preserved. The three native corner fields are non-final in every version, and TriPoint also
overrides equals. The immutable-equality shortcut is therefore refused; an exact
victim-tracking design or another optimization is needed before removing the scan. Current production source
and all correctness fallbacks remain unchanged.

Next bind JFR and Java command clocks explicitly in a separate diagnostic plugin, or
use a proven clock-mapping protocol, before new command-owner percentage claims.
In parallel with that reasoning, investigate an exact-safe settlement optimization;
never skip the native geometric-equality behavior to obtain a speedup. Original failed
production ABBA, original FIFO2588 refusal and T057 delivery baseline remain unchanged.
All-version/long-run/LaneC remain open. No merge/push/release; broadergoalactive.
