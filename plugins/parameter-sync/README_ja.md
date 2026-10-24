---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.parameter-sync
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: parameter, sync, link, workflow
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# パラメータ同期 (Parameter Sync)

> **公式 Turboism プラグイン** · **ステータス: プレビュー**

正規化範囲マッピングに基づくパラメータ値のリアルタイム同期・連動機能を提供します。単一の統合ダイアログで、上部のグローバルスイッチと各パラメータのチェックボックスで設定できます。

| 項目 | 値 |
|---|---|
| バージョン | `0.1.0` |
| プラグイン ID | `dev.turboism.plugin.parameter-sync` |
| カテゴリ | `modeling` |
| タグ | parameter, sync, link, workflow |
| Cubism 必須 | はい |
| インターフェース | `swing` |
| ライセンス | プロジェクトライセンス |

## 主な機能

- スライダーのドラッグや値入力時に、同期設定されたすべてのパラメータへリアルタイムで値を同期。
- 統合された単一ウィンドウ：上部に全体有効化スイッチと同期数、中央に各パラメータのチェックボックス、下部にテストプレビュースライダー。
- Turboism イベントシステムとプログラム書き込み抑制により、振動や遅延のないスムーズな同期を実現。
- モデル GUID によるセッション間設定の自動永続化。
