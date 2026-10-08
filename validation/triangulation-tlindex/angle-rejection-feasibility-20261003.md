# T055 angle rejection feasibility — 2026-10-03

The conservative scalar rejection predicate passed three-version native-method controls. It is a candidate for further integration research, not a production change or a measured speedup. T055 launched no Editor and left the frozen T053 production artifact unchanged.

The existing T053 native5303 recordings expose a distinct remaining path: `h.d → r.a(GVector2,GVector2) → Math.atan2 → StrictMath.atan2`. Same-JVM event timestamps mapped to explicit `auto-connect-start/returned` markers give these counts. Native and Java event types remain separate.

| Recording | Command 1 native atan2 with h.d | Command 2 | Command 3 | Outside commands |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 230 | 227 | 214 | 13 |
| T053 candidate | 233 | 227 | 245 | 14 |

These are conditional sample counts, not calls, CPU percentages or removable time. Native event counts cannot be added to Java events to infer a benefit. They justify inspecting this caller; they do not reveal how often the proposed guard would reject actual inputs.

Exact native disassembly found an important version difference: h.d rejects angle >0 in 5203, and angle >L.f()=1e-6 in 5302/5303. The native r angle bodies match after normalizing constant-pool indices. That diagnostic normalization is not an admission fingerprint. An initial audit assuming the epsilon branch for every version failed on 5203; final controls use each reviewed branch threshold.

The owned prototype retains the native float products and their order:

```java
float cross = ax * by - ay * bx;
float dot = ax * bx + ay * by;
```

It declines when either computed value is nonfinite, zero or subnormal. Otherwise it rejects only when dot is negative, or `abs((double) cross) > (double) dot * 2e-6`. Negative dot places the normalized angle well beyond either threshold. For positive dot the second condition requires an angular separation above approximately 2e-6, leaving a factor-two margin over the larger native threshold and its float rounding. Both signs of cross are safe there because native negative angles normalize by adding float 2π. All other inputs retain the original calculation; neither cosine, projection, general r angle return values nor geometry are replaced. A future weave must branch only at h.d's rejection site, not return a fabricated angle from r.

Java17 `--release 17 -Xlint:all -Werror` compilation and `-ea -Xverify:all` execution passed. Each profile used 240986 inputs: 14 special values in all four positions, ULP neighborhoods around zero, ±epsilon and ±2epsilon, 100000 deterministic raw-float tuples and 100000 deterministic finite-range tuples. The final run passed 1387528 assertions across three profiles; it found no false rejection, retained exact fallback angle bits and preserved 19466 native assertion failures per profile. Native Kotlin assertion enablement was checked. The synthetic per-profile 157699 guard rejections are not a host bypass rate. These controls are not exhaustive float enumeration.

The adjacent JSON contains complete window/path counts, three-version method audit and SHA pins for source, original JFR exports, markers, official JARs and final control output. Final control output is tracked in the adjacent controls.txt with only the home-directory prefix replaced by ${USER_HOME}; the original unmodified output remains pinned in build/. Earlier scope refinements remain in build/; the final report uses only profile-specific controls with zero/subnormal fallback.

Reproduce controls:

```sh
mkdir -p /tmp/t055-angle-classes
javac --release 17 -Xlint:all -Werror -d /tmp/t055-angle-classes validation/triangulation-tlindex/diagnostic/AngleRejectionSelfCheck.java
java -ea -Xverify:all -cp /tmp/t055-angle-classes AngleRejectionSelfCheck 5203 "$JAR_5203" 5302 "$JAR_5302" 5303 "$JAR_5303"
python3 validation/triangulation-tlindex/diagnostic/analyze-angle-hotspot.py --output /tmp/t055-angle-windows.json
```

Remaining integration work must preserve native vector getter/class initialization/failure ordering, exact method/field admission and shared premain lease revocation. The current test invokes the unmodified native angle method; it does not execute a transformed h.d or establish live admission. Guard cost, actual input distribution, full output equality and CPU/RSS gates still need evidence before any production performance claim. No merge, push or release occurred.
