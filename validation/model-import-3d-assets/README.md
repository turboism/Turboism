# 授权苹果模型的离线参考

这些脚本只准备和分析测试素材，不启动 Cubism、不代表已经支持苹果导入。
原文件 `~/Downloads/3D-apple.rar` 保持不变。

- `convert-apple-obj.py`：只接受已核对哈希的高细分 OBJ，保留两个源组、几何和 UV；四边形固定沿 0–2 对角线三角化，生成无材质 GLB 与来源清单。零值第三纹理坐标被明确省略，float32 误差单独报告。不做减面、重居中、单位猜测或截图贴图。
- `apple-occlusion-reference.py`：在原始 150,784 个三角形上做双面正交深度光栅化，分别记录每个组件的最近深度。自动适配的参考相机参数写入报告；标签灰度不是材质。原生绘制与插值仍需另验。
- `verify-apple-occlusion-rays.py`：以独立的源空间 Möller–Trumbore 射线求交复核深度样本，避免仅靠同一个光栅器自证遮挡关系。

需要 Python、NumPy 和 Pillow。每次输出使用新目录，防止覆盖旧证据。

本模型在 yaw=-30、pitch=-30 的同一姿态下，组件 0 与组件 1 各有位于对方前方的重叠像素。独立射线复核一致，说明两个完整组件之间无法仅用一个固定先后顺序表达可见性。后续需要分块与分块内部的自遮挡处理，不能只提高 GLB 索引数量上限。

`apple-fbx.py` 读取固定哈希的 FBX 7400；`verify-apple-subdivision.py` 验证封闭网格 Catmull–Clark 细分位置及四边形连接关系。逐组件坐标平移显式记录，位置匹配阈值为 0.001 源单位；不验证 UV、绕序、材料或原生导入。运行：

```sh
python3 -B validation/model-import-3d-assets/verify-apple-subdivision.py --source build/model-import-3d-inputs/apple-20261001/apple --out build/model-import-3d-offline/apple-subdivision-new
```

已验证果梗 3 级与果身 5 级重建位置、连接关系吻合源 OBJ；较低级别仍需单独测量误差，不能直接用于验收。

`measure-apple-levels.py --cages <subdivision-output> --reference <original-depth-output> --out <new-directory>` 测量四组细分级别的轮廓、组件归属及深度差。固定参考镜头和三个姿态，不屏蔽边界；深度差并非顶点投影误差，测量结果不作为原生验收。完整重建仍可能采用不同的四边形对角线，不能只凭顶点和四边形连接一致断言三角形表面完全一致。

`prepare-apple-bsp-input.py` 将固定哈希高精度 GLB 的全部源三角形与 UV 精确抽取为离线 BSP 探针输入（BSP1，小端，两个网格各有三角形计数，每角点 XYZUV float32）。对应 `selfcheck/AppleBspProbe.java` 仅调用分块算法，不调用原生入口。输出输入哈希与面数清单；预算拒绝不得视为导入通过。

`measure-apple-self-occlusion.py` 记录原始模型在三个固定参考姿态下的正面最近/最远深度，区分超过 1e-5 源单位的多层覆盖。`verify-apple-front-layers.py` 用独立源空间射线核验见证的两层深度和正面命中；不等同原生验证。

2026-10-02 用户授权适量扩大上限后，完整苹果 BSP 计算已完成，但产生约 30 万分片。`AppleBspProbe` 可显式指定计算预算和组件范围；组件测试必须记录 scope，不能冒充整个苹果。完整结果与资源开销见 `build/model-import-3d-offline/apple-expanded-budget-20261002/`。

`measure-apple-fixed-order.py` 测量一份固定三角形顺序（默认源重心 Z 降序；`--order` 可指定 Java 输出的小端 int32 源三角形排列），软件 painter 只按覆盖与顺序写入，核验源深度与组件归属。该候选顺序不能当作全域证明。`apple-occlusion-reference.py --grid25` 生成 25 个固定联合姿态参考，默认仍为原三姿态；旧参考数组逐值回归一致。

`prepare-apple-control-charts.py` 将全部原三角形角点归属到 98 个细分根四边形，图表坐标与原贴图 UV 分开；精确核对原 GLB 的位置、UV 与 0–2 对角线，不减面。最初 3 格控制网格只是容量估计，不是可用曲面拟合。

`OfficialWarpKernelProbe.java` 在固定 SHA256 的官方 5.3 JAR 中只调用 Warp 数学单例，不打开编辑器；恒等、仿射、边界与控制点脉冲验证表明 `quadTransform=true` 是双线性，`false` 是三角形线性。不能因编辑网格含 Bezier 类而假定运行时执行三次插值，也不能把该离线调用记为宿主、Core 或导出验收。

`measure-apple-warp-fit.py` 拟合原位置在图表中的双线性控制网格；对齐源采样的边界固定到相同源顶点，检查完整边连续性，接缝以原顶点身份核对，不按相同位置/UV 焊接。所有源三角形与 UV 仍作为参考保留。使用与生产 `SurfaceAutoCamera` 一致的 AABB 中点及最远实际顶点半径（400px），测量空间拟合误差，并单独计算联合参数线性插值误差。官方数学核回放包括 25 个固定姿态及 25 个非绑定点姿态。参数插值由离线理想双线性计算，尚不是原生参数求值器。

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 -B validation/model-import-3d-assets/measure-apple-warp-fit.py \
  --charts build/model-import-3d-offline/apple-control-charts-20261002 \
  --out build/model-import-3d-offline/apple-warp-fit-new \
  --kernel-classes build/model-import-3d-offline/apple-warp-kernel-20261002/classes \
  --lib '<proton-prefix>/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib' \
  --replay-cells 16 --replay-key-count 17
```

2026-10-02 正确菜单相机的测量：16 格图表、238×119 Warp、17×17 参数点需要 8,288,520 个控制样本，纯 XY float 最小 66,308,160 字节。空间误差全域上界 0.2143px，理想参数误差上界 0.4284px，合计 0.6426px（另计浮点及原生求值）；50 姿态官方数学核回放最大源投影偏差 0.4478px，核与理想空间基函数差 <0.000151px。旧 r2–r5 使用较大的 AABB 角点球取景，保留为探索记录，不能拿其较小误差与菜单结果混用。

证据为 `apple-warp-fit-r7-dense-20261002/report.json`；当前总样本硬上限适度提高到 10,000,000，最大纯坐标存储 80MB，其他对象/历史/贴图另计。苹果仍未进入生产原生导入：尚缺显式 Warp 形态目标写入、原生索引顺序/遮挡、保存重开与官方导出验证。`selfcheck/AppleKeyDensityProbe.java` 对照直接绑定原网格的内存需求，不分配大形态或写入宿主；17×17 仍需约 227MB 纯坐标，且超单批写入上限。

`prepare-apple-warp-fixture.py` 在容量分配前拒绝超限请求，将原固定顺序三角形按 `(源组件, GLB槽, 图表)` 身份打包为 ≤65535 顶点网格，逐三角形核验原 XYZ、UV、源位置/UV索引，另外记录派生的 Warp 局部输入坐标；生成 289 个控制形态。它只准备离线原生作者输入，未修改源文件、未创建 Cubism 工程，不能当作生产准入。
