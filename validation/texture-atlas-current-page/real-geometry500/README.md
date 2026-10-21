# Geometry500 真实宿主排版

输入：`test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_500.cmo3`

SHA-256：`99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b`

宿主 Cubism 5.3.03；插件 Dalsoo Polygon；8192²；间距3px；网格模式；自动倍率；允许旋转。

- Job: `ebe50be7-bed3-49f2-87b4-372cad22ec26`
- Run: `queue-f3bcfc7ccb8f461aac2b2e2281df364c`
- Prepared: `39d953d47094f838697e2fa599fbc545e94440985e2308ff645fc167c04bd93b`
- 算法耗时：12465.3775 ms；UI计时：12853.8949 ms。均为有探针的单次耗时。
- 输出500项，倍率1；overlaps=0；overflow=[]；finite/inside/layerMatch=true。

`layout-before.png` / `layout-after.png` 为 niri 对本任务“编辑纹理集”窗口的直接截图。`comparison.png` 仅裁剪两图的相同画布区域并横向拼接，无重排、重绘或生成纹理。

结果呈左侧斜坡状，右侧留白较多；通过几何校验并不代表达到用户期望的紧凑形态。测试集包含复杂外观纹理，但本次插件使用的提取轮廓为500环/2000顶点，不能代表 heavy.cmo3 的复杂轮廓负载。heavy.cmo3 本轮未运行。

此前两次采集接口得到黑屏；改为 niri 后取得可见截图。重复运行用于纠正截图，不作为速度提升对照。

内存文件记录整个宿主堆；单次完成无法排除宿主长期泄漏。内核分配优化与独立回归证据见上层 KERNEL-ALLOCATION.md。

截图采用临时验证探针（由上述 prepared 快照固定）；采集完成后已移除源码中的截图等待逻辑，避免为常规验证增加等待。

提交中的队列记录已将本机用户目录替换为 `<USER_HOME>`；标识与哈希保持原值。
