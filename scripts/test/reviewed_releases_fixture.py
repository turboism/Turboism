"""Synthetic canonical tables for tool fixtures (no licensed host assets)."""
import json


def table(versions):
    return json.dumps({'format': 'turboism.reviewed-releases/v1', 'releases': [
        dict(version=v, build=i + 1,
             artifact=dict(name='Live2D_Cubism.jar', size=i + 1, sha256=f'{i + 1:064x}'),
             core=dict(major=5, minor=0, patch=0), fullRuntime=True,
             hostLabel=f'Live2D Cubism {v}') for i, v in enumerate(versions)
    ]})
