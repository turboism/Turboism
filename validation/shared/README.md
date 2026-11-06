# shared/ — validation 探针共享工具箱（单一源）

各 `validation/<probe>/build.sh` 在编译期直接 `javac` 本目录源码，替代曾经的每目录副本（RelocateJar 曾四副本分叉）。

- `src/dev/turboism/validation/shared/tools/RelocateJar.java` — ASM jar 重定位工具。调用：`java -cp <asm jars> dev.turboism.validation.shared.tools.RelocateJar <in.jar> <out.jar> <from/prefix> <to/prefix>`。含 module-info 剥离与输出名归一注释。
- `src/dev/turboism/validation/shared/fixture/FixtureLoader.java` — child-first `URLClassLoader`；run.sh 以 `-D<prop>.expectLoader=dev.turboism.validation.shared.fixture.FixtureLoader` 断言。
- `src/dev/turboism/validation/shared/fixture/CodeSourceUrl.java` — 打印目录 URL 的外部形式，推导期望 codeSource。
- `src/dev/turboism/validation/shared/verdict/ProbeTerminal.java` — 探针的封闭状态模型与判定规则（`PASS`/`FAIL`/`BLOCKED`/`SKIPPED` 参与终态，`INFO` 仅作证据）。状态是枚举常量，未知状态拼写无法编译。
- `src/dev/turboism/validation/shared/verdict/ProbeAssertions.java` — 断言记录器（`record(name, expected, actual, ProbeTerminal)`）、计数器与结果行布局；结果尾部只能经 `appendPublication(report, Records, terminalKey)` 发布。

`verdict/` 的两个类是四个终态探针（animation-timeline、atlas-polygon、clipmask-viewer、parameter-batch-transfer）唯一的判定来源。离线用例：`bash validation/shared/check-probe-terminal.sh`。

- `probe-build.sh` — 插件探针的共享构建步骤：`probe_sdk_jar` 按 `scripts/dev/worktree-id.sh` 定位当前 worktree 的 SDK jar，`probe_build_plugin_jar <base> <out.jar> <sources...>` 完成 javac、META-INF 暂存、打包，并要求 `META-INF/turboism/plugin.json`（及 `PROBE_REQUIRED_ENTRIES`）真的进包；最后打印产物与 sha256。调用方只声明自己的源码、产物路径与额外事实（`PROBE_JAVAC_FLAGS`）。**不新增构建依赖**：描述符契约不在共享层重写，探针自己的场景事实（如 parameter-lock 的 schema/id/permissions/UI）在各自 build.sh 对已打包 jar 断言，仍只需 SDK jar。
- `verdict/ProbeAssertions.appendPublication` 是唯一的结果文件尾部发布点：counters → 断言记录 → 终态行，顺序不再由各探针各自维护；探针只提供业务断言与头部/证据行。

改本目录文件前先跑 `python3 scripts/test/check_validation_tools_sync.py .`（devCheck 也跑）：它拒绝 validation/ 下出现同名副本；有意例外在脚本 `CANONICAL`/`DECLARED_EXCEPTIONS` 里登记。
