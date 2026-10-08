# T056 complete angle-method feasibility — 2026-10-03

The owned h.d bytecode prototype preserves the tested native outputs and exercises actual angle bypass. It is ready for production-integration research; production and host performance acceptance remain open. No Agent was installed, no Editor was launched, and frozen T053 production stayed unchanged.

The prototype pins both original h class hashes. It leaves vector construction, original angle call and rejection target, projection/cosine, triangle mutations and algorithm order intact. The operation has a substituted owned lease with finally release. Null lease runs every original angle call. The original r singleton read occurs before each optional guard; the first angle call in every invocation stays native to retain initialization. Eight final scalar getter reads preserve the native cross/dot float order. Zero, subnormal and nonfinite computed cross/dot decline. The predicate is the T055 conservative far-from-threshold rule, valid for the reviewed 5203 zero threshold and 5302/5303 epsilon threshold.

Plain guard timing exposed all-fallback overhead, so the final prototype tracks consecutive misses in a primitive local. Eight misses disable further guard attempts for that invocation; a proved rejection resets the miss count. Disabled state survives later native calls. This bounds expensive checks but does not eliminate all added branches or guarantee zero regression.

Java17 lint/Werror compilation and `-ea -Xverify:all` full native execution pass 45970 assertions:

| Native profile | Fixtures | Assertions | Guard attempts | Proven rejection bypasses |
| --- | ---: | ---: | ---: | ---: |
| 5203 | 128 | 15451 | 8375 | 7734 |
| 5302 | 128 | 15259 | 8039 | 7606 |
| 5303 | 128 | 15259 | 8039 | 7606 |

Controls compare pristine native d, patched d with null lease, and leased patched d in separate loaders. They compare ordered endpoint indices/raw coordinate bits and verify every output endpoint belongs to that fixture by physical identity. Fixtures include random, empty, collinear, near-ray, zero/subnormal, nonfinite and coordinates mutated after initial triangle construction. Native exception signatures match; an injected list exception also preserves the exact Throwable instance. Lease closes on normal/error exits. Native angle calls plus proved bypasses equal baseline calls. Every guard attempt verifies a preceding native call. All-collinear fixtures verify at most eight attempts. Unknown bytes and wrong d access refuse; canonical non-d algorithms and nondebug/nonframe class/field metadata fingerprints remain unchanged.

Both original and candidate r methods have the same diagnostic counter, outside production admission. The checks do not establish a genuine shared premain lease or all Editor output fields. Cold class-init errors are not fault-injected. The prototype recomputes frames throughout h; preservation of every non-target raw attribute is not claimed.

Scalar cost experiments use six alternating warmup pairs, five alternating measured pairs, 4096 inputs per invocation and 262144 evaluations per measured run. Result counts are consumed and checked. Rows show ratios of independent medians against the original scalar atan2/float-normalization predicate; they are not Editor speedup estimates.

| Synthetic distribution | Plain guard change | Bounded adaptive guard change |
| --- | ---: | ---: |
| Uniform finite (all 4096 predicates reject) | −88.255% | −85.037% |
| Half near-ray (2048 predicates reject) | −47.973% | −47.711% |
| All near-ray fallback | +31.560% | +3.984% |
| All zero-cross fallback | +13.231% | +6.883% |

These are separate JVM runs with visible timing variance. They support retaining the miss bound and investigating actual workload distribution, not a stable percentage comparison between modes. Scalar timings exclude lease acquisition, vector allocations, getter/class-init behavior and the full algorithm. Synthetic rejection counts are not an Editor bypass rate. All previous exploratory logs remain in build/; final evidence uses only the tracked final native/adaptive/plain outputs.

Compile the four AngleGuard*.java files with local ASM9.7.1 core/tree. Run AngleGuardNativeSelfCheck with `-ea -Xverify:all` and the three explicit official JAR paths. Run AngleGuardCostSelfCheck with `-Xverify:all`, first without arguments and separately with `--adaptive`. Exact argv and dependencies are pinned in the adjacent JSON. Tracked logs replace only the home prefix with `${USER_HOME}`; original logs remain pinned in build/.

Next integrate the reviewed d stencil/helper into the existing composed preparation and actual shared dependency lease, including live util.L and relevant final getter/angle links. Verify definition mutations, attach-enabled decline, revocation, cold failures, null lease, exact bytecode composition and the frozen artifact before a FIFO host comparison. Real input distribution, complete output equality and unchanged original CPU/RSS limits remain required. No merge, push or release occurred.
