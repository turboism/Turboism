---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.artmesh-mirror
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing, symmetry
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: none
---

# 图形网格镜像

通过独立的画布按钮开关图形网格镜像。在普通建模模式选择图形网格，拖动菱形或轴线可自由平移轴心，拖动方形可旋转。双击轴控件重置到选中网格中心。与曲面变形器镜像独立开关，不注册快捷键。

轴心与旋转手柄的位置跟随画布缩放；手柄保持固定的屏幕大小以便点击，缩小到很低倍率时保留最小间距。轴线使用白色线芯和深色描边，黄色菱形用于移动，橙色方形用于旋转；手柄带不透明衬底和深浅描边。

切换到不同的图形网格（或改变多选网格集合）时，镜像轴自动回到当前选中网格的包围框中心。保持同一选择时，手动移动和旋转的轴会保留。
