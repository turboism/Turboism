---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.frame-animation-import
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: animation, frame-animation, sprite-sheet, parameter
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# コマアニメーションインポート (Frame Animation Import)

> **Turboism 公式プラグイン** · **ステータス: プレビュー (Preview)**

スプライトシートまたは連番画像からコマアニメーションをインポートし、自動的にアートメッシュを生成し、専用パラメータのキーフォーム表示（不透明度）を設定します。

| 項目 | 内容 |
|---|---|
| バージョン | `0.1.0` |
| プラグイン ID | `dev.turboism.plugin.frame-animation-import` |
| カテゴリ | `modeling` (モデリング) |
| タグ | animation, frame-animation, sprite-sheet, parameter |
| Turboism API | `[0.1.0,0.2.0)` |
| ホスト要件 | Live2D Cubism Editor |
| UI 技術 | `swing` |
| ライセンス | Project License |

## 機能概要

- **スプライトシート切り出し:** グリッド分割線とコマ番号のプレビューを見ながら、行・列・有効コマ数でスプライトシートを切り出します。
- **連番画像のインポート:** 複数のPNG画像を選択し、自然順（例: frame_1 .. frame_10）で正しくソートして取り込みます。
- **自動メッシュ生成と階層整理:** 専用パーツを自動作成し、矩形アートメッシュを生成してテクスチャを割り当てます。
- **パラメータ生成とキーフォーム設定:** 専用パラメータを作成し、コマ送りまたはクロスフェードの不透明度を自動で設定します。
- **アトミックトランザクション:** インポート処理全体が1つのトランザクション内で実行され、`Ctrl+Z` 1回で完全に元に戻せます。

## 要件と互換性

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** 検証済みの全 Editor バージョン `5.0.00` ～ `5.3.04` をサポート。
- **UI モード:** `swing`.
- **依存関係:** なし。

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全レビュー済みバージョン.
- パーツ不透明度書き込み のみ 5.3.00–5.3.04（[Cubism 5.3.00 でPart offscreen drawing (part opacity and clipping masks)を追加](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history/)）
- トップメニュー統合 対象外 5.0.00–5.0.06（それ以前の Cubism バージョンには機能自体がない）
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->

## インストールと有効化

本公式プラグインは**ストア候補 (Store Candidate)** です。マーケットプレイスでの正式公開までは、Turboism の公式リリースパッケージからインストールし、**プラグイン管理** で有効化してください。ワークフローが不要になった場合は、同画面から無効化またはアンインストールできます。

## 使い方

1. コマンドパレット（`Ctrl+P`）またはパーツ／デフォーマパレットの右クリックメニューから **コマアニメーションをインポート...** を開きます。
2. スプライトシートまたは連番画像を選択します。
3. パラメータ名、ID、切り替え方式を設定します。
4. **インポート開始** をクリックするとモデルに自動反映されます。

## 機能

| 宣言された機能 | 効果 |
|---|---|
| `cubism.model.read` | モデル構造、パーツ、既存パラメータの読み取り。 |
| `cubism.model.write` | パーツ、メッシュ、テクスチャ、パラメータバインドの作成。 |
| `cubism.parameter.write` | アニメーション用パラメータの作成と不透明度の書き込み。 |
| `ui.context-menu.contribute` | パーツおよびデフォーマパレットへの右クリックメニュー登録。 |
| `ui.menu.contribute` | Turboism のトップレベルメニューへのインポート機能登録。 |
| `ui.status.notify` | インポート結果および通知の表示。 |

## 権限

| 権限 | スコープ | 要求理由 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | モデルのパーツ、描画オブジェクト、パラメータ定義の読み取り。 |
| `turboism.cubism.model.write` | `application` | パーツやメッシュの作成、パラメータのバインドと不透明度設定。 |
| `turboism.file.read` | `application` | 選択されたコマ画像およびスプライトシートファイルの読み取り。 |
| `turboism.action.register` | `application` | インポート用アクションの登録。 |
| `turboism.ui.context-menu.contribute` | `application` | コンテキストメニュー項目の登録。 |
| `turboism.ui.menu.contribute` | `application` | Turboism のトップレベルメニューへのインポート機能登録。 |
| `turboism.ui.status.notify` | `application` | 処理結果と通知の表示。 |

## プライバシーとデータ

### ネットワーク

ネットワーク接続は一切行いません。

### ローカルデータ

ユーザーが選択した画像ファイルのみを読み込み、個人データを外部に永続化しません。

### テレメトリ

テレメトリ送信は行いません。ログはローカルにのみ保存されます。

## 状態と制限

- **ステータス:** プレビュー (Preview)。
- Cubism のモデルドキュメントが開かれている必要があります。
- 単一フレームが 4MB RGBA 予算を超える場合は自動的に高品質リサイズされます。
- ヘッドレス環境ではダイアログは表示されません。

## トラブルシューティング

| 症状 | 確認事項 |
|---|---|
| メニュー項目が表示されない | モデルドキュメントが開かれているか確認してください。 |
| 画像の読み込みに失敗する | ファイルが有効な画像フォーマットか確認してください。 |
| インポートが失敗する | ステータス通知と実行ログを確認してください。 |

## サポートとライセンス

- **公式サイト:** [https://turboism.dev](https://turboism.dev)
- **開発者:** Turboism Contributors
- **ライセンス:** Project License
- **プラグイン ID:** `dev.turboism.plugin.frame-animation-import`
