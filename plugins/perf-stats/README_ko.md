---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.perf-stats
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: performance
tags: metrics, diagnostics, fps
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---
# ì±ë¥ íµê³

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

Cubism の CPU、FPS、JVM メモリー、ガベージコレクション統計をリアルタイムで表示します。

| 詳細 | 値 |
|---|---|
| 버전 | `0.1.0` |
| 플러그인 ID | `dev.turboism.plugin.perf-stats` |
| カテゴリー | `performance` |
| 태그 | metrics, diagnostics, fps |
| Cubism 필요 여부 | 예 |
| 인터페이스 | `swing` |
| 라이선스 | 프로젝트 라이선스 |

## 기능 개요

- 埋め込みの Performance パネルと、スタンドアロンの Performance Monitor ウィンドウを追加します。
- 共有ランタイム統計ソースを 1 秒ごとに 1 回サンプリングし、上限 120 ポイントのチャート履歴を保持します。
- Cubism のステータスエリアに、コンパクトな CPU パーセンテージを表示します。

## 요구 사항 및 호환성

- **SDK API 範囲:** `[0.67.0,1.0.0)`。
- **Cubism:** Cubism が必要です。Turboism は現在、レビュー済みの正確な Editor アーティファクト `5.0.00` から `5.3.04` を受け入れます。本プラグインは、宣言されたサービスと機能が利用できる場合にのみ、各ホスト向け機能を公開します。
- **インターフェースモード:** `swing`。
- **プラグイン依存関係:** 宣言されていません。

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

この公式プラグインは、まだ公開ストアに掲載されていない**ストア候補**です。マーケットプレイスで公開されるまでの間は、Turboism の公式リリースパッケージを通じてインストールし、**Plugin Management** で有効化してください。このワークフローが不要な場合は、同じウィンドウから無効化またはアンインストールできます。

## 사용 방법

1. 常時利用できるコンパクトなチャートのために、埋め込み Performance パネルを開きます。
2. **Turboism → Performance Monitor** を使用して、スタンドアロンウィンドウを開きます。
3. ビューポート FPS、CPU、ヒープ/非ヒープメモリー、ガベージコレクション停止の傾向を観察します。

## 기능

| 宣言された機能 | ユーザーへの効果 |
|---|---|
| `performance.stats.read` | ローカルの Cubism プロセスと JVM のパフォーマンスサンプルを読み取ります。 |
| `ui.embedded-panel.contribute` | Cubism UI にライブチャートパネルを追加します。 |

## 권한

| 権限 | スコープ | 要求する理由 |
|---|---|---|
| `turboism.performance.stats.read` | `application` | ライブチャート用に Cubism プロセスの CPU、FPS、JVM メモリー統計を読み取ります。 |
| `turboism.ui.status.notify` | `application` | Cubism ステータスバーに常駐するコンパクトな CPU パーセンテージラベルを表示します。 |
| `turboism.ui.panel.contribute` | `application` | Cubism パレットエリアに、埋め込みパフォーマンスチャートパネルを追加します。 |
| `turboism.action.register` | `application` | Performance Monitor ウィンドウアクションを登録します。 |
| `turboism.ui.menu.contribute` | `application` | Turboism のトップレベルメニューに Performance Monitor 項目を追加します。 |

## 개인정보 및 데이터

### 네트워크

ネットワーク接続を行いません。

### 로컬 데이터

サンプルを永続化しません。メトリクスとチャート履歴はメモリー内に保持され、プラグインのライフサイクルとともにクリアされます。

### 원격 측정ー

このプラグインからテレメトリーが送信されることはありません。

プラグイン ID を付して、プラグインのライフサイクルおよび障害の記録が Turboism のセッションログと Cubism のホストログに表示される場合があります。

## 상태 및 제한 사항

- **ステータス:** プレビュー。
- ランタイムパフォーマンス統計サービス、および対応するパネル/ステータス UI 統合が必要です。
- チャートは診断用サンプルであり、ベンチマークの保証ではありません。未対応のメトリクスは利用不可として表示される場合があります。

## 문제 해결

| 증상 | 확인 사항 |
|---|---|
| パネルまたはメニューがない | プラグインが有効で、パネル/メニューの追加サービスが利用可能であることを確認します。 |
| チャートにデータが表示されない | 1 秒間隔のサンプラーを待ち、ログでパフォーマンスプローブの可用性を確認します。 |
| CPU ラベルが消える | ステータス通知の可用性、およびプラグインが無効化または再読み込みされていないかを確認します。 |

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

- **프로젝트 웹사이트:** [https://turboism.dev](https://turboism.dev)
- **게시자:** Turboism Contributors
- **라이선스:** 프로젝트 라이선스
- **플러그인 ID:** `dev.turboism.plugin.perf-stats`
