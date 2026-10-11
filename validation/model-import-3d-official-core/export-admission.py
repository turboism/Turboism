"""Recognize explicit bounded source-sampling receipts without relabeling them PASS."""
SOURCE_SHA = 'dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'


def admitted(receipt):
    if receipt.get('status') == 'PASS':
        return True
    if receipt.get('status') != 'ACCEPTED_WITH_USER_APPROVED_PIXEL_DEVIATION':
        return False
    policy = receipt.get('pixelDeviationPolicy', {})
    if not (policy.get('sourceTextureSha256') == SOURCE_SHA
            and policy.get('maxFraction') == .001
            and policy.get('channelTolerance') == 2
            and policy.get('alphaExact') is True):
        return False
    phases = ('atlas-baseline', 'atlas-packed', 'atlas-repacked', 'atlas-redone')
    expected = {(phase, i, None) for phase in phases for i in range(9)}
    expected |= {(phase, i, 'baseline-all-pixels') for phase in phases[1:] for i in range(9)}
    checks = policy.get('checks', [])
    if not isinstance(checks, list) or len(checks) != len(expected):
        return False
    observed = set()
    for row in checks:
        key = (row.get('phase'), row.get('index'), row.get('comparison'))
        if key not in expected or key in observed:
            return False
        observed.add(key)
        count, total = row.get('mismatches'), row.get('samples')
        if not (type(count) is int and type(total) is int and total > 0
                and 0 <= count <= total * .001):
            return False
    warnings = receipt.get('warnings')
    return (observed == expected and isinstance(warnings, list) and bool(warnings)
            and all(isinstance(w, str) and (w.startswith('persistence pixels differ: ')
                    or w == 'atlas changed rendered pixels') for w in warnings))
