# testing/ — JUnit 测试与宿主验证代理

本目录存放**可自动执行**的测试代码。与 `validation/`（人工/场景探针证据）的分工见下。

## 布局

- `integration-tests/` — Gradle 模块 `:testing:integration-tests`，全部 JUnit 测试源。
  - `src/test/java/dev/turboism/tests/plugin/` — `*Probe` 探针与其 `*Test` verdict 配对测试。惯例：每个探针至少有一个测试覆盖其 verdict 判定核心路径；环境依赖用 `Assumptions` 门控。
  - `src/test/java/dev/turboism/tests/validation/` — `WorkspaceValidationAgent` / `WorkspaceValidationUi`：打包进 workspace-validation bundle、在真实 Cubism Editor 内运行的宿主探针 agent（例外：名字带 validation 但物理上属于本模块，历史遗留）。
- `test-support/` — Gradle 模块 `:testing:test-support`，共享测试基础设施。
- `host-validation/` — 宿主验证 agent 源码（`image-archive/src/...` 的 11 个 `Native*HostAgent`/`Observation`/`Audit`）与各场景的 `config.json`（`float-array`、`image-archive`、`mesa-gl-thread`、`mesa-gl-thread-off`、`texture-upload`），由 `scripts/preview/host_validation.py` 驱动，仅 Linux + Proton + 正版 Cubism Editor 环境可运行。`experiments/` 为一次性原型代码。

## 新代码放哪

- 新的自动化测试/探针 verdict 测试 → `integration-tests/src/test/java/dev/turboism/tests/` 对应包。
- 新的真实宿主探针 agent → `host-validation/<场景>/`（附 `config.json`）或 `validation/<probe>/`（见 `validation/README.md` 的场景类探针）。
- 纯 shell/python 驱动的证据采集场景 → `validation/<probe>/`。

## 已知例外（不搬移，仅记录）

- `tests/validation/` 即 `integration-tests` 的 `dev.turboism.tests.validation` 包 —— 名字在 testing 侧但实为宿主 agent。
- `testing/host-validation/` 持有宿主探针 agent 而非 JUnit 测试 —— 需真实编辑器环境。
- `WindowsHistoryFloatProbe`（integration-tests）名为 history，实为 panel-floating 手动验证探针。
