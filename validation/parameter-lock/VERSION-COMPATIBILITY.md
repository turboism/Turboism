# 参数锁版本兼容性

范围：20 个已审核的精确 Cubism Editor 版本（5.0.00–5.0.06、5.1.00–5.1.03、5.2.00–5.2.03、5.3.00–5.3.04）。

## 能力覆盖

- `cubism.model.observe` 根据模型读取及项目读取能力计算，覆盖模型身份与文档生命周期依赖。
- `cubism.model.intercept` 根据模型写入能力和参数钩子安装器使用的三个精确选择器共同计算：`parameter-operation.set-value`、`parameter-source.id`、`id.value`（均位于 `cubism.editor-model` 命名空间）。记录与选择器均须为 `VERIFIED_STATIC`。
- 任一必要能力、选择器或类 pin 缺失时，其对应版本不能计入该项能力的可用集合。生成器不再忽略缺失的已声明依赖。模型观察、参数拦截均已从 `unmappedCapabilities` 中移除。

运行时仍按已验证的每版本选择器绑定参数入口，保留既有宿主准入与 classloader 校验。未增加模糊版本匹配，也未将某个版本的混淆类名复制为所有版本共用入口。

## 可重复验证

```bash
python3 scripts/test/test_generate_compatibility_matrix.py
python3 scripts/generate_compatibility_matrix.py --check
TURBOISM_CUBISM_REF_ROOT=/path/to/cubism-ref \
  ./gradlew :runtime:parameterLockArtifactTest :plugins:parameter-lock:test
```

证据目录须包含 `<精确版本>/Live2D_Cubism.jar`。专用任务未配置目录时直接失败；常规 runtime 测试在未提供原始 JAR 时跳过此项外部证据测试。

逐版本测试读取当前提交的 Editor-model 与 control-appearance 记录，通过生产 Java 校验器校验原始 JAR 身份及完整选择器，然后运行实际参数写入与原生行 UI 变换器，检查：

1. 原生参数写入入口包含 `beforeNative`、`afterNative`、`failedNative`，并调用该版本的 source-id 与 id-value 入口。
2. 宿主 classloader 不匹配时不变换。
3. 项目生命周期 profile 的全部入口确实存在于该版本原始 JAR。
4. 单参数行、双参数行、参数组行的构造与选择入口包含对应原生 appearance bridge 回调。

插件单元测试另行覆盖锁定/解锁、异步加载、递归组锁、持久化及模型隔离。JAR 字节码测试不替代真实 Editor 内的滑杆、文本输入、Undo/Redo 等交互验收；既有实机结果仍以 [ROW-UI-ACCEPTANCE.md](ROW-UI-ACCEPTANCE.md) 为准。

## 本轮验证结果（2026-10-22）

| 检查 | 结果 |
|---|---|
| 兼容性生成器回归 | 19 项通过，包含缺少每个必要选择器、未验证选择器及缺少项目能力的负向用例 |
| `:runtime:parameterLockArtifactTest` | 20 项通过，0 跳过 |
| `:plugins:parameter-lock:test` | 42 项通过，0 跳过 |
| 生命周期与原生 control-appearance 回归 | 137 项通过，0 跳过 |
| 参数钩子安装器回归 | 1 项通过，0 跳过 |
| `checkCodeQuality`、Python Ruff、生成产物 `--check` | 通过 |

当前生成矩阵中，参数锁支持版本和完整功能版本均为上述 20 个审核版本，两项钩子能力不再处于未映射状态。
