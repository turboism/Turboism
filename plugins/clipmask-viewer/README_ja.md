---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.clipmask-viewer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: analysis
tags: clip-mask, viewer, graph
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# クリップマスクビューア

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

モデルを変更せずに、クリップマスクの関係、重複、順序の競合、関連する ArtMesh を検査します。

| 詳細 | 値 |
|---|---|
| バージョン | `0.1.0` |
| プラグイン ID | `dev.turboism.plugin.clipmask-viewer` |
| カテゴリ | `analysis` |
| タグ | clip-mask, viewer, graph |
| Turboism API | `[0.1.0,0.2.0)` |
| Cubism が必要 | はい |
| インターフェース | `swing` |
| ライセンス | プロジェクトライセンス |

## 機能概要

- Turboism パネルにセクションを追加し、ビューアを開く Turboism メニューアクションを追加します。
- フィルタリング、ズーム、更新に対応したグラフ、マスク主テーブル、ユーザー主テーブルのビューを提供します。
- エディターでの選択内容を強調表示し、選択した GUID をシステムクリップボードにコピーできます。

## 要件と互換性

- **Turboism API:** `[0.1.0,0.2.0)`。
- **Cubism:** Cubism が必要です。Turboism は現在、レビュー済みの厳密な Editor 成果物 `5.2.03` および `5.3.02` を受け入れます。このプラグインは、宣言されたサービスと機能が利用可能な場合にのみ、各ホスト向け機能を公開します。
- **インターフェースモード:** `swing`。
- **プラグインの依存関係:** 宣言されていません。

## インストールと有効化

この公式プラグインは**ストア候補**であり、まだストアリスティングとして公開されていません。マーケットプレイス公開までは Turboism の公式リリースパッケージからインストールし、**プラグイン管理**で有効にしてください。ワークフローが不要な場合は、同じウィンドウから無効化またはアンインストールできます。

## 使い方

1. Turboism パネルのセクションまたは Turboism メニューからクリップマスクビューアを開きます。
2. グラフまたはテーブルビューを選択し、フィルタリングと無関係ノード切り替えを使用して結果を絞り込みます。
3. 行またはノードを選択して関係を検査します。GUID が必要な場合はコピーアクションを使用します。

## 機能

| 宣言された機能 | ユーザーへの効果 |
|---|---|
| `cubism.clipmask.read` | クリップマスクおよび ArtMesh 関係のスナップショットを読み取ります。 |
| `ui.embedded-panel.contribute` | Turboism パネルにランチャーセクションを追加します。 |
| `ui.menu.contribute` | Turboism メニューにビューアコマンドを追加します。 |
| `ui.status.notify` | ホストのステータス通知を通じて、クリップボードおよびビューアの結果を報告します。 |

## 権限

| 権限 | スコープ | 要求する理由 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 重複チェッカーとビューアのため、クリップマスクおよび ArtMesh のスナップショットとエディター選択内容を読み取ります。 |
| `turboism.ui.panel.contribute` | `application` | クリップマスクビューアの折りたたみ可能なセクションを Turboism パネルに挿入します。 |
| `turboism.action.register` | `application` | Turboism タブボタンとメニュー項目の背後にある clipmask-viewer.open.viewer アクションを登録します。 |
| `turboism.ui.menu.contribute` | `application` | Turboism メニューを通じてクリップマスク重複チェッカーを公開します。 |
| `turboism.ui.status.notify` | `application` | GUID コピーの結果を通知します。 |
| `turboism.event.subscribe` | `application` | 生成された選択観測サブスクライバーを登録します。 |
| `turboism.cubism.selection.observe` | `application` | 開いているビューアを、プル検出された選択変更と同期させます。 |

## プライバシーとデータ

### ネットワーク

ネットワーク接続は行いません。

### ローカルデータ

モデルデータやプラグイン設定を永続化しません。ユーザーが要求したコピー操作では、選択された GUID テキストがシステムクリップボードに書き込まれます。

### テレメトリー

このプラグインからテレメトリが送信されることはありません。

プラグイン ID が付与されたプラグインのライフサイクル記録および障害記録が、Turboism のセッションログと Cubism のホストログに表示される場合があります。

## 状態と制限

- **ステータス:** プレビュー。
- 読み取り専用です。クリップマスクの割り当てやモデルオブジェクトを変更しません。
- レビュー済みのクリップマスクおよびエディター選択読み取りサービスが必要です。Swing ビューアはヘッドレス JVM では利用できません。

## トラブルシューティング

| 症状 | 確認事項 |
|---|---|
| ビューアアクションがない | プラグインが有効であり、パネル/メニュー貢献サービスが利用可能であることを確認してください。 |
| ビューアが空である | ArtMesh とクリップマスク関係を持つモデルを開き、更新してください。 |
| ウィンドウが開かない | ヘッドレス環境または利用できないクリップマスク読み取り機能がログにないか確認してください。 |

## サポートとライセンス

- **プロジェクト Web サイト:** [https://turboism.dev](https://turboism.dev)
- **公開者:** Turboism Contributors
- **ライセンス:** プロジェクトライセンス
- **プラグイン ID:** `dev.turboism.plugin.clipmask-viewer`
