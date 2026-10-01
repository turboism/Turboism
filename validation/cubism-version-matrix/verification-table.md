# 全安装版本验证台账（进行中）

当前表不是最终支持承诺。静态记录、启动成功、验证工具缺口和能力不支持分别记录。
支持列仅列已通过的具体场景范围；不能推导同服务其他方法或整项能力通过。未验证不得填入支持或不支持。

基线：`7d7de1de4`；20 个安装版本；131 个静态能力标识；46 个可选 SDK 服务；30 个插件；135 个目录测试场景。

编译后 SDK 成员范围见 [方法覆盖索引](sdk-method-coverage.json)：894 个公开／受保护类型、5,389 个方法／构造器、1,063 个字段，包含 incubating 与编译生成成员。62个方法已关联实际场景（owned-MOC 21、MCP纹理/夹具准备/历史14、MCP Glue/参数/绑定27），其余仍待审计；此索引不会将部分实机结果提升为全部 API 支持。

| 版本 | 精确静态记录能力数 | 无精确记录能力数 | 支持能力集 | 不支持能力集 | 状态 |
| --- | ---: | ---: | --- | --- | --- |
| 5.0.00 | 89 | 42 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.01 | 89 | 42 | 待逐项验证 | 待负向证据 | 进行中 |
| 5.0.02 | 89 | 42 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.03 | 89 | 42 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.04 | 89 | 42 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.05 | 89 | 42 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.06 | 89 | 42 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.00 | 96 | 35 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.01 | 96 | 35 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.02 | 103 | 28 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.03 | 103 | 28 | owned-MOC Canvas／参数／部件及生命周期（限Haru） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.2.00 | 122 | 9 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.2.01 | 122 | 9 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.2.02 | 122 | 9 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.2.03 | 126 | 5 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.00 | 129 | 2 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.01 | 129 | 2 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.02 | 128 | 3 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.03 | 127 | 4 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证） | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.04 | 131 | 0 | owned-MOC结构getter／生命周期（限Haru；Core非空Glue待证）；完整MCP场景含Editor非空Glue、参数／绑定／纹理写入恢复与保存重开、命令执行恢复 | 其他能力待证 | 部分场景通过；全能力进行中 |

5.3.04 MCP free-r6通过范围见[mcp-texture-coverage.json](mcp-texture-coverage.json)及原始client结果；Workspace control为UNAVAILABLE，Core诊断与验证专用native保存不能折算为对应完整生产能力。5.2.02新包启动回归待复测，旧owned-MOC通过仅对其当时冻结产物有效。

完整能力、SDK 服务、插件、场景及安装摘要见 [inventory.json](inventory.json)。
owned-MOC的18版实机范围与逐项证据见[owned-moc-verification.md](owned-moc-verification.md)。旧历史证据见[验证记录](README.md)，均不能折算为全能力通过。

## 尚未登记为任务的验证脚本

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
