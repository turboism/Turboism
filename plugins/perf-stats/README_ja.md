---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.perf-stats
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: performance
tags: metrics, diagnostics, fps
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# パフォーマンス統計

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

Cubism の CPU、FPS、JVM メモリー、ガベージコレクション統計をリアルタイムで表示します。

| 詳細 | 値 |
|---|---|
| バージョン | `0.1.0` |
| プラグイン ID | `dev.turboism.plugin.perf-stats` |
| カテゴリー | `performance` |
| タグ | metrics, diagnostics, fps |
| Turboism API | `[0.1.0,0.2.0)` |
| Cubism が必要 | はい |
| インターフェース | `swing` |
| ライセンス | プロジェクトライセンス |

## 機能概要

- 埋め込みの Performance パネルと、スタンドアロンの Performance Monitor ウィンドウを追加します。
- 共有ランタイム統計ソースを 1 秒ごとに 1 回サンプリングし、上限 120 ポイントのチャート履歴を保持します。
- Cubism のステータスエリアに、コンパクトな CPU パーセンテージを表示します。

## 要件と互換性

- **Turboism API:** `[0.1.0,0.2.0)`。
- **Cubism:** Cubism が必要です。Turboism は現在、レビュー済みの正確な Editor アーティファクト `5.2.03` と `5.3.02` を受け入れます。本プラグインは、宣言されたサービスと機能が利用できる場合にのみ、各ホスト向け機能を公開します。
- **インターフェースモード:** `swing`。
- **プラグイン依存関係:** 宣言されていません。

## インストールと有効化

この公式プラグインは、まだ公開ストアに掲載されていない**ストア候補**です。マーケットプレイスで公開されるまでの間は、Turboism の公式リリースパッケージを通じてインストールし、**Plugin Management** で有効化してください。このワークフローが不要な場合は、同じウィンドウから無効化またはアンインストールできます。

## 使い方

1. 常時利用できるコンパクトなチャートのために、埋め込み Performance パネルを開きます。
2. **Turboism → Performance Monitor** を使用して、スタンドアロンウィンドウを開きます。
3. ビューポート FPS、CPU、ヒープ/非ヒープメモリー、ガベージコレクション停止の傾向を観察します。

## 機能

| 宣言された機能 | ユーザーへの効果 |
|---|---|
| `performance.stats.read` | ローカルの Cubism プロセスと JVM のパフォーマンスサンプルを読み取ります。 |
| `ui.embedded-panel.contribute` | Cubism UI にライブチャートパネルを追加します。 |

## 権限

| 権限 | スコープ | 要求する理由 |
|---|---|---|
| `turboism.performance.stats.read` | `application` | ライブチャート用に Cubism プロセスの CPU、FPS、JVM メモリー統計を読み取ります。 |
| `turboism.ui.status.notify` | `application` | Cubism ステータスバーに常駐するコンパクトな CPU パーセンテージラベルを表示します。 |
| `turboism.ui.panel.contribute` | `application` | Cubism パレットエリアに、埋め込みパフォーマンスチャートパネルを追加します。 |
| `turboism.action.register` | `application` | Performance Monitor ウィンドウアクションを登録します。 |
| `turboism.ui.menu.contribute` | `application` | Turboism のトップレベルメニューに Performance Monitor 項目を追加します。 |

## プライバシーとデータ

### ネットワーク

ネットワーク接続を行いません。

### ローカルデータ

サンプルを永続化しません。メトリクスとチャート履歴はメモリー内に保持され、プラグインのライフサイクルとともにクリアされます。

### テレメトリー

このプラグインからテレメトリーが送信されることはありません。

プラグイン ID を付して、プラグインのライフサイクルおよび障害の記録が Turboism のセッションログと Cubism のホストログに表示される場合があります。

## 状態と制限

- **ステータス:** プレビュー。
- ランタイムパフォーマンス統計サービス、および対応するパネル/ステータス UI 統合が必要です。
- チャートは診断用サンプルであり、ベンチマークの保証ではありません。未対応のメトリクスは利用不可として表示される場合があります。

## トラブルシューティング

| 症状 | 確認事項 |
|---|---|
| パネルまたはメニューがない | プラグインが有効で、パネル/メニューの追加サービスが利用可能であることを確認します。 |
| チャートにデータが表示されない | 1 秒間隔のサンプラーを待ち、ログでパフォーマンスプローブの可用性を確認します。 |
| CPU ラベルが消える | ステータス通知の可用性、およびプラグインが無効化または再読み込みされていないかを確認します。 |

## サポートとライセンス

- **プロジェクト Web サイト:** [https://turboism.dev](https://turboism.dev)
- **発行者:** Turboism Contributors
- **ライセンス:** プロジェクトライセンス
- **プラグイン ID:** `dev.turboism.plugin.perf-stats`
