---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-folders
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.67.1,1.0.0)"
requiresCubism: true
interface: swing
---

# フォルダー別アトラス配置

Turboism SDK のみを使用する独立プラグインです。具体的な配置アルゴリズムは内蔵せず、
登録されたアルゴリズムを自動検出します。

Turboism メニューからフォルダー別配置を開き、最大サイズ、アルゴリズム、余白、
画像間隔、自動サイズを選択します。パーツのトップフォルダーごとにまとめ、
収まらない場合はサブフォルダーごとに分割します。元の画像サイズとツリー順を維持します。

MaxRects-BSSF や Dalsoo など、アトラス全体の配置に対応するアルゴリズムを別途有効にしてください。
現在ページ専用のアルゴリズムは、この操作では選択できません。

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全審査済みバージョン（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04、20 個の正確なバージョン）.
全機能（審査済みバージョン）：5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
- トップメニュー統合 対象外 5.0.00–5.0.06（それ以前の Cubism バージョンには機能自体がない）
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->
