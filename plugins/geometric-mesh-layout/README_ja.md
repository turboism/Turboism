---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.geometric-mesh-layout
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# 幾何メッシュ配置

## 機能概要

メッシュ編集ツールバーに「幾何メッシュ配置」ツールを追加します。有効化するとキャンバス上に中心アンカーを設定でき、ダイアログで円形または正多角形、半径、辺数を選択して頂点とエッジを生成できます。

## 要件と互換性

Turboism API: `[0.67.0,1.0.0)`. Cubism Editor: 5.0.00–5.3.04.

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全審査済みバージョン（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04、20 個の正確なバージョン）.
全機能（審査済みバージョン）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->

## インストールと有効化

Turboism 公式配布パッケージに同梱されています。プラグイン管理で有効・無効を切り替えます。

## 使い方

1. アートメッシュの手動メッシュ編集モードに入ります。
2. メッシュ編集ツールバーの**幾何メッシュ配置**をクリックします。
3. キャンバス上でクリックまたはドラッグして中心アンカーを設定します。
4. ダイアログで**形状プリセット**（円周、三角形、四角形、六角形、八角形）、**半径**、**辺数**、および**中心点を作成**・**外周点から中心への線を生成**・**同心円を生成**（周数スライダー）を設定し、**確定**をクリックします。

## 機能

| Capability | Effect |
|---|---|
| `cubism.mesh.custom-tools` | メッシュ編集ツールバーにカスタムツールを登録します。 |

## 権限

| Permission | Scope | Effect |
|---|---|---|
| `turboism.ui.toolbar.mesh.contribute` | `application` | メッシュツールバーにボタンを追加します。 |
| `turboism.cubism.model.read` | `application` | 頂点座標とカメラ投影を読み取ります。 |
| `turboism.cubism.model.write` | `application` | 頂点とエッジを生成します。 |

## プライバシーとデータ

ネットワーク通信やテレメトリーはありません。生成された頂点とエッジはネイティブの Undo/Redo に統合されます。

## 状態と制限

メッシュ編集モード内でのみ動作します。

## トラブルシューティング

ボタンが表示されない場合は、単一のアートメッシュでメッシュ編集モードに入っていることを確認してください。

## 変更履歴

### 0.1.0

- 初回リリース。

## サポートとライセンス

[Issues](https://github.com/turboism/Turboism/issues) · [Project License](../../LICENSE)
