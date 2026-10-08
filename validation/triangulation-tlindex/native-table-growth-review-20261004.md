# T093 索引表按需扩容源码审查

PASS_SOURCE_AND_OWNED_RUNTIME_CONTROLS_NOT_PRODUCTION_ARTIFACT_ACCEPTANCE。在 T092 完整命令缓存边界差分通过后，修改生产 `NativeMeshEdgeTable` 和 `NativeMeshEdgeLookup`：初始缓冲按当前边列表大小分配，原有最坏新增量仅作为不变的 entryLimit；新增唯一边到达半满时才扩容。重复键保持最低物理 slot，字面有符号端点语义不变。

扩容先预留完整 replacement 字节，同时保留 old 预留；旧、新缓冲总预留仍必须满足4MiB过程预算。重新散列完成后替换缓冲并释放旧预留。预算拒绝则丢弃部分表，后续返回 UNKNOWN 走原生查找；分配异常归还预留并按原异常通路传播。未改变原生网格、缓存、版本、重绘或 callback 行为，也未引入池化或强制 GC。预算是声明缓冲上限，不是 JVM 堆或 RSS 上限。

9项focused测试通过，含14,000键跨多次扩容的独立最低slot对照、初始预留显著小于最坏容量、扩容预算压力下的UNKNOWN与精确释放，以及原有有符号/重复/线程/并发预算控制。真实三版本 SDK、双断言、当前生产 runtime helper/patcher 的自有加载器矩阵通过：288完整操作夹具、288准入不可用回退夹具、211helper控制；最终预留为0。完整操作覆盖同长度修改、immutable/进度/后缀异常等控制。

此矩阵使用当前生产源码的 helper 与 patcher，但定义准入前端是自有控制，不是新工件的真实 sole-premain 验证。尚未构建新候选、补充大量新增边的原生扩容控制或证明实机分配/RSS收益；此前按initialEntries估算的容量节省不能当作新实现实测。

下一步构建精确集成工件，补充原生扩容密集控制并核验完整实际准入，然后执行独立内存证据与新冻结正式性能对比。原 T085 尚未获性能交付，T088 RSS失败及历史失败、T057已接受交付、完整多版本真实宿主/长期/Lane C范围保持。证据 `build/t093-native-table-growth-r1`。
