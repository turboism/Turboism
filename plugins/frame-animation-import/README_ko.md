---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.frame-animation-import
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: animation, frame-animation, sprite-sheet, parameter
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---
# íë ì ì ëë©ì´ì ê°ì ¸ì¤ê¸°

> **Turboism 公式プラグイン** · **ステータス: プレビュー (Preview)**

スプライトシートまたは連番画像からコマアニメーションをインポートし、自動的にアートメッシュを生成し、専用パラメータのキーフォーム表示（不透明度）を設定します。

| 項目 | 内容 |
|---|---|
| 버전 | `0.1.0` |
| 플러그인 ID | `dev.turboism.plugin.frame-animation-import` |
| 카테고리 | `modeling` (モデリング) |
| 태그 | animation, frame-animation, sprite-sheet, parameter |
| ホスト要件 | Live2D Cubism Editor |
| UI 技術 | `swing` |
| 라이선스 | 프로젝트 라이선스 |

## 기능 개요

- **スプライトシート切り出し:** グリッド分割線とコマ番号のプレビューを見ながら、行・列・有効コマ数でスプライトシートを切り出します。
- **連番画像のインポート:** 複数のPNG画像を選択し、自然順（例: frame_1 .. frame_10）で正しくソートして取り込みます。
- **自動メッシュ生成と階層整理:** 専用パーツを自動作成し、矩形アートメッシュを生成してテクスチャを割り当てます。
- **パラメータ生成とキーフォーム設定:** 専用パラメータを作成し、コマ送りまたはクロスフェードの不透明度を自動で設定します。
- **アトミックトランザクション:** インポート処理全体が1つのトランザクション内で実行され、`Ctrl+Z` 1回で完全に元に戻せます。

## 요구 사항 및 호환성

- **SDK API 範囲:** `[0.67.0,1.0.0)`.
- **Cubism:** 検証済みの全 Editor バージョン `5.0.00` ～ `5.3.04` をサポート。
- **UI モード:** `swing`.
- **依存関係:** なし。

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.3.00–5.3.04.
- 파트 불투명도 쓰기 에서만 5.3.00–5.3.04（[Cubism 5.3.00에 Part offscreen drawing (part opacity and clipping masks) 추가](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/)）
- 상단 메뉴 통합 제외 5.0.00–5.0.06（이전 Cubism 버전에는 기능 자체가 없음）
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

本公式プラグインは**ストア候補 (Store Candidate)** です。マーケットプレイスでの正式公開までは、Turboism の公式リリースパッケージからインストールし、**プラグイン管理** で有効化してください。ワークフローが不要になった場合は、同画面から無効化またはアンインストールできます。

## 사용 방법

1. 上部の **Turboism → プラグイン** メニューまたはパーツタブの右クリックメニューから **コマアニメーションをインポート...** を開きます。
2. スプライトシートまたは連番画像を選択します。
3. パラメータ名、ID、切り替え方式を設定します。
4. **インポート開始** をクリックするとモデルに自動反映されます。

## 기능

| 宣言された機能 | 効果 |
|---|---|
| `cubism.model.read` | モデル構造、パーツ、既存パラメータの読み取り。 |
| `cubism.model.write` | パーツ、メッシュ、テクスチャ、パラメータバインドの作成。 |
| `cubism.parameter.write` | アニメーション用パラメータの作成と不透明度の書き込み。 |
| `ui.context-menu.contribute` | パーツおよびデフォーマパレットへの右クリックメニュー登録。 |
| `ui.menu.contribute` | Turboism のトップレベルメニューへのインポート機能登録。 |
| `ui.status.notify` | インポート結果および通知の表示。 |

## 권한

| 권한 | 범위 | 요청 이유 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | モデルのパーツ、描画オブジェクト、パラメータ定義の読み取り。 |
| `turboism.cubism.model.write` | `application` | パーツやメッシュの作成、パラメータのバインドと不透明度設定。 |
| `turboism.file.read` | `application` | 選択されたコマ画像およびスプライトシートファイルの読み取り。 |
| `turboism.action.register` | `application` | インポート用アクションの登録。 |
| `turboism.ui.context-menu.contribute` | `application` | コンテキストメニュー項目の登録。 |
| `turboism.ui.menu.contribute` | `application` | Turboism のトップレベルメニューへのインポート機能登録。 |
| `turboism.ui.status.notify` | `application` | 処理結果と通知の表示。 |

## 개인정보 및 데이터

### 네트워크

ネットワーク接続は一切行いません。

### 로컬 데이터

ユーザーが選択した画像ファイルのみを読み込み、個人データを外部に永続化しません。

### 원격 측정

テレメトリ送信は行いません。ログはローカルにのみ保存されます。

## 상태 및 제한 사항

- **ステータス:** プレビュー (Preview)。
- Cubism のモデルドキュメントが開かれている必要があります。
- 単一フレームが 4MB RGBA 予算を超える場合は自動的に高品質リサイズされます。
- ヘッドレス環境ではダイアログは表示されません。

## 문제 해결

| 증상 | 확인 사항 |
|---|---|
| メニュー項目が表示されない | モデルドキュメントが開かれているか確認してください。 |
| 画像の読み込みに失敗する | ファイルが有効な画像フォーマットか確認してください。 |
| インポートが失敗する | ステータス通知と実行ログを確認してください。 |

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

- **公式サイト:** [https://turboism.dev](https://turboism.dev)
- **開発者:** Turboism Contributors
- **라이선스:** 프로젝트 라이선스
- **플러그인 ID:** `dev.turboism.plugin.frame-animation-import`
