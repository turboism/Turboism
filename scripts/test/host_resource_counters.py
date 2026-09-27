"""Read-only CPU/DRM counters and explicit-unit interval calculations."""
import os
from pathlib import Path
import stat
import time


def cpu_snapshot(text):
    fields = text.rsplit(')', 1)[1].split()
    return {'ticks': int(fields[11]) + int(fields[12]), 'started': int(fields[19]),
            'ticksPerSecond': os.sysconf('SC_CLK_TCK'), 'logicalCpus': os.cpu_count(),
            'monotonicNs': time.monotonic_ns()}


def cpu_interval(before, after):
    duration = (after['monotonicNs'] - before['monotonicNs']) / 1e9
    if (duration <= 0 or before['started'] != after['started']
            or before['ticksPerSecond'] != after['ticksPerSecond']
            or before['logicalCpus'] != after['logicalCpus'] or after['ticks'] < before['ticks']):
        raise ValueError('invalid CPU interval/identity')
    seconds = (after['ticks'] - before['ticks']) / after['ticksPerSecond']
    return {'cpuSeconds': seconds, 'wallSeconds': duration, 'corePercent': 100 * seconds / duration,
            'machinePercent': 100 * seconds / duration / after['logicalCpus']}


def parse_drm(text):
    fields = dict(line.split(':', 1) for line in text.splitlines() if ':' in line)
    fields = {key: value.strip() for key, value in fields.items()}
    if not all(key in fields for key in ('drm-driver', 'drm-pdev', 'drm-client-id')):
        return None
    engines, capacity, resident = {}, {}, {}
    for key, value in fields.items():
        if key.startswith('drm-engine-capacity-'):
            capacity[key.removeprefix('drm-engine-capacity-')] = int(value)
            if int(value) <= 0:
                raise ValueError('invalid DRM engine capacity')
        elif key.startswith('drm-engine-'):
            amount, unit = value.split()
            if unit != 'ns' or int(amount) < 0:
                raise ValueError('invalid DRM busy counter')
            engines[key.removeprefix('drm-engine-')] = int(amount)
        elif key.startswith('drm-resident-'):
            parts = value.split()
            multiplier = {'KiB': 1024, 'MiB': 1024**2}[parts[1]] if len(parts) == 2 else 1
            if int(parts[0]) < 0:
                raise ValueError('invalid DRM resident counter')
            resident[key.removeprefix('drm-resident-')] = int(parts[0]) * multiplier
    return {'driver': fields['drm-driver'], 'device': fields['drm-pdev'], 'id': fields['drm-client-id'],
            'enginesNs': engines, 'capacity': capacity, 'residentBytes': resident}


def deduplicate(clients):
    result = {}
    for client in clients:
        key = '/'.join(client[field] for field in ('driver', 'device', 'id'))
        if key not in result:
            result[key] = client
            continue
        previous = result[key]
        if previous['capacity'] != client['capacity'] or previous['enginesNs'].keys() != client['enginesNs'].keys():
            raise ValueError('inconsistent duplicated DRM client')
        for engine, value in client['enginesNs'].items():
            previous['enginesNs'][engine] = max(previous['enginesNs'][engine], value)
        # Keep one resident snapshot, never sum duplicated descriptors.
        previous['residentBytes'] = client['residentBytes']
    return result


def gpu_snapshot(root):
    clients, missing = [], 0
    complete = True
    for info in (root / 'fdinfo').iterdir():
        try:
            details = (root / 'fd' / info.name).stat()
            if not stat.S_ISCHR(details.st_mode) or os.major(details.st_rdev) != 226:
                continue
            client = parse_drm(info.read_text())
            if client is None or not client['enginesNs']:
                missing += 1
            if client is not None:
                clients.append(client)
        except (FileNotFoundError, ProcessLookupError):
            complete = False
    return {'clients': deduplicate(clients), 'unavailableDescriptors': missing,
            'complete': complete, 'monotonicNs': time.monotonic_ns()}


class GpuIntervals:
    """Retain counter high-water values as required by the DRM usage ABI."""
    def __init__(self):
        self.previous = None
        self.high = {}

    def add(self, snapshot):
        previous = self.previous
        self.previous = snapshot
        clients = snapshot['clients']
        prior_high = dict(self.high)
        for key, client in clients.items():
            for engine, value in client['enginesNs'].items():
                identity = (key, engine)
                self.high[identity] = max(value, self.high.get(identity, 0))
        if (previous is None or not clients or not snapshot['complete'] or not previous['complete']
                or snapshot['unavailableDescriptors'] or previous['unavailableDescriptors']
                or clients.keys() != previous['clients'].keys()):
            return None
        duration = snapshot['monotonicNs'] - previous['monotonicNs']
        if duration <= 0:
            raise ValueError('invalid GPU sampling interval')
        busy, capacities = {}, {}
        for key, client in clients.items():
            old = previous['clients'][key]
            if client['enginesNs'].keys() != old['enginesNs'].keys() or client['capacity'] != old['capacity']:
                return None
            for engine in client['enginesNs']:
                target = client['device'] + '/' + engine
                capacity = client['capacity'].get(engine, 1)
                if target in capacities and capacities[target] != capacity:
                    raise ValueError('inconsistent device engine capacity')
                capacities[target] = capacity
                identity = (key, engine)
                busy[target] = busy.get(target, 0) + self.high[identity] - prior_high[identity]
        return {'wallNs': duration, 'busyNs': busy,
                'percent': {key: value / duration / capacities[key] * 100 for key, value in busy.items()}}
