---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.recent-project-board
version: 0.68.1
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: recent-files, preview, viewport, navigation
turboismApi: "[0.68.1,1.0.0)"
requiresCubism: true
interface: swing
---

# 最近のプロジェクトボード

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

プロジェクトが開かれていないときに、Cubism の空のモデリングビューポートに最近使用したプロジェクトのサムネイルを表示します。ダブルクリックでプロジェクトを開きます。

| 項目 | 値 |
|---|---|
| バージョン | `0.68.1` |
| プラグイン ID | `dev.turboism.plugin.recent-project-board` |
| カテゴリー | `workflow` |
| タグ | recent-files, preview, viewport, navigation |
| Cubism が必要 | はい |
| インターフェース | `swing` |
| ライセンス | プロジェクトライセンス |

## 機能概要

- ドキュメントが開かれていない間、ネイティブ OpenGL ビューポート上に最大 8 件の最近のプロジェクトを表示します。
- ヘッダーの Lucide アイコンボタンで 6 列グリッド、3 列グリッド、詳細リストを切り替え、選択を記憶します。
- 公式 `recent-preview` プラグインの公開 `thumbnail.captured` イベントからサムネイルを受信します。
- ドキュメントを開くとボードは自動的に非表示になり、すべてのドキュメントを閉じると再表示されます。
- カードをダブルクリックしてプロジェクトを開きます。
- 最近のファイルがない場合は、中央に「新規プロジェクトを作成」および「新規アニメーションプロジェクトを作成」リンクを表示します。

## 要件と互换性

- **SDK API 範囲:** `[0.68.1,1.0.0)`。
- **Cubism:** Cubism が必要です（5.0.00–5.3.04）。
- **インターフェースモード:** `swing`。
- **プラグイン依存関係:** `dev.turboism.plugin.recent-preview`（必須）。

## インストールと有効化

本公式プラグインは Turboism に同梱されており、**プラグインマーケット** からも入手できます。**プラグイン管理** で有効化、無効化、アンインストールが可能です。

## ライセンス

プロジェクトライセンス。アイコンは Lucide v0.468.0（ISC ライセンス）を使用しています。

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全審査済みバージョン（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04、20 個の正確なバージョン）.
全機能（審査済みバージョン）：5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->
