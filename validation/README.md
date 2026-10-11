# validation/ — 场景探针与证据目录

本目录存放**场景级验证探针**：每个子目录是一个自包含场景（build.sh 编译 + run.sh 驱动 + 证据文件），多数需要真实 Cubism Editor 宿主。与 `testing/`（JUnit 自动化测试）的分工见 `testing/README.md`。

## 布局约定

- `<probe>/build.sh` — 编译探针工具与被测类；可在无宿主环境跑编译期断言。
- `<probe>/run.sh` — 场景驱动脚本；产出 `work/` 下证据与 verdict 日志。
- `<probe>/tools/`、`fixture/`、`selfcheck/` — 场景私有源码。**共享工具一律用 `validation/shared/src/`**（见下）。
- 证据文件（日期化 `*.md`/`*.json`/日志）随目录入库；`triangulation-tlindex/` 另见 `INDEX.md` 的保留/归档策略。

## shared/ — 单一源工具箱

`shared/src/dev/turboism/validation/shared/`：

- `tools/RelocateJar.java` — ASM 包名重定位 jar 工具（`RelocateJar <in.jar> <out.jar> <from/prefix> <to/prefix>`），各 build.sh 直接 `javac` 此文件并以 `dev.turboism.validation.shared.tools.RelocateJar` 调用。
- `fixture/FixtureLoader.java` — child-first `URLClassLoader`（同名 fixture 类不回落系统 classpath）。
- `fixture/CodeSourceUrl.java` — 打印目录的 `Path.toUri().toURL().toExternalForm()`，供 run.sh 推导期望 codeSource。

历史：RelocateJar 曾四副本分叉（triweave 副本丢 module-info 剥离注释），FixtureLoader/CodeSourceUrl 双副本；2026-10 收敛为单一源。`checkValidationToolsSync`（`scripts/test/check_validation_tools_sync.py`，挂 `devCheck`）拒绝目录内再出现同名副本；有意例外（meshhash 字节数组 loader、T033/T035 私有 defineClass loader）在脚本内点名。SelfCheck/Probe 各目录实现本就不同，不在收敛范围。

## 新探针放哪

- 需要真实编辑器宿主的场景探针 → `validation/<probe>/`（仿 triangulation-* 结构）。
- 可由 JUnit 覆盖的 verdict 逻辑 → `testing/integration-tests/`（`dev.turboism.tests.plugin.*Probe` + 配对 `*Test`）。
- 宿主内 agent（打进 jar 在编辑器进程里跑）→ `testing/host-validation/`。

## 无 README 目录说明

下列目录为老探针，无独立 README（见各 build.sh/run.sh 头部注释了解用法）：`supply-chain/`（供应链/依赖审计脚本目录，非探针）。其余探针目录均已补最小 README；新增目录请附一句用途 + 运行方式 + 是否需真实宿主。
