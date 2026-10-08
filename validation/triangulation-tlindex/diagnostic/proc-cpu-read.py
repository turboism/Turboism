"""Read one process stat with its own clock range, before PSS collection."""
import os
import pathlib
import time


def stat(p):
    begin_nanos = time.monotonic_ns()
    begin_epoch = time.time_ns() // 1_000_000
    s = (pathlib.Path('/proc') / str(p) / 'stat').read_text()
    f = s[s.rindex(')') + 2:].split()
    row = {'pid': p, 'startTicks': f[19], 'userTicks': int(f[11]),
           'systemTicks': int(f[12]), 'rssBytes': int(f[21]) * os.sysconf('SC_PAGE_SIZE')}
    end_epoch = time.time_ns() // 1_000_000
    end_nanos = time.monotonic_ns()
    if end_nanos < begin_nanos or end_epoch < begin_epoch:
        raise ValueError('process CPU read clock decreased')
    row.update(cpuReadStartEpochMillis=begin_epoch, cpuReadEndEpochMillis=end_epoch,
               cpuReadStartMonotonicNanos=begin_nanos, cpuReadEndMonotonicNanos=end_nanos)
    return row
