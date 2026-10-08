# 全安装版本验证台账（进行中）

当前表不是最终支持承诺。静态记录、启动成功、验证工具缺口和能力不支持分别记录。
支持列仅列已通过的具体场景范围；不能推导同服务其他方法或整项能力通过。未验证不得填入支持或不支持。

基线：`7d7de1de4`；20 个安装版本；131 个静态能力标识；46 个可选 SDK 服务；30 个插件；140 个目录测试场景。

编译后 SDK 成员范围见 [方法覆盖索引](sdk-method-coverage.json)：894 个公开／受保护类型、5,389 个方法／构造器、1,063 个字段，包含 incubating 与编译生成成员。62个方法已关联实际场景（owned-MOC 21、MCP纹理/夹具准备/历史14、MCP Glue/参数/绑定27），其余仍待审计；此索引不会将部分实机结果提升为全部 API 支持。

| 版本 | 精确静态记录能力数 | 无精确记录能力数 | 支持能力集 | 不支持能力集 | 状态 |
| --- | ---: | ---: | --- | --- | --- |
| 5.0.00 | 90 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.01 | 90 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.02 | 90 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple）；MCP除authoring事务外场景（参数/绑定/纹理写入、五类保存重开、FREE夹具准备恢复） | 完整Drawable／Glue／Deformer；MCP authoring事务execute返回UNAVAILABLE；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.03 | 90 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.04 | 90 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.05 | 90 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.0.06 | 90 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.00 | 97 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.01 | 97 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.02 | 104 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple） | 完整Drawable／Glue／Deformer；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.1.03 | 104 | owned-MOC Canvas／参数／部件及生命周期（限Haru）；非空纹理关系图（限Simple）；MCP除authoring事务外场景（含五类纹理保存重开） | 完整Drawable／Glue／Deformer；MCP authoring事务execute返回UNAVAILABLE；其他待证 | 当前owned provider明确拒绝完整Drawable／Glue／Deformer；其他待证 | 部分场景通过；全能力进行中 |
| 5.2.00 | 123 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；非空纹理关系图（限夹具）；完整MCP场景（含Glue/参数/绑定/纹理保存重开/恢复） | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.2.01 | 123 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；非空纹理关系图（限夹具）；完整MCP场景 | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.2.02 | 123 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；非空纹理关系图（限夹具）；完整MCP场景 | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.2.03 | 126 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；非空纹理关系图（限夹具）；完整MCP场景 | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.00 | 129 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；非空纹理关系图（限夹具）；完整MCP场景；PSD原生仅保存观察 | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.01 | 129 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；完整MCP场景含非空Editor Glue／参数／绑定恢复、五类纹理保存重开及Screen／Multiply Color准备恢复；非空纹理关系图（限夹具）；PSD原生仅保存观察 | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.02 | 128 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；非空纹理关系图（限夹具）；完整MCP场景；PSD原生仅保存观察 | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.03 | 127 | owned-MOC结构getter／生命周期（限Haru；非空Glue待证）；非空纹理关系图（限夹具）；完整MCP场景；PSD原生仅保存观察 | 其他能力待证 | 其他能力待证 | 部分场景通过；全能力进行中 |
| 5.3.04 | 131 | owned-MOC结构getter／生命周期（限Haru；Core非空Glue待证）；完整MCP场景含Editor非空Glue、参数／绑定／纹理写入恢复与保存重开、命令执行恢复；非空纹理关系图（限夹具）；PSD原生仅保存观察；参数批量迁移全场景（keyform行含真实重映射、morph行含scaffold夹具wide/narrow/invert三行迁移+撤销/重做+存储态form关联+还原，narrow行实证morph按契约"原样搬移不clamp"）；动画时间线；工作区 | 其他待证 | 其他能力待证 | 部分场景通过；全能力进行中 |

5.3.04 MCP free-r6通过范围见[mcp-texture-coverage.json](mcp-texture-coverage.json)及原始client结果；Workspace control为UNAVAILABLE，Core诊断与验证专用native保存不能折算为对应完整生产能力。5.3.01 free-r2也已完整场景PASS（含Multiply Color）。5.2.02 free-r2停在现有Trial欢迎modal，复测待终态；旧owned-MOC通过仅对其当时冻结产物有效。

完整能力、SDK 服务、插件、场景及安装摘要见 [inventory.json](inventory.json)。
owned-MOC的20版实机范围与逐项证据见[owned-moc-verification.md](owned-moc-verification.md)。旧历史证据见[验证记录](README.md)，均不能折算为全能力通过。

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

纹理关系图三个代表版本PASS见[覆盖](texture-relations-coverage.json)。5.3.04 PSD原可读观察FAIL，native结果为SAVED_UNVERIFIED；新仅保存场景已独立supervisor PASS，见[覆盖](psd-save-observation-coverage.json)，不填补PSD内容可读、结构、保真、替换及外部编辑缺口。

动画时间线目录已与当前wrapper的可执行版本（5.2.00–5.3.04）对齐；5.0/5.1仍是工具准入缺口，不能据此记为能力不支持。参数批量迁移、动画和工作区新增显式assisted变体，辅助agent只允许绑定queue任务home，不退出宿主或验证生产能力。
