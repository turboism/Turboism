"""Fixed ABBA gate calculation; never grants production acceptance."""
import math


ORDER = [('baseline1', 'baseline'), ('candidate1', 'candidate'),
         ('candidate2', 'candidate'), ('baseline2', 'baseline')]
PAIRS = [('baseline1', 'candidate1'), ('baseline2', 'candidate2')]
METRICS = ('wallSeconds', 'directCpuSeconds', 'sampledCpuSeconds',
           'operationPeakRssBytes', 'peakAboveBaselineBytes', 'retained3Minus1Bytes')


def evaluate(legs):
    if [(x['name'], x['arm']) for x in legs] != ORDER:
        raise ValueError('expected exactly four predeclared ABBA legs')
    sequences = [x['sequence'] for x in legs]
    if any(not isinstance(s, int) or s <= 0 for s in sequences) or any(
            a >= b for a, b in zip(sequences, sequences[1:])):
        raise ValueError('invalid submission order')
    if len({x['jobId'] for x in legs}) != 4 or len({x['runId'] for x in legs}) != 4:
        raise ValueError('duplicate jobs/runs')
    for leg in legs:
        if not all(leg[k] is True for k in ('lifecyclePass', 'outputMatch', 'cpuUnitsPass')):
            raise ValueError('incomplete lifecycle/output/CPU unit evidence')
        for key in METRICS:
            value = leg[key]
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise ValueError('invalid numeric metric')
        if any(leg[k] <= 0 for k in ('wallSeconds', 'directCpuSeconds', 'operationPeakRssBytes')):
            raise ValueError('nonpositive primary metrics')
    named = {x['name']: x for x in legs}
    pairs = []
    for base_name, candidate_name in PAIRS:
        base, candidate = named[base_name], named[candidate_name]
        changes = {k: (candidate[k] / base[k] - 1) * 100 for k in
                   ('wallSeconds', 'directCpuSeconds', 'operationPeakRssBytes')}
        gates = {
            'commandWallImproves': candidate['wallSeconds'] < base['wallSeconds'],
            'javaCpuTotalNoRegression': candidate['directCpuSeconds'] <= base['directCpuSeconds'],
            'absoluteRssGrowthWithin20Percent': candidate['operationPeakRssBytes'] <= base['operationPeakRssBytes'] * 1.2,
            'rssAboveOwnBaselineWithin80MiB': candidate['peakAboveBaselineBytes'] <= 80 * 1024 * 1024,
            'retained3Minus1Within64MiB': candidate['retained3Minus1Bytes'] <= 64 * 1024 * 1024,
        }
        pairs.append({'baseline': base_name, 'candidate': candidate_name,
                      'candidateChangePercent': changes, 'gates': gates, 'pass': all(gates.values())})
    totals = {arm: {key: sum(x[key] for x in legs if x['arm'] == arm)
                    for key in ('wallSeconds', 'directCpuSeconds', 'sampledCpuSeconds')}
              for arm in ('baseline', 'candidate')}
    change = {key: (totals['candidate'][key] / totals['baseline'][key] - 1) * 100
              for key in ('wallSeconds', 'directCpuSeconds')}
    return {'status': 'PASS_FIXED_BALANCED_GATES' if all(p['pass'] for p in pairs)
            else 'FAIL_FIXED_BALANCED_GATES', 'pairs': pairs, 'totals': totals,
            'aggregateCandidateChangePercent': change,
            'productionAcceptance': 'NOT_GRANTED_INSTRUMENTED_PROTOCOL',
            'statisticalBenefit': 'NOT_ESTABLISHED_TWO_PAIRS'}
