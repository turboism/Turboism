# Full-Installation Version Verification Ledger (In Progress)

This table is not a final support commitment. Static records, successful startup, verification-tool gaps, and unsupported capabilities are recorded separately. Every capability still needs a matching real assertion; an unverified capability must not be filled in as supported or unsupported.

Baseline: `7d7de1de4`; 20 installed versions; 131 static capability identifiers; 46 optional SDK services; 30 plugins; 140 directory test scenarios.

| Version | Capabilities with an exact static record | Capabilities without an exact record | Supported capability set | Unsupported capability set | Status |
| --- | ---: | ---: | --- | --- | --- |
| 5.0.00 | 90 | 41 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.0.01 | 90 | 41 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.0.02 | 90 | 41 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.0.03 | 90 | 41 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.0.04 | 90 | 41 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.0.05 | 90 | 41 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.0.06 | 90 | 41 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.1.00 | 97 | 34 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.1.01 | 97 | 34 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.1.02 | 104 | 27 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.1.03 | 104 | 27 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.2.00 | 123 | 8 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.2.01 | 123 | 8 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.2.02 | 123 | 8 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.2.03 | 126 | 5 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.3.00 | 129 | 2 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.3.01 | 129 | 2 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.3.02 | 128 | 3 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.3.03 | 127 | 4 | Pending item-by-item verification | Pending negative evidence | In progress |
| 5.3.04 | 131 | 0 | Pending item-by-item verification | Pending negative evidence | In progress |

See [inventory.json](inventory.json) for the full capability, SDK service, plugin, scenario, and installation summary. Narrow-scope host history is in the [verification records](README.md) and does not convert directly into an all-capability pass.

## Verification Scripts Not Yet Registered as Tasks

- `scripts/preview/run-atlas-image-host-validation.sh`
- `scripts/preview/run-atlas-image-shadow-host-validation.sh`
- `scripts/preview/run-atlas-polygon-host-validation.sh`
- `scripts/preview/run-bounding-box-overlay-host-validation.sh`
- `scripts/preview/run-cubism-host-validation.sh`
- `scripts/preview/run-external-psd-edit-host-validation.sh`
- `scripts/preview/run-float-array-host-validation.sh`
- `scripts/preview/run-fx-host-validation.sh`
- `scripts/preview/run-graal-script-host-validation.sh`
- `scripts/preview/run-image-archive-host-validation.sh`
- `scripts/preview/run-mesh-edit-host-validation.sh`
- `scripts/preview/run-plugin-management-chooser-host-validation.sh`
- `scripts/preview/run-resource-host-validation.sh`
- `scripts/preview/run-settings-page-host-validation.sh`
- `scripts/preview/run-texture-upload-host-validation.sh`
- `scripts/preview/run-warp-position-host-validation.sh`
