# T103 invocation-local corner vector reuse feasibility

PASS_OWNED_THREE_SDK_DIFFERENTIAL_ONLY: three pinned official SDKs, 2489 cases each, 7467 total. Isolated owned loaders, no editor or production installation. Prototype rewrites only nine GVector2 corner-construction sites in PointInTriangleD$a.a to three lazily initialized invocation-local objects; exact original coordinate reads, triangle order, arithmetic, barycentric calls, logging and return path remain in bytecode. No static or ThreadLocal vector state introduced.

Return fields compare raw double bit patterns, including NaN/signed zero; normal-return input arrays/point remain unchanged. Cases cover deterministic random geometry, extremes/nonfinite values, nullable inside/nearest fallback, empty/trailing indices, sentinel and invalid indices/strides, null parameters, many misses followed by hit and fallback. Ordinary exception type/message parity passes; fatal allocation timing, concurrent external mutations and UI lifecycle are not proven.

On 100 misses then hit, instrumented FF vector constructor counts are 910 baseline / 610 candidate: exactly 300 fewer corner allocations. Both arms contain the same owned constructor counter, so this is a constructor-count proof, not a timing, JFR weight or RSS comparison. Companion isInTriangle still has its native temporary difference vectors; no scalar arithmetic rewrite or claim that all allocations are removed.

Initial r1 failed before differential execution because the modified owned class lacked the signed archive CodeSource certificates, causing companion signer mismatch. Original compiled classes and logs are retained and pinned. r2 passes the verified original jar CodeSource/certificates after fully reading the entry; no host retry or signature stripping.

Production admission still needs the callback/escape closure, exact class/dependency identity, installation/removal lifecycle, UI result/output equivalence and final-artifact formal CPU/wall/RSS gates. This prototype does not accept T093 or replace T057; earlier failures and all-version/long-run/Lane C requirements remain.
