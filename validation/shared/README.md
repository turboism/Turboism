# shared/ — validation 探针共享工具箱（单一源）

各 `validation/<probe>/build.sh` 在编译期直接 `javac` 本目录源码，替代曾经的每目录副本（RelocateJar 曾四副本分叉）。

- `src/dev/turboism/validation/shared/tools/RelocateJar.java` — ASM jar 重定位工具。调用：`java -cp <asm jars> dev.turboism.validation.shared.tools.RelocateJar <in.jar> <out.jar> <from/prefix> <to/prefix>`。含 module-info 剥离与输出名归一注释（以 atlas-image-timing 最全副本为准）。
- `src/dev/turboism/validation/shared/fixture/FixtureLoader.java` — child-first `URLClassLoader`；run.sh 以 `-D<prop>.expectLoader=dev.turboism.validation.shared.fixture.FixtureLoader` 断言。
- `src/dev/turboism/validation/shared/fixture/CodeSourceUrl.java` — 打印目录 URL 的外部形式，推导期望 codeSource。
- `src/dev/turboism/validation/shared/verdict/ProbeTerminal.java` — 探针终态集合与判定规则（`PASS`/`FAIL`/`BLOCKED`/`SKIPPED`）。
- `src/dev/turboism/validation/shared/verdict/ProbeAssertions.java` — 断言记录器与结果行布局；记录时校验状态字面量。

`verdict/` 的两个类是四个终态探针（animation-timeline、atlas-polygon、clipmask-viewer、parameter-batch-transfer）唯一的判定来源。离线用例：`bash validation/shared/check-probe-terminal.sh`。

改本目录文件前先跑 `python3 scripts/test/check_validation_tools_sync.py .`（devCheck 也跑）：它拒绝 validation/ 下出现同名副本；有意例外在脚本 `CANONICAL`/`DECLARED_EXCEPTIONS` 里登记。
