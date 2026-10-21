#!/usr/bin/env python3
"""Render exported actual placements; never packs or moves the polygons."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import Polygon, Patch

ROOT = Path(__file__).resolve().parent / 'heavy-kernel'
FONT = font_manager.FontProperties(fname='/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc')
COLORS = ['#5c91aa', '#208c87', '#ceaa57']


def panel(ax, name, title):
    layout = json.loads((ROOT / (name + '-layout.json')).read_text())
    timing = json.loads((ROOT / (name + '-preview-run.json')).read_text())
    for item in layout['placed']:
        ax.add_patch(Polygon(item['points'], closed=True,
                             facecolor=COLORS[item['id'] % 3], edgecolor='#24434a', linewidth=.45, alpha=.86))
    ax.set_xlim(0, layout['page'])
    ax.set_ylim(layout['page'], 0)
    ax.set_aspect('equal')
    ax.set_facecolor('white')
    ax.set_xticks([0, 1024, 2048, 3072, 4096])
    ax.set_yticks([0, 1024, 2048, 3072, 4096])
    ax.tick_params(labelsize=8, colors='#64717b', length=3)
    for spine in ax.spines.values():
        spine.set_color('#86949c')
    ax.set_title(title + '\n' +
                 f"放入 {len(layout['placed'])}/{layout['count']} · 未放入 {len(layout['overflow'])} · " +
                 f"轮廓占页 {layout['coverage']:.1%} · {timing['elapsedMs']/1000:.2f} 秒",
                 fontproperties=FONT, fontsize=12, color='#253b43', pad=15, linespacing=1.8)
    return layout


def footer(fig):
    fig.legend(handles=[Patch(facecolor=c, label=l) for c, l in zip(COLORS, ['32 顶点凹形', 'L 形', '细长矩形'])],
               loc='lower center', bbox_to_anchor=(.5, .047), ncol=3, prop=FONT, frameon=False)
    fig.text(.5, .025, '真实落位坐标直接绘制；固定倍率，未放入项未绘于页内。合成样例，不是 Cubism 窗口截图。',
             ha='center', fontproperties=FONT, fontsize=9, color='#68777e')


fig, axes = plt.subplots(1, 2, figsize=(15, 9), facecolor='#eef2f3')
fig.suptitle('离线排版结果 · 同一组 100 项复杂轮廓', fontproperties=FONT, fontsize=19, color='#253b43', y=.97)
panel(axes[0], 'mixed100-quarter', '90° 步进旋转 · QUARTER')
panel(axes[1], 'mixed100-free', '自由旋转 · FREE / Abey')
footer(fig)
fig.subplots_adjust(left=.05, right=.98, top=.83, bottom=.14, wspace=.12)
fig.savefig(ROOT / 'heavy-layout-comparison.png', dpi=140, facecolor=fig.get_facecolor())
plt.close(fig)

fig, ax = plt.subplots(figsize=(10, 11), facecolor='#eef2f3')
fig.suptitle('离线重负载排版 · 250 项复杂轮廓', fontproperties=FONT, fontsize=19, color='#253b43', y=.97)
panel(ax, 'mixed250-free', '自由旋转 · FREE / Abey · 4096 × 4096')
footer(fig)
fig.subplots_adjust(left=.08, right=.95, top=.83, bottom=.12)
fig.savefig(ROOT / 'heavy-layout250-free.png', dpi=140, facecolor=fig.get_facecolor())
plt.close(fig)
