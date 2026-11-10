# 离线纹理分区与方向布线原型

这是待人工检查的算法原型，未修改宿主插件行为。验证问题：先按纹理建立嵌套区域，再统一布点、均值合并，能否得到稀疏且方向明确的约束网格？

## 运行

在仓库根目录，准备 Python 环境及本项目 Java 产物：

```sh
python3 -m venv --system-site-packages /tmp/turbo-mesh-region-venv
/tmp/turbo-mesh-region-venv/bin/pip install -r plugins/turbo-mesh/prototypes/texture-regions/requirements.txt
./gradlew :sdk:jar :plugins:turbo-mesh:jar
```

稀疏预设（一条命令运行）：

```sh
/tmp/turbo-mesh-region-venv/bin/python plugins/turbo-mesh/prototypes/texture-regions/prototype.py plugins/turbo-mesh/prototypes/texture-regions/sample.png --output /tmp/turbo-mesh-region-result
```

较细预设增加 `--level 2 --spacing 42 --tolerance 2 --min-spacing 6`。

## 流程与阈值

1. 使用 PNG 原始 Alpha ≥ 128 作为轮廓。分割前用最近的不透明像素补齐边缘 RGB，避免透明区黑色影响色块分割。
2. 用 Felzenszwalb 图像图分割取得候选连通区域，按相邻区域的 Lab 均值色差合并。面积小于实体总面积的 **0.6%** 的区域优先并入颜色最接近的邻居。由细到粗建立父子树，再按粗到细查看。不是按矩形切图。
3. `--contrast 6` 控制区域合并；三级合并色差阈值为 3.9 / 6 / 9。默认采用最粗的 `--level 1`，而不是将所有细分层强制变成网格。
4. 外轮廓与共享分界线只提取一次。先对像素台阶轻度低通，保留多像素尺度的尖角，再按 `--tolerance` 指定的像素误差简化。默认最大采样间距为包围盒长边的 8.7%，简化容差为 0.375%，近点候选距离为 0.9%（分别可用 `--spacing`、`--tolerance`、`--min-spacing` 覆盖）。下方证据使用显式参数以固定输出。
5. 用实体像素的 PCA 估计长宽比。大于 2.2 且两端 10% 位置的截面宽度都小于最大宽度的 72% 时，沿长轴截面插值生成 **3 条向两端连接的弧**；长条硬表面采用平行直线引导；圆形/椭圆形使用以几何中心缩放的 **3 层同心环**。方向线先裁切到实体内部，避免穿过 Alpha 孔洞。与纹理线的所有交点先统一节点化，再向区域内部填点。
6. **全部布点完成后**，对距离小于 `--min-spacing` 指定像素距离的顶点尝试合并，位置取原始顶点的加权算术均值。多次合并按簇内原始点数加权。若导致区域消失、断环或新增边交叉则拒绝该次合并。该距离是候选距离，不保证所有点对最终都超过此距离。
7. 使用仓库已有 `ConstraintDelaunay` 三角化。Java 桥接将像素坐标缩放至 UV 数量级，避免误用该算法的固定容差。

`regions.npz`、`hierarchy.json` 保存区域层级；`mesh.json` 保存局部像素坐标网格和原图偏移；`constraint-roles.json` 区分纹理线、外轮廓、方向线。`sites-before-merge.json` 保存合并前数据。

## 当前结果与局限

见 [NOTES.md](NOTES.md) 和 `evidence/` 的真实离线产物。

这不是纹理重建或变形质量验证。区域标签来自自动分割，并没有手绘真值；色差阈值仍可能合并有效的弱阴影，或保留渐变的伪分界。轮廓简化与平均移动允许少量像素偏差，指标同时报告相对原始 Alpha 的 IoU，不能只用三角网格自洽证明贴图准确。

形状方向模型目前验证了单个圆、椭圆、纺锤轮廓与带两个 Alpha 孔洞的 USB 硬表面。复杂分叉、多个孤岛和强凹轮廓仍需进一步处理。PCA 分类与三个方向线只是本次可视验证预设。环的径向连接和弧之间的横向连接仍由 CDT 决定，尚未保证整齐的结构化拓扑，也没有实机形变测试。

在视觉结果被认可前保留在 prototype 目录，之后选择吸收到 Java 正式算法或删除；不要自动接入宿主。

## USB 硬表面样例

```sh
# 稀疏版：面积阈值 1.5%，色差阈值基准 8
/tmp/turbo-mesh-region-venv/bin/python plugins/turbo-mesh/prototypes/texture-regions/prototype.py plugins/turbo-mesh/prototypes/texture-regions/sample-usb.png --output /tmp/turbo-mesh-region-usb-sparse --min-region-ratio .015 --contrast 8 --spacing 32 --tolerance 1.2 --min-spacing 4
# 较细版：面积阈值 1%，其余采样距离按图形尺寸自动缩放
/tmp/turbo-mesh-region-venv/bin/python plugins/turbo-mesh/prototypes/texture-regions/prototype.py plugins/turbo-mesh/prototypes/texture-regions/sample-usb.png --output /tmp/turbo-mesh-region-usb --min-region-ratio .01
```

约束边允许由共线边链组成；验证其覆盖长度，容差为原图像素 1e-4。拓扑面积检查容差为 0.001 平方像素（SDK 顶点为 float32）。实际缺失面积仍原样保存在 metrics 中，不取整掩盖。
