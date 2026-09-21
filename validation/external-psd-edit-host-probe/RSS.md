# RSS observation

`rss.py` is an optional Linux observer for the queued F1 pipeline. Pass it as the generic
Runner's `--remote-post-launch` hook. It uses the Runner's eleven context arguments and the
queue's containment record. It reads only the admitted cgroup and binds the unique exact
`com.live2d.cubism.CECubismEditorApp` process by PID and Linux start ticks.

The hook samples `VmRSS` every 50 ms until the probe writes its bound terminal result. It also
records `VmHWM`, whose interval starts at process creation. The report explicitly includes
startup in its observation scope; neither value is a JVM heap measurement. The hook writes
`external-psd-rss.json` under this task's evidence directory. Wrong scope, boot identity,
reused PID, multiple main-class processes, or a foreign result stop collection. Missing
process samples or zero samples produce incomplete evidence. It never signals processes,
changes the queue, or declares SC-006 passed.
The report carries the same job, attempt, run and prepared digest as containment, plus the
Linux CPU model, logical CPU count, physical memory and kernel used for the measurement.

This synchronous hook is for pipeline runs. GUI runs need the Runner's later readiness trigger
and must not use this hook. A saved-file reopen stage does not form part of the ten-save
performance interval; run the F1 performance pipeline separately from the two-stage driver.

Offline checks:

```bash
python3 scripts/test/test_external_psd_rss.py
```

The synthetic proc files used in these tests are not host evidence. The hook still requires a
real queued run before its observations can be used for performance review.
