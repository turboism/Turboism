# 插件功能图标

Turboism 框架和第一方插件嵌入 Cubism 原生界面的自绘功能图标，在右下角统一使用紫色方形白色 `T` 标记，表示功能由 Turboism 第一方提供。保留主体轮廓及按钮状态，避免用户把这些入口当成原生功能。

**使用范围：此标记仅用于 Turboism 框架和第一方插件。第三方插件不使用此标记；接入 Turboism SDK、上架插件商店或兼容框架均不代表第一方身份。** 生成脚本是仓库内部的第一方素材工具，不作为自动标注第三方插件的规则。

- 标记宽度约为资源宽度的 28%，至少 7px；距右边和下边约为资源宽度的 1/32。
- 浅色描边使标记在深浅按钮背景上均可辨认；字形同时提供颜色以外的识别线索。
- 默认、悬停、按下及选中状态保留同一标记；禁用状态使用灰色。
- 当前覆盖选区笔刷、包围框镜像、Alt 对称切换和历史面板。已有品牌图形的 Turboism 首页、安装器、ACP 入口不再重复加标。
- 表示 ArtMesh、变形器、部件等对象类型的原生符号，以及通用设置齿轮，不使用功能来源角标。

## 深浅色模式适配

新增或修改 UI 图标时，必须遵守 [UI 外观与深色模式规范](../../sdk/plugin-author-guide.md#ui-appearance-and-dark-mode)，同时检查资源文件和运行时绘制路径。

- 优先使用透明背景，使按钮融入其所在工具条。不得在 PNG、代码生成的图像或 GL 状态纹理中烘焙白色、浅灰等固定浅色底板；确需实色背景时，应使用所在工具条的主题颜色。
- 透明背景不等于完成适配。主体线条、镜像轴、节点和文字在深色工具条下也必须清晰；避免仅使用黑色或过暗的关闭状态图形。需要时提供深浅两套图标，或选用两种背景下均可辨认的配色。
- 保留抗锯齿边缘的透明度；主体、角标和交互反馈均应在宿主实际显示尺寸及支持的 DPI 下检查。
- 检查默认、悬停、按下、开启/选中、关闭、禁用等支持的状态。关闭状态仍可点击，不能以禁用样式代替；模式不能只靠颜色区分。
- 对缓存的位图或 GL 纹理，检查显示期间切换主题的效果。使用透明底板及半透明反馈，或在主题变化时重新生成资源，避免保留旧主题底色。
- 修改无角标源图后重新生成发布资源，避免下次生成时覆盖修复。浅色/深色背景合成预览和 alpha 检查用于初步验证；最终宿主视觉效果仍需单独确认，并明确记录未完成的验证。

曲面变形器镜像和图形网格镜像按钮共用 `RuntimeViewContextMenuRegistry` 的 GL 状态图像生成逻辑。它们的源图即使透明，运行时叠加不透明浅色底板仍会破坏深色模式；检查这类问题时不能只查看 PNG。

## 独立素材

素材位于 [`assets/plugin-badge/`](assets/plugin-badge/)，可直接交给设计师或用于后续图标合成：

- [`turboism-plugin-badge.svg`](assets/plugin-badge/turboism-plugin-badge.svg)：正常状态，100×100 归一化坐标，纯几何图形，不依赖字体。
- [`turboism-plugin-badge-disabled.svg`](assets/plugin-badge/turboism-plugin-badge-disabled.svg)：禁用状态。
- 同名 PNG 提供 7、9、10、13、18、34、64px 两套状态。文件名末尾数字为角标本身的尺寸；画布不含放置边距。

SVG 用于编辑和缩放，PNG 用于像素尺寸精确合成。两者由同一组几何与颜色定义生成；不同渲染器的边缘抗锯齿可能略有不同。小尺寸优先使用对应 PNG，不要从 64px 图反复缩放。

| 色彩角色 | 正常状态 | 禁用状态 |
| --- | --- | --- |
| 背景 | `#6752C7` | `#BDBDBD` |
| T 字形 | `#FFFFFF` | `#EEEEEE` |
| 外沿 | `#F4F3FF` | `#E6E6E6` |

## 合成规范

角标保持正方形和固定方向，不拉伸、不旋转、不另换字体；不要增加阴影或随悬停改变颜色。它表示 Turboism 框架或第一方插件身份，不表示启用、选中或错误。按钮背景继续承担交互状态表达，只有禁用状态切换灰色角标。

尺寸按 `max(7, round(源图宽度 × 0.28))` 计算，右边距和下边距均为 `max(1, round(源图宽度 / 32))`；取整规则以 Python `round` 为准。

| 源图宽度 | 角标尺寸 | 右、下边距 |
| --- | --- | --- |
| 24px | 7px | 1px |
| 32px | 9px | 1px |
| 36px | 10px | 1px |
| 48px | 13px | 2px |
| 64px | 18px | 2px |
| 120px | 34px | 4px |

新图标设计时给右下角预留角标尺寸和边距，避免遮盖关键图形。此规则用于当前工具栏资源；更小或比例特殊的资源应先检查宿主实际显示尺寸。HiDPI 变体应按同一逻辑尺寸同比放大主体、角标和边距，避免分别取整造成位置跳动。

Python 合成入口：

```python
from plugin_icon_badge import render_plugin_badge, with_plugin_badge

badge = render_plugin_badge(9)  # 独立的 9×9 RGBA 素材
result = with_plugin_badge(source_image, disabled=False)
```

在 `scripts/artwork/` 外使用时，将该目录加入 Python 模块搜索路径。对无角标源图调用一次 `with_plugin_badge`；不要重复处理输出图。

## 重新生成

仅更新独立 SVG 和 PNG 素材（编辑共享定义后执行）：

```sh
python3 scripts/artwork/plugin_icon_badge.py
```

在仓库根目录执行（依赖 Python 3 和 Pillow）：

```sh
python3 scripts/artwork/generate_plugin_icons.py
```

选区笔刷也支持单独重新生成：

```sh
python3 plugins/selection-brush/artwork/generate_icons.py
```

笔刷主体由代码绘制；其他主体的无角标源图保存在各插件 `artwork/` 下。共享的 `plugin_icon_badge.py` 在输出时添加角标，不修改源图，也不在已加角标的资源上重复叠加。新增类似第一方功能入口时，将无角标图放入插件的 `artwork/`，并在 `generate_plugin_icons.py` 的 `ASSETS` 中登记。

生成过程保留资源路径与尺寸，无需更改图标加载逻辑。最终在宿主中的缩放、主题及 DPI 显示仍需在 Cubism 中确认。
