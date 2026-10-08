# T110R2 formal controlled ABBA — revision 2 after desktop session restore (2026-10-07)

Same frozen design as T110 (protocol 25d1635b…): baseline `17b2a714…` (accepted T057) vs candidate `6a7aa03b…` (point corner reuse + owned callback-context tracking), identical non-JFR controlled-input plugin `e03edb6d…`, single production premain, two reversed-order pairs judged separately, gates unchanged (pair CPU regression ≤ 0%, strict wall improvement, operation peak RSS ≤ +20%, ≤ 80 MiB above own baseline, retained-3rd-minus-1st ≤ 64 MiB).

T110's baseline1 host-environment failure (no user desktop session) is preserved in callback-controlled-abba-review-20261004.json; this revision re-runs all four legs under the restored rain desktop session (Xwayland :0 owned by rain, DISPLAY=:0 verified accessible). New frozen prepared inputs; no replacement of the failed leg's records. Performance acceptance NOT granted by this protocol alone.
