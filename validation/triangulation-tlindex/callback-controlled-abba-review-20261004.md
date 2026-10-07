# T110 formal controlled ABBA — host environment failure (2026-10-04/07)

Frozen protocol `25d1635b…` (task e07216ea5 lineage). First leg baseline1 seq2676 failed at host readiness, controller stopped per protocol (no replacement).

## Failure

- Launcher reached "launching exact Cubism 5303 through official BAT" then readiness timeout after 240 s; runner exit 1.
- Gates preserved: validation FAIL, cleanup safe, no normal exit.

## Environment diagnosis (read-only)

- The shared worker had died; this session restarted `host_validation.py serve` from a tty session (no DISPLAY).
- Xwayland :0 is owned by the greeter (uid 948) login-screen session, started 00:59 UTC; `who` shows only rain SSH sessions — no user desktop session exists.
- `launch.sh` exports DISPLAY=:0; X clients now fail with "Authorization required". The user desktop session that ran all prior Cubism legs is gone.

## Conclusion

Environment blocker, not an artifact or protocol regression. The four frozen legs stay as-is; rerun requires the user desktop session restored (graphical login as rain), then `run-all.py` refuses to double-submit only after a fresh controller decision — the failed baseline1 is preserved and excluded; a new protocol revision will re-run all four legs unchanged.

Production remains the accepted T057 artifact. No merge, push or release.
