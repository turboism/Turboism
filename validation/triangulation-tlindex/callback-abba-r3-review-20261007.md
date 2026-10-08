# T110R3 formal callback ABBA — failed gates (2026-10-07)

Four frozen legs (2678/2679/2680/2681) all passed lifecycle, 2133-row controlled parity, and kernel CPU unit checks.

| pair | wall | Java CPU | peak RSS | above own baseline | retained 3-1 |
|---|---:|---:|---:|---:|---:|
| baseline1→candidate1 | -2.04% | -1.19% | -29.8% | **145.5 MiB FAIL** | 11.2 MiB |
| baseline2→candidate2 | -1.27% | **+4.53% FAIL** | +1.8% | **388.1 MiB FAIL** | -56.3 MiB |

Decision: candidate `6a7aa03b…` NOT adopted; delivery remains accepted T057 `17b2a714…`. The point-reuse + callback-context candidate pays an inconsistent CPU direction and RSS-above-baseline cost that exceeds the frozen gates.

Context (preserved, not an excuse): the baseline1 leg itself showed extreme memory behaviour (peak 4303 MiB, retained growth 1182 MiB) unlike the other three legs (~2.9-3.0 GiB) right after the desktop session was restored; frozen gates were applied as-is with no outlier removal.

Prior failures (T106, T110 environment, T110R2 observer) remain UNCHANGED. No merge, push or release.
