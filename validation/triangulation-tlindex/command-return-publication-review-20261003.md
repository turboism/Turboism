# T076 command-return publication review: partial 5303 evidence

The proposed command-return observer is not admitted yet. New bounded static
inspection shows that native repaint-related processing can run synchronously
before the command returns. A collector cannot simply assume that the producer's
raw arrays survive unchanged until a later EDT observation.

The native command calls endEdit and repaintCanvas after per-source autoConnect
and setEdgeUpdated. The inspected CECompletePack repaint overload delegates to
CEUpdateManager. Its per-view callback dispatch uses `util/aD.c(Function0)`, which
invokes the callback directly on EDT and otherwise schedules it with Swing
invokeLater. The captured T callback calls CEViewContext.onInputEvent, whose body
invokes the virtual onInputEvent_exe. The update manager also schedules delayed
work through another utility overload. This establishes the synchronous path,
not that it necessarily recomputes or corrupts any mesh arrays.

The inherited ACEditMode.endEdit implementation calls virtual optional-state
capture, undo-manager addEdit, modification bookkeeping and an optional callback.
Those callees and the actual live view-context implementations still need review.
The class/member listing does not establish a complete transitive publication
proof, or identify which view implementations the live task uses.

The separate inspector reads seven selected class entries and decodes their Code
attributes without starting a JVM or loading official classes. Six synthetic
decoder tests pass: operand/member separation, wide instructions and long branches,
aligned/unaligned switches, malformed/truncated/bad-boundary refusal, and resolved
member references. Independently, every instruction offset in the two existing
pinned javap methods commandAutoConnect and autoConnect matches the new decoder
(130 + 170 instructions). This is bounded decoder evidence, not an arbitrary
class-file verifier or runtime safety proof.

Each inspected class is SHA-256 bound in the companion JSON. The archive path
comes from the historical reviewed 5303 manifest; complete JAR hashing is deferred
while another performance leg runs. Consequently this review explicitly does
not revalidate complete archive identity or grant host admission. It also does
not cover the 5203/5302 call chains. Raw instruction output is retained under
`build/t076-production-acceptance-preflight-r1/command-return-closure5303-r1.json`;
no native bytecode body is included in this tracked report.

Two initial selections refused because the requested method was not declared on
the selected class. Actual inheritance/callback references identified ACEditMode
and onInputEvent, which were then inspected. These are read-only inspection
corrections; no host retry, guard change or source mutation occurred.

Next inspect actual view handlers and edit/undo completion, revalidate the complete
official archive in a free measurement window, and prove complete per-source
generation/output stability before implementing a separate collector. Preserve
legacy CacheNotReady refusal, source/document binding, native options and complete
source coverage. Do not force cache refresh, write version fields, infer generation
from unchanged array identity, or substitute successful command return for full
output equivalence. T075's original four-leg PASS is unchanged; no new host job or
production delivery was performed. The broader goal and human review remain open.
