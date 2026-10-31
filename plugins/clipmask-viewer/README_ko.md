---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.clipmask-viewer
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: analysis
tags: clip-mask, viewer, graph
turboismApi: "[0.68.1,1.0.0)"
requiresCubism: true
interface: swing
---
# í´ë¦½ ë§ì¤í¬ ë·°ì´

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

モデルを変更せずに、クリップマスクの関係、重複、順序の競合、関連する ArtMesh を検査します。

| 詳細 | 値 |
|---|---|
| 버전 | `0.1.0` |
| 플러그인 ID | `dev.turboism.plugin.clipmask-viewer` |
| 카테고리 | `analysis` |
| 태그 | clip-mask, viewer, graph |
| Cubism 필요 여부 | 예 |
| 인터페이스 | `swing` |
| 라이선스 | 프로젝트 라이선스 |

## 기능 개요

- Turboism パネルにセクションを追加し、ビューアを開く Turboism メニューアクションを追加します。
- フィルタリング、ズーム、更新に対応したグラフ、マスク主テーブル、ユーザー主テーブルのビューを提供します。
- エディターでの選択内容を強調表示し、選択した GUID をシステムクリップボードにコピーできます。

## 요구 사항 및 호환성

- **SDK API 範囲:** `[0.68.1,1.0.0)`。
- **Cubism:** Cubism が必要です。Turboism は現在、レビュー済みの厳密な Editor 成果物 `5.0.00` から `5.3.04` を受け入れます。このプラグインは、宣言されたサービスと機能が利用可能な場合にのみ、各ホスト向け機能を公開します。
- **インターフェースモード:** `swing`。
- **プラグインの依存関係:** 宣言されていません。

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- 상단 메뉴 통합 제외 5.0.00–5.0.06（이전 Cubism 버전에는 기능 자체가 없음）
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

この公式プラグインは Turboism に同梱され、**プラグインマーケット**からも入手できます。インストール済みプラグインの有効化、無効化、アンインストールは**プラグイン管理**で行います。

## 사용 방법

1. Turboism パネルのセクションまたは Turboism メニューからクリップマスクビューアを開きます。
2. グラフまたはテーブルビューを選択し、フィルタリングと無関係ノード切り替えを使用して結果を絞り込みます。
3. 行またはノードを選択して関係を検査します。GUID が必要な場合はコピーアクションを使用します。

## 기능

| 宣言された機能 | ユーザーへの効果 |
|---|---|
| `cubism.clipmask.read` | クリップマスクおよび ArtMesh 関係のスナップショットを読み取ります。 |
| `ui.embedded-panel.contribute` | Turboism パネルにランチャーセクションを追加します。 |
| `ui.menu.contribute` | Turboism メニューにビューアコマンドを追加します。 |
| `ui.status.notify` | ホストのステータス通知を通じて、クリップボードおよびビューアの結果を報告します。 |

## 권한

| 権限 | スコープ | 要求する理由 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 重複チェッカーとビューアのため、クリップマスクおよび ArtMesh のスナップショットとエディター選択内容を読み取ります。 |
| `turboism.ui.panel.contribute` | `application` | クリップマスクビューアの折りたたみ可能なセクションを Turboism パネルに挿入します。 |
| `turboism.action.register` | `application` | Turboism タブボタンとメニュー項目の背後にある clipmask-viewer.open.viewer アクションを登録します。 |
| `turboism.ui.menu.contribute` | `application` | Turboism メニューを通じてクリップマスク重複チェッカーを公開します。 |
| `turboism.ui.status.notify` | `application` | GUID コピーの結果を通知します。 |
| `turboism.event.subscribe` | `application` | 生成された選択観測サブスクライバーを登録します。 |
| `turboism.cubism.selection.observe` | `application` | 開いているビューアを、プル検出された選択変更と同期させます。 |

## 개인정보 및 데이터

### 네트워크

네트워크 연결을 수행하지 않습니다.

### 로컬 데이터

モデルデータやプラグイン設定を永続化しません。ユーザーが要求したコピー操作では、選択された GUID テキストがシステムクリップボードに書き込まれます。

### 원격 측정ー

このプラグインからテレメトリが送信されることはありません。

プラグイン ID が付与されたプラグインのライフサイクル記録および障害記録が、Turboism のセッションログと Cubism のホストログに表示される場合があります。

## 상태 및 제한 사항

- **ステータス:** プレビュー。
- 読み取り専用です。クリップマスクの割り当てやモデルオブジェクトを変更しません。
- レビュー済みのクリップマスクおよびエディター選択読み取りサービスが必要です。Swing ビューアはヘッドレス JVM では利用できません。

## 문제 해결

| 증상 | 확인 사항 |
|---|---|
| ビューアアクションがない | プラグインが有効であり、パネル/メニュー貢献サービスが利用可能であることを確認してください。 |
| ビューアが空である | ArtMesh とクリップマスク関係を持つモデルを開き、更新してください。 |
| ウィンドウが開かない | ヘッドレス環境または利用できないクリップマスク読み取り機能がログにないか確認してください。 |

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

- **프로젝트 웹사이트:** [https://turboism.dev](https://turboism.dev)
- **公開者:** Turboism Contributors
- **라이선스:** 프로젝트 라이선스
- **플러그인 ID:** `dev.turboism.plugin.clipmask-viewer`
