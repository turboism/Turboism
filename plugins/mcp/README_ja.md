---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.mcp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: mcp, automation, external-tools
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---

# Turboism MCP サーバー

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

ローカルのループバックインターフェース上で、変更系操作を Bearer トークンで保護した MCP Streamable HTTP サーバーを実行します（stdio ブリッジがトークンを自動供給）。

| 詳細 | 値 |
|---|---|
| バージョン | `0.1.0` |
| プラグイン ID | `dev.turboism.plugin.mcp` |
| 公開カタログ | 80 ツール · 15 リソース · 2 テンプレート · 8 プロンプト |
| Cubism が必要 | はい |
| インターフェース | `swing` |

## 機能概要

- 型付きのドメインレベルのモデルオブジェクト、パラメーター、バインディング、履歴、Editor コマンドツールを公開します。
- アクティブなドキュメント、モデル、ワークスペース、Cubism Core、およびサニタイズ済みランタイム診断を JSON リソースとして公開します。
- 検査、診断、編集、復旧、および範囲を限定した Editor 自動化のためのワークフロープロンプトを提供します。
- ループバッククライアントのみを処理し、オリジン、本文サイズ、プロトコル、セッション、レートの制限を適用します。
- 認証情報を持たない stdio 起動記述子（実行中 JVM のランチャー、プラグインクラスパス、ブリッジメインクラス、状態ディレクトリ）をランタイム接続境界を通じて公開し、ACP プラグインなどの同梱コンシューマーがトークンを扱わずにエージェントをプリコンパイル済みブリッジへ接続できるようにします。

## 要件と互換性

- **SDK API 範囲:** `[0.67.0,1.0.0)`。
- **Cubism:** Turboism は現在、レビュー済みの正確な Editor アーティファクト `5.0.00` から `5.3.04` を受け入れます。ホスト向けリソースは、その公開 SDK 機能を利用できないとき、クローズドに失敗します。空の成功結果を捏造することはありません。
- **トランスポート:** プロトコル `2025-11-25` を使用する MCP Streamable HTTP。サポート対象の以前のプロトコルバージョンについては互換性ネゴシエーションを行います。
- **インターフェースモード:** `swing`。ローカル MCP 接続情報ウィンドウを提供します。
- **プラグイン依存関係:** 宣言されていません。

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全審査済みバージョン（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04、20 個の正確なバージョン）.
全機能（審査済みバージョン）：5.3.00–5.3.04。
- 外部編集セッション API（edit()） 対象外 5.0.00–5.1.01（[Cubism 5.1.00 でExternal application integration (WebSocket API)を追加](https://docs.live2d.com/zh-CHS/cubism-editor-manual/updates-history5-1/)）
- トップメニュー統合 対象外 5.0.00–5.0.06（それ以前の Cubism バージョンには機能自体がない）
- MCP Editor コマンド実行 対象外 5.0.00–5.0.06（それ以前の Cubism バージョンには機能自体がない）
- MCP Editor コマンド一覧 対象外 5.0.00–5.0.06（それ以前の Cubism バージョンには機能自体がない）
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->

## インストールと有効化

1. Turboism の公式リリースパッケージおよび **Plugin Management** を通じてプラグインをインストールし、有効化します。
2. **Turboism → MCP 接続** を開きます。このウィンドウには、現在のローカルアドレスと、範囲を限定したプロセス内の接続/リクエスト履歴が表示されます。
3. **stdio クライアント設定** の行をローカル MCP クライアントにコピーします（推奨）。または HTTP アドレスを直接 HTTP クライアントへコピーします。デフォルトは `http://127.0.0.1:43123/mcp` です。stdio クライアントはトークンを意識しません。直接 HTTP クライアントは、変更系ツール呼び出しとセッション終了に `Authorization: Bearer <トークン>`（プラグイン状態ディレクトリ内 `mcp.token` の内容）が必要です。
4. 接続を診断するときはウィンドウを開いたままにし、**更新**で最新履歴を読み込みます。
5. プログラムから利用するローカルコンシューマーは、引き続きユーザー単位のプラグイン状態ディレクトリにある所有者保護済み `mcp-connection.json` を読み取れます。
6. `initialize` を完了し、`MCP-Session-Id` を保持して `notifications/initialized` を送信し、以降のリクエストにはネゴシエート済みのプロトコルバージョンを含めます。

サーバーはループバックにのみバインドされ、接続ファイルは引き続き所有者権限で保護されます。表示履歴には MCP セッション ID は含まれず、プロセス終了時に破棄されます。

## 使い方

**Turboism → MCP 接続** に表示される設定でローカルクライアントを接続します。stdio ブリッジ経由なら追加の認証設定は不要です。

### 一般的なコーディングエージェント

**Claude Code**

```bash
claude mcp add --transport http turboism \
  http://127.0.0.1:43123/mcp
```

同等の Claude Code JSON 設定：

```json
{
  "mcpServers": {
    "turboism": {
      "type": "http",
      "url": "http://127.0.0.1:43123/mcp"
    }
  }
}
```

**Visual Studio Code**

```json
{
  "servers": {
    "turboism": {
      "type": "http",
      "url": "http://127.0.0.1:43123/mcp"
    }
  }
}
```

**Codex CLI**

次を追加します。

```toml
[mcp_servers.turboism]
url = "http://127.0.0.1:43123/mcp"
```

### 公開 MCP カタログ

#### ツール

| ツール | 目的 |
|---|---|
| `turboism.model_objects.apply` | 順序付けられた作成、名前変更、再親子付け、削除操作を適用します。 |
| `turboism.parameters.apply` | 型付きパラメーター値および定義操作を適用します。 |
| `turboism.parameters.structure.read` | フォルダーとパラメーターの混合順序・定義・Combined 状態を読み取ります。 |
| `turboism.parameters.structure.plan` | 逐次編集、新規フォルダー参照、削除範囲をプレビューします。 |
| `turboism.parameters.structure.apply` | セッション計画を照合し、独立した authoring トランザクションで適用します。 |
| `turboism.parameters.structure.result` | ホストを読まずに保存済み操作結果を返します。 |
| `turboism.authoring.meshes.triangulate` | native 分割の実際の点順/面を返し、UV対応を推測しません。 |
| `turboism.authoring.meshes.create_plan` | 明示的親/UV/任意画像と source/default を分離した新規メッシュを計画します。 |
| `turboism.authoring.meshes.create` | 独立トランザクションで一括作成/任意画像binding、宣言状態CASとreadbackを行います。 |
| `turboism.authoring.meshes.create_result` | ホストを読まず保存済み作成結果を返します。 |
| `turboism.documents.read` | 空セッションを含むproject文書IDをboundedに読み、pathは返しません。 |
| `turboism.documents.new_model` | NEW_MODELを一度だけ条件実行し、新active model/authoring IDを確認します。 |
| `turboism.documents.new_result` | host読み取りや再実行なしに新規作成結果を返します。 |
| `turboism.parameter_bindings.apply` | 型付きパラメーターバインディング操作とネイティブのアトミック転送を適用します。 |
| `turboism.glues.read` | 正確なバージョンでルーティングされた Glue の編集状態を一覧または取得します。 |
| `turboism.glues.write` | ネイティブ Undo 対応の Glue 変更を 1 件適用します。 |
| `turboism.textures.read` | 件数を制限したテクスチャライブラリのメタデータと現在の状態を読み取ります。 |
| `turboism.textures.write` | 状態を検証し、独立したネイティブ Undo に対応するテクスチャ操作を 1 件実行します。 |
| `turboism.assets.binding.read` | authoring snapshot と照合し ArtMesh の画像/atlas 入力と登録済み画像候補を観測します。 |
| `turboism.assets.binding.bind` | 完全 CAS と独立 SDK transaction で既存の登録済み画像を関連付け、正確に再読します。 |
| `turboism.assets.binding.result` | host を読まず、session に保持された過去の関連付け結果を返します。 |
| `turboism.assets.images.register` | 一回限りの READ grant から有界 PNG 登録と関連付けの非同期 job を開始します。 |
| `turboism.assets.images.result` | host を読まず、phase/内容 hash と過去の native transaction 結果を返します。 |
| `turboism.assets.images.cancel` | native transaction 准入前の取消を要求し、准入後は実際の結果を保持します。 |
| `turboism.atlas.read` | session に束縛した有界 atlas snapshot を読みます。 |
| `turboism.atlas.plan` | 書込みなしで決定的 shelf plan を生成します。 |
| `turboism.atlas.apply` | 独立 native Undo の完全矩形 apply job を開始します。 |
| `turboism.atlas.result` | host に触れず保持した結果を読みます。 |
| `turboism.atlas.cancel` | native admission 前の cancel を要求します。 |
| `turboism.export.moc.plan` | exportせず grant を消費せずに source/settings の session plan を発行します。 |
| `turboism.export.moc.execute` | 公式 MOC/Core 検証と PNG/static model3/manifest ZIP の承認済み atomic write。 |
| `turboism.export.moc.result` | host に触れず export/file result と hash を照合します。 |
| `turboism.export.moc.cancel` | file write admission 前の cancel を要求します。 |
| `turboism.history.read` | 不変のネイティブ Undo 履歴スナップショットを読み取ります。 |
| `turboism.history.undo` | 世代、リビジョン、および任意の項目 ID を使って安全に Undo します。 |
| `turboism.history.redo` | 世代、リビジョン、および任意の項目 ID を使って安全に Redo します。 |
| `turboism.transaction.execute` | 対象の読み取りと Undo 対応書き込みを 1 つの編集トランザクションにまとめます。 |
| `turboism.authoring.meshes.read` | ArtMesh のソース形状、GUID 軸・セル・形状と Morph ID をページ単位で読み取ります。 |
| `turboism.authoring.meshes.evaluated.read` | ネイティブの最終計算頂点と GUID パラメータ消費状態を観測し、鮮度は保証しません。 |
| `turboism.authoring.warps.read` | Warp の保存制御点と完全な GUID 軸・セル・形状を読み取ります。 |
| `turboism.authoring.rotations.read` | Rotation の基準角と保存形状、完全な GUID 軸・セルを読み取ります。 |
| `turboism.authoring.keyforms.preview` | 実際の typed write 要求を CAS 検証し、差分と予測ハッシュを返します。 |
| `turboism.authoring.keyforms.write` | 完全な snapshot CAS で通常 ArtMesh 頂点/描画順/不透明度、Warp 制御点、Rotation form を書き込みます。 |
| `turboism.authoring.keyforms.result` | モデルにアクセスせず、セッション内の過去の操作結果を照会します。 |
| `turboism.render.state` | ドキュメント、履歴、現在のパラメータと描画上限を読み取ります。 |
| `turboism.render.capture` | 期待状態に結び付けたネイティブ描画を MCP PNG 画像として返します。 |
| `turboism.render.pose_grid` | 最大九姿勢を描画し、元のパラメータ値への復元を検証します。 |
| `turboism.render.pose_grid.status` | UI キューを待たず進捗と終端状態を読み取ります。 |
| `turboism.render.pose_grid.cancel` | 安全な境界での中止を要求します。ネイティブ呼出しと復元は中断しません。 |
| `turboism.render.pose_grid.result` | 姿勢ごとの画像、復元結果とトランザクション証跡を読み取ります。 |
| `turboism.render.pose_grid.release` | 消費済み画像を解放し、メタデータと再実行防止の識別子を保持します。 |
| `turboism.validation.geometry` | ArtMesh の範囲を指定した読み取り専用のトポロジー・面積・形状比・反転・辺長比診断。 |
| `turboism.validation.warp` | 保存/提案 Warp 制御セルの双線形折返し・退化・基準方向を読み取り専用で診断します。 |
| `turboism.validation.raster` | セッション内保存 pose PNG の領域色比率・frame 差分を検査し、ホスト参照や美術合格判定はしません。 |
| `turboism.rig.rotation.plan` | 検証済み Rotation 保存スナップショットの 1〜3 通常軸で角度置換をプレビューし、別途 CAS 書込み要求を返します。 |
| `turboism.rig.warp.plan` | 既存の一〜三軸 Warp に native 座標 landmark 変位場をプレビューし、書込みや解剖学的推測は行いません。 |
| `turboism.rig.mesh.plan` | 保存 ArtMesh 頂点の landmark 変位と明示 XY 残差をプレビューし、独立 typed 書込要求を返します。 |
| `turboism.authoring.masks.read` | GUID を含む ArtMesh マスク依存スナップショットを読み取ります。 |
| `turboism.authoring.masks.write` | 一つの SDK トランザクションで非空マスクと反転フラグを条件付き置換します。 |
| `turboism.authoring.masks.result` | UI を待たずセッション内の保存済みマスク操作結果を読み取ります。 |
| `turboism.authoring.properties.read` | authoring snapshot を使い自身の表示/ロックと ArtMesh カリングを読み取ります。 |
| `turboism.authoring.properties.write` | 完全な保存状態 CAS と単一 SDK トランザクションで明示した source Boolean 属性を変更します。 |
| `turboism.authoring.properties.result` | UI キューを待たず保存済み source 属性操作結果を読み取ります。 |
| `turboism.files.request_grant` | ユーザーのファイル選択を非同期で要求し、セッション内 grant を発行。 |
| `turboism.files.grant_status` | ネイティブ選択画面を待たずに承認状態を照会。 |
| `turboism.files.revoke_grant` | grant を失効させ、遅れて届いたネイティブ許可も解放。 |
| `turboism.documents.open` | exact 5.3.03 の許可付き CMO3 OPEN を分派し、readiness を再発見。 |
| `turboism.documents.save_as` | 同セッションの WRITE 許可で対象モデルを新しい保存先へ保存。 |
| `turboism.documents.file_result` | UI を待たずに保存コマンドの保持結果を照会。 |
| `turboism.files.inspect` | 単発 READ 許可で完全読取のバイト数と SHA-256 を非同期観測。 |
| `turboism.files.inspect_result` | I/O を待たずに保持されたファイル観測を照会。 |
| `turboism.capabilities.read` | 操作効果、バージョン対応、トランザクション適格性、SDK カバレッジを読み取ります。 |
| `turboism.editor_commands.execute` | 検出可能な直接 Editor コマンドと型付き非ファイル Editor コマンドを実行します。 |

最初の書き込み前にバッチ全体の入力 Schema を検証します。後続要素の形式が不正な場合、先行要素も実行しません。形式が正しいバッチでも実行時エラーによって部分成功することはありますが、完了済みの結果は保持し、`stopOnError` に従って後続処理を止めます。単一のネイティブ操作がアトミックでも、MCP バッチ全体がトランザクションになるわけではありません。

##### 書き込み結果と反転操作の移行

確定したパラメーター書き込みの `outcome`、`retryable: false`、識別子は後続の読み戻し失敗でも保持されます。詳細の読み戻し失敗は `APPLIED_WITH_READBACK_WARNING` です。最後の任意スナップショットが取得できない場合、`parameters` は `null` となり、`parameterSnapshotWarning` が理由を示します。完了済みの `results` は失われません。読み戻しだけが失敗した確定済みの書き込みを再試行しないでください。

曖昧なバインディング操作 `invert` は書き込み前に拒否します。対象の**すべての通常キーフォーム・バインディング**を明示的に反転するには、次を使用します。

```json
{
  "operations": [{
    "operation": "invert_all_bindings",
    "scope": "all_target_bindings",
    "targets": [{"type": "art_mesh", "id": "ArtMesh1"}]
  }]
}
```

単一パラメーターの反転ではなく、ブレンドシェイプのバインディングも反転しません。結果には範囲と影響するパラメーター ID が含まれ、読み戻し成功時には該当する通常バインディングも含まれます。この操作では `parameterId` を受け付けません。単一パラメーターの反転には別の検証済み Provider 契約が必要です。

既存の `model_objects.apply`、`parameters.apply`、`parameter_bindings.apply` は引き続き `transactionEligible: false` であり、`turboism.transaction.execute` はトランザクション開始前に拒否します。個々のネイティブ操作に Undo がないという意味ではありません。`turboism.capabilities.read` で例外を確認できます。カバレッジ台帳はモデルオブジェクト、パラメーター、バインディング、テクスチャを含む明示された 18 型を対象とし、SDK 全体の網羅を保証しません。`MCP_LEGACY_WRITE` は公開済みの旧式書き込みを示し、グループ化トランザクションの検証完了を意味しません。追加行の空の `supportedVersions` は台帳が厳密なバージョン保証を行わないことを示し、実行時には Provider の准入判定が適用されます。

#### テクスチャライブラリの編集

`turboism.textures.read` を `{"operation":"list"}` で呼び出し、返された **`state` オブジェクト** をそのまま書き込みの `expectedState` に渡します。別の `stateToken` 文字列は読み取りの関連付け用であり、書き込みの前提条件や認可トークンではありません。書き込みや Undo／Redo の後は状態を再取得してください。ネイティブ履歴が利用できない場合、書き込みを拒否します。

正確な対象バージョンは `5.2.03 / 5.3.00–5.3.04` です。操作は `add_model_image_group`（`name`）、`remove_model_image`（`id`）、`add_texture_atlas`（`name`、`widthPixels`、`heightPixels`）、`remove_texture_atlas`（`id`）、`remove_raw_image`（`id`）です。すべての削除には明示的な `confirmDelete: true` が必要です。モデル画像グループは名前で識別し、架空の GUID は作成しません。同名グループの作成は変更前に拒否します。

名前と ID は 256 Unicode コードポイント以内、アトラスの各辺は 1–16384 ピクセルです。読み取りは各トップレベル集合を 1024 件、入れ子のモデル画像の合計を 1024 件に制限し、超過時には黙って省略せずエラーを返します。確定した書き込み後の読み戻し失敗では識別子を保持し、`APPLIED_WITH_READBACK_WARNING` と `postState: null` を返します。結果を証明できないネイティブ例外は `OUTCOME_UNKNOWN` であり、`NOT_APPLIED` と誤って報告せず、自動再試行も促しません。ホスト例外中のパスは公開しません。

これらの操作は個別のネイティブ Undo に対応しますが、`transactionEligible` は `false` のままです。台帳の `MCP_WRITE_STANDALONE_UNDO` はグループ化トランザクションの子操作と区別します。生画像の削除は指定された画像とその入力接続のみを処理し、モデル画像、ArtMesh、アトラスを保持します。5.2 ではネイティブ Undo 操作を組み合わせ、削除ダイアログは呼び出しません。

これはライブラリの追加・削除機能であり、ピクセルのアップロード、任意ファイルの入出力、MCP 経由のアトラス配置ではありません。バージョンメタデータはアダプターとテストの対象を示します。実機検証は別に記録され、注釈や単体テストから実機での成功を推測することはできません。

#### リソース

| リソース URI | 目的 |
|---|---|
| `turboism://active/document` | アクティブなプロジェクト、ドキュメント、モデル、選択状態、ワークスペース、テーマのスナップショット。 |
| `turboism://active/model/overview` | コンパクトなアクティブモデルおよび選択状態の概要。 |
| `turboism://active/model/hierarchy` | アクティブなモデルオブジェクト階層。 |
| `turboism://active/model/clip-masks` | アクティブモデルの ArtMesh クリッピングマスクレコード。 |
| `turboism://active/model/parameters` | 実際のアクティブモデルパラメーター状態。 |
| `turboism://active/model/statistics` | 構造、ジオメトリー、テクスチャ、マスク、および任意のオフスクリーン数。 |
| `turboism://active/model/textures` | パスやバイトを含まない、生画像、モデル画像グループ、テクスチャアトラスのメタデータ。 |
| `turboism://active/model/parameter-bindings` | アクティブモデルの集約済みパラメーターバインディング状態。 |
| `turboism://active/document/history` | ネイティブ Undo の可用性、項目、世代、リビジョン、位置。 |
| `turboism://environment/cubism-core` | 受け入れられた Cubism Core バージョンと公開機能フラグ。 |
| `turboism://environment/workspace` | 現在および利用可能なワークスペースと型付き可用性。 |
| `turboism://environment/workspace/layout` | 型付き可用性を備えた順序付き読み取り専用ドックレイアウトツリー。 |
| `turboism://environment/diagnostics` | 範囲が限定され、パスが伏せられた Turboism 起動診断問題。 |
| `turboism://environment/runtime-diagnostics` | 最近の、範囲が限定されサニタイズ済みの MCP ランタイム結果。 |
| `turboism://host/editor-commands` | 現在利用できる対応 Editor コマンドと型付きリクエストスキーマ。 |

リソースはある時点のスナップショットです。サーバーは現在、サブスクリプションまたはリソース更新通知を宣言していません。

ワークスペースおよびレイアウトリソースは、診断コードとともに `availability: "UNAVAILABLE"` を正常に返す場合があります。これは型付きホスト状態であり、権限拒否（`-32001`）、リソース不在（`-32002`）、未対応の機能（`-32003`）、タイムアウト（`-32004`）、キャンセル（`-32800`）に対する JSON-RPC エラーとは異なります。

#### リソーステンプレート

- `turboism://active/model/parameters/{parameterId}`
- `turboism://active/model/parameters/{parameterId}/bindings`

#### プロンプト

- `inspect_active_document`
- `edit_model_structure`
- `normalize_parameters`
- `repair_parameter_bindings`
- `recover_document_history`
- `run_editor_command`
- `diagnose_environment`
- `inspect_model_diagnostics`

プロンプトは引数を受け付けません。2 つの診断プロンプトは、変更操作を明示的に禁止します。

## 機能

### 宣言された機能

| 機能 | ユーザーへの効果 |
|---|---|
| `mcp.streamable-http` | 数値ループバック上でトークン保護された MCP Streamable HTTP を提供します（変更系操作は Bearer 必須）。 |
| `mcp.tools` | 80 個の型付きツールワークフローを公開します。 |
| `mcp.resources` | 静的およびテンプレート化された JSON リソースを公開します。 |
| `mcp.prompts` | ユーザー制御のワークフロープロンプトを公開します。 |
| `cubism.workspace.read` | 型付きワークスペース状態とドックレイアウトのスナップショットを読み取ります。 |
| `cubism.model.objects.read` | 対応するモデルオブジェクトを検査します。 |
| `cubism.model.objects.write` | 対応するモデルオブジェクトを作成、名前変更、再親子付け、削除します。 |
| `cubism.parameters.read` | アクティブモデルのパラメーターを読み取ります。 |
| `cubism.parameters.write` | 型付きパラメーター値および定義操作を適用します。 |
| `cubism.parameter-bindings.read` | パラメーターバインディングを読み取ります。 |
| `cubism.parameter-bindings.write` | 型付きバインディング操作とネイティブのアトミック転送を適用します。 |
| `cubism.history.read` | ネイティブ Undo 履歴を読み取ります。 |
| `cubism.history.write` | 世代およびリビジョンガードを使ってネイティブ Undo 履歴を移動します。 |
| `cubism.editor-commands.execute` | 範囲が限定された、対応する Editor コマンド画面を実行します。 |

## 権限

| 権限 | スコープ | 要求する理由 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | アクティブなモデルオブジェクト、Core メタデータ、統計、テクスチャメタデータを読み取ります。 |
| `turboism.cubism.parameter.read` | `application` | ツール面でのパラメーター読み取り意図を宣言します。実際のパラメーター読み取りは実行時に上記のモデル読み取り権限によって強制されます。 |
| `turboism.cubism.project.read` | `application` | アクティブなプロジェクト、ワークスペース、レイアウト、テーマ状態を読み取ります。 |
| `turboism.cubism.model.write` | `application` | 型付きのモデル、パラメーター、バインディング、履歴、モデル設定の書き込みを適用します。 |
| `turboism.ui.file-chooser.request` | `application` | セッション内のファイル許可を発行する前に、ファイル選択画面でユーザーの承認を求めます。 |
| `turboism.file.read` | `application` | loopback エンドポイントを公開する前に MCP 接続ファイルのメタデータ（存在、所有者、シンボリックリンク検査）を検証します。 |
| `turboism.file.write` | `application` | 直接 Editor `SAVE` コマンドを許可し、MCP 状態ディレクトリに所有者専用の loopback 接続ファイルを書き込みます。 |
| `turboism.network.fetch` | `application` | 型付きの外部アプリケーション設定コマンドを許可し、Origin 保護付きの loopback HTTP トランスポートをバインドします。 |
| `turboism.process.run` | `application` | 型付きの外部アプリケーション設定コマンドを許可します。 |
| `turboism.mcp.connection.publish` | `application` | 権限が承認された自動化プラグインに、ループバックエンドポイントをプロセス内ランタイム交換経由で公開します。 |
| `turboism.action.register` | `application` | ローカルの MCP 接続ウィンドウを開くアクションを登録します。 |
| `turboism.ui.menu.contribute` | `application` | Turboism メニューに **MCP 接続** を追加します。 |

診断拡張によって `host.unsafe`、パフォーマンス、ファイル読み取り、設定、イベント、UI 変更の権限が追加されることはありません。

## プライバシーとデータ

### ネットワーク

サーバーは `127.0.0.1` でのみ待ち受けます。リクエストの `Host` ヘッダーはループバック（`127.0.0.1`、`localhost`、または `[::1]`、任意で `:port` 付き）を指す必要があり、受け入れ可能なループバックオリジン、MCP プロトコルとセッション、1 MiB 以下の本文、および設定済みのレート制限が必要です。変更系ツール呼び出しとセッション終了にはさらに `Authorization: Bearer <トークン>`（`mcp.token` の内容）が必要で、読み取り専用メソッドには認証は不要です。システムプロパティ `turboism.mcp.requireAuthForRead=true` を設定すると、読み取り系を含むすべての `tools/call` に Bearer 認証が必須になります。リモートアクセス用には設計されていません。

### ローカルデータ

プラグインは、接続メタデータ、ユーザー単位の Bearer トークン、stdio ブリッジソースのみをプラグイン状態ストレージへ所有者権限で書き込みます。POSIX システムでは、所有者のみの権限を設定しようとします。診断およびモデルリソースは、生のファイルシステムパス、ネイティブホストオブジェクト、画像バイトを公開しません。

`turboism://environment/diagnostics` は `DiagnosticReport.Problem.path()` を省略し、問題リストを制限し、メッセージを 1 行に変換し、メッセージ長を上限設定し、Unix パス、Windows パス、`file:` URI を伏せます。

### テレメトリー

このプラグインからテレメトリーが送信されることはありません。プラグイン ID を付して、プラグインのライフサイクルおよび障害の記録が Turboism のセッションログと Cubism のホストログに表示される場合があります。

## 状態と制限

- **ステータス:** プレビュー。
- 安定したデフォルトポートは `43123` です。`turboism.mcp.port=0` を明示的に設定した場合のみエフェメラルポートを選択します。`turboism.mcp.requestsPerMinute` は高度なレート制限システムプロパティです。`turboism.mcp.requireAuthForRead`（`true`/`false`、デフォルト `false`）を設定すると、読み取り系 `tools/call` にも Bearer トークンが必須になります。
- GET SSE、リソースサブスクリプション、リスト変更通知、進行状況通知、MCP Tasks は実装されていません。
- ワークスペース切り替えおよびデフォルトレイアウトの変更は、ランタイムが現在 `turboism.host.unsafe` でそれらをゲートしているため、意図的に利用不可です。
- MCP セッションが、生のパスを受け取らずに実際の `UserFileHandle` 認可を受け取れるようになるまで、`EditorFileCommandRequest`、インポート/エクスポート、別名保存、バックアップ、その他のハンドルベースのファイルワークフローは利用できないままです。
- 汎用 SDK 呼び出し、リフレクション、任意のネイティブメンバー、シェル実行、生のパス、ダイアログ自動化、ライフサイクル登録 API は公開されません。
- パフォーマンスサンプリング、キャンバス/プロファイル、物理/アニメーション、テクスチャのピクセル入力と MCP 経由のアトラス配置、スクリーンショット、バイナリリソースは、独自の権限および正確なホスト証跡要件を持つ、独立した将来の機能のままです。テクスチャライブラリの追加・削除は上記の専用ツールを使用します。
- 削除は破壊的です。デフォルトでは参照されているオブジェクトを拒否します。カスケードは明示的に要求する必要があります。

## トラブルシューティング

| 症状 | 確認事項 |
|---|---|
| MCP クライアントが接続できない | **Turboism → MCP 接続** を開き、プロセスが実行中であることを確認し、表示された正確なループバックアドレスを使用します。ポート `43123` が使用中なら、競合プロセスを停止するか明示的な高度なポート設定を使います。 |
| リクエストがディスパッチ前に拒否される | メソッド、オリジン、セッション、MCP プロトコルバージョン、本文サイズ、コンテンツタイプ、レート制限を確認します。 |
| リソースが `UNAVAILABLE` を返す | アクティブなドキュメント/モデル状態と正確なホスト機能の受け入れを確認してください。空の成功値として扱わないでください。 |
| リソースが権限拒否を返す | プラグインディスクリプターの許可と、上記で指定した特定のランタイム権限を確認します。 |

## 変更履歴

### 0.1.0

- 初回リリース。

## サポートとライセンス

- **プロジェクト Web サイト:** [https://turboism.dev](https://turboism.dev)
- **発行者:** Turboism Contributors
- **ライセンス:** Project License
- **プラグイン ID:** `dev.turboism.plugin.mcp`

## モデルのネイティブ描画

`turboism.render.state({})` の完全な `state` を `expectedState` とし、明示した `width` と `height` を `turboism.render.capture` に渡します。パラメータと選択は変更しません。Runtime が精密バージョン、権限、GPU を検証します。失敗時にキャッシュやサムネイルを返しません。状態取得だけでは描画の可用性を証明しません。

上限：各辺 1024、1,048,576 ピクセル、PNG 3MiB、パラメータ 2048 件。画像がトランザクション結果上限を超えるため、両ツールはトランザクション対象外です。描画中にドキュメント、履歴、姿勢が変化すると画像を破棄します。画像には姿勢、履歴、時刻、寸法、SHA-256 が対応します。原点は左上です。SDK はモデルから画像への行列を公開しないため `modelToPixelTransform` は null です。切り抜き、pose grid と限定検査は後述します。実機受入は未完了です。

## 保存キーフォームの読み取り

`turboism.authoring.meshes.read`、`warps.read`、`rotations.read` は発見済みの `documentId`、`modelId`、`objectId` を要求します。最初は `snapshotId` を省略し offset 0 から開始します。以後は同じ `snapshotId` と `nextOffset` でページを取得します。同一の不変 SDK スナップショットから source/stored データを返し、パラメータ、選択、評価を変更しません。GUID、軸、セル、共有形状、既定値、親 ID を保持します。

ArtMesh の `mesh_form` 行は `opacity` も返します。独立した selector 准入がある場合は有限のネイティブ保存値、未対応 selector または旧 SDK 実装では `null` です。不明を 1 とみなしません。Morph の保存値はクランプせず、通常 form の絶対不透明度とも解釈しません。この観測は不変スナップショットの等価性/CAS に含まれ、頂点・描画順 writer とランドマーク計画は保持します。読み取りは opacity や pose を変更せず、通常 opacity の writer は keyforms.write で別途准入します。Warp/Rotation の不透明度と保存色は別契約が必要です。

`ordinary_write_support` 行には閉じた `operationSelectors` map が含まれます。対象の種類に応じ ART_MESH_POSITIONS、ART_MESH_DRAW_ORDERS、ART_MESH_VALUES（頂点/順序）、ART_MESH_VALUES_WITH_OPACITY、WARP_CONTROL_POINTS または ROTATION_FORMS を返します。opacity の native selector 拡張は別に調べるため、幾何 PRESENT と opacity ABSENT が共存できます。欠落項目は UNKNOWN、旧 provider は空 map です。スナップショット時の selector 観測であり、reviewedHost/historySelectors/transactionCoordinator/writePermission/form 資格とは別です。PRESENT は書込み許可ではなく、default/shared/Morph/編集状態/CAS/予算/transaction の検査を省略しません。状態変更後は再発見し、writer は実行時にも検証します。動的セッションはこの観測と実際の Warp/Rotation 型を generic deformers.find/all 経由でも保持し、世代 guard を適用します。

座標は SDK の保存値です。画布・ピクセル変換を合成しません。スナップショットは MCP セッションに属し、5 分で期限切れになります。上限：16 件、各 4MiB、合計 16MiB のシリアライズ行、100,000 行、各ページ 512 行/240KiB。文書・履歴が変わった場合は再取得します。完全な SDK 状態を将来の CAS 用に保持しますが、token は書込権限を与えません。保存形状の書込みは別の keyforms.write 契約を使用し、実機受入は未完了です。

### 通常キーフォームの一括書き込み

先に authoring read で実際の snapshotId を取得し、`turboism.authoring.keyforms.write` に documentId/modelId、固有 operationId、targets を渡します。各 target は kind/objectId/snapshotId/edits、各 edit は formGuid、全通常軸の GUID coordinates と型に応じた完全な positions/controlPoints/form を指定します。全対象の保存状態と親を検証後、単一 SDK トランザクション内でも再検証します。独立した非デフォルト通常形状のみ対応し、共有形状、Morph、トポロジ変更を拒否します。上限は 8 オブジェクト、64 form、合計 16,384 点、768 KiB。transaction.execute への入れ子は不可です。成功後はスナップショットを再取得します。

応答喪失時は `turboism.authoring.keyforms.result` に operationId を渡します。同一要求の再送は保存結果を返し、ID の異なる用途への再利用を拒否します。RUNNING、ROLLED_BACK、OUTCOME_UNKNOWN、APPLIED_WITH_READBACK_WARNING を区別してください。不明な結果は未変更を意味しません。結果はセッション分離・プロセス内保存で、サーバー全体で最大 256 操作まで保持します。古い結果を削除して再実行せず、満杯なら新規書き込みを拒否します。Agent のキャンセルはホストのロールバックを保証しません。再起動/別セッションの復旧、実機 Undo/Redo と保存・再オープンは別途検証が必要です。

### 姿勢スキャンと復元

render.state の完全な state を expectedState とし、operationId、width/height、poses、timeBudgetMs、restorePolicy:"always" を pose_grid に渡します。各 pose は一意の label と values:[{parameterId,value}] を持ち、省略値は前の姿勢ではなく元の値を使います。書込み権限と Bearer が必要で、SDK トランザクションを自身で所有します。transaction.execute への入れ子は不可です。値の復元後も Undo が一件残る可能性があり、実際の receipt を確認してください。復元後も履歴 revision や古い authoring snapshot が有効とは限らず、状態を再取得してください。

上限は 9 姿勢、6 対象パラメータ、合計 1,048,576 画素、PNG 合計 3 MiB、結果メタデータ 240 KiB、計画/基準メタデータ 128 KiB。最大 60 回の値書込み/復元は SDK の 64 項目制限内です。通常の state/capture のパラメータメタデータも 128 KiB まで。重複表示 ID は SDK が先頭要素を参照するため拒否し、ordinal を GUID と見なしません。

各 frame は実際の姿勢 state、値、captureId、PNG ハッシュと imageContentIndex を持ち、九姿勢なら九枚の typed image を返します。添付数のみ 8 から 9 に変更し、PNG 3 MiB、HTTP 要求 1 MiB、Pi 8 MiB とトランザクション結果 256 KiB は変更しません。

応答喪失時は status/result で operationId を照会します。cancel と最大 30 秒の予算はホスト呼出し間の協調制御で、GPU、setter、復元を強制中断しません。失敗時も復元を試み、全文書/モデル/世代が変われば新しい対象に書きません。parametersRestored は元の全値を確認できた場合のみ true です。FAILED、CANCELLED、TIME_BUDGET_EXCEEDED、RESTORE_UNVERIFIED、OUTCOME_UNKNOWN を区別してください。

セッション分離・プロセス内保存で画像は最大 2 スキャン、操作識別子は 64 件です。証跡を消費後、完了画像を release して次のスキャンを可能にします。メタデータと再実行防止情報は残り、解放済み result は画像なし、imageContentIndex=null、imagesReleased=true です。不明な結果は未変更を意味しません。再起動/別セッション復旧、実機/GPU、視覚モデル消費、品質と保存再オープンは別途検証が必要です。

## 範囲を限定した幾何診断

`turboism.validation.geometry` は `authoring.meshes.read` の発行済みスナップショットを使用します。documentId/modelId/objectId/snapshotId、includeSource、referenceFormGuid、formGuids、thresholds、maxFindings は必須です。同一オブジェクトの通常保存 form を比較基準として明示します。source のみなら formGuids=[]、referenceFormGuid=null。default/shared の通常 form は診断できますが、Morph 参照・非通常 form は拒否します。解析の前後で完全なネイティブ状態、親と履歴を照合し、revision が同じでも幾何の変化を拒否します。

空・未使用・重複・非多様体トポロジー、向き、三角形面積/形状比、保存基準からの反転/辺長比、各幾何内の三角形内部の正面積重複を検査します。閾値は有限値で明示します。上限は対象 32 form、保持 findings 200 件、推定作業量 2M、結果 240 KiB。省略または未定義比較は INCOMPLETE。ok は診断の実行成功であり、モデル受入ではありません。境界接触、別オブジェクト間交差、亀裂、評価/補間、UV/アトラス、画布、マスク/遮蔽、美術品質は未実装として返します。パラメータ/選択の変更、描画、書き込みは行いません。

内部重複は X 順の AABB 走査と分離軸判定を使用し、double 誤差界で符号が確定しなければ binary float 値を正確に保つ BigDecimal に切り替えます。包含・一致・隣接面の折り返しを WARNING とし、共有辺/頂点や境界接触だけでは重複扱いしません。triangleIndex/relatedTriangleIndex で両面を特定し、indices は最初の面のみ、第二面は発行済み topology から取得。source/保存 form は別々に解析し、別オブジェクト比較や自動修正は行いません。

共通 2M 推定予算は基本診断・ソート・走査・正確な述語を制限します。予算切れは intersectionCheckComplete/analysisComplete/comparisonComplete=false と INCOMPLETE。intersectionPairsTested、intersectionSweepVisits（除外アクセス含む）、estimatedWork を返します。findings/counts は観測済み範囲のみで、対象 form を絞って再確認できます。結果切詰め/未定義基準も INCOMPLETE。重複は幾何証拠であり、美術欠陥や全モデル合格を意味しません。

`authoring.keyforms.write` の ArtMesh edit は positions、整数 drawOrder（0..1000）、または両方を受け付け、バッチ内で混在できます。実際の formGuid と完全な coordinates が必要です。描画順のみの場合は専用 SDK writer で全頂点を保持し、混在/同時変更は combined SDK contribution を使用します。省略フィールドは immutable expected snapshot から補完し、両 SDK リストの form 集合を一致させます。保持する補完頂点も 16,384 点上限に算入します。default/shared/Morph 保護、トランザクション、完全読み戻しと operationId の意味は維持します。同一の前後状態では検証済みトランザクション内の setter を省略します。実機 Undo/Redo と遮蔽の見え方は別途受入が必要です。

ArtMesh の通常 edit は `opacity`（有限数 0..1）も受け付け、positions/drawOrder と任意に組み合わせられます。対象の保存 opacity は既知で通常値の対応範囲内である必要があります。混在バッチは閉じた SDK `ArtMeshKeyformValues` を使い、省略値は完全な expected スナップショットから保持します。保持位置を含む全対象頂点を既存の 16,384 点予算に数えます。Runtime は保存 opacity の読み書き selector を別途准入し、全 tuple/form と default/shared/Morph 保護を setter 前に検証して、一つの native keyform contribution で更新します。完全な読み戻しと operationId/result/rollback の意味を維持します。既存の ordinary_write_support.writerSelectors は幾何インフラの観測で、opacity selector の准入ではありません。現在 pose の setter、Morph opacity、自動の目口フェードではありません。実宿主の Undo/Redo、描画と永続化は引き続き検証が必要です。

## ArtMesh クリッピングマスク

`authoring.masks.read` に documentId/modelId と対象・使用予定ソースの objectIds を渡します。既存マスク依存を推移的に展開し、最大 64 オブジェクトの GUID/親/マスク/反転/履歴とセッション snapshotId を返します。TTL 5 分、最大 16 スナップショット、各 64 KiB。選択・パラメータは変更しません。

`authoring.masks.write` は documentId/modelId/snapshotId、operationId と replacements:[{objectId,maskIds,inverted}] を受け付けます。最大 8 対象、128 KiB。対象と新ソースは発行済みスナップショット内である必要があります。SDK トランザクション前/内部で全依存の GUID/親/マスク/反転と履歴を照合し、単一ネイティブ条件付きバッチ後に対象外を含め全範囲を検証します。SDK が空置換を禁止するためマスク削除は未対応です。自己参照・重複・新規循環を拒否します。幾何変更や視覚受入は行いません。

応答喪失時は `authoring.masks.result({operationId})` を使用し、RUNNING/APPLIED/NO_CHANGE/ROLLED_BACK/OUTCOME_UNKNOWN/APPLIED_WITH_READBACK_WARNING を区別します。同一要求は保存結果、競合 ID は拒否。結果はセッション分離・プロセス内、最大 256 操作で削除せず、満杯なら新規書込拒否。結果なしは未変更の証明ではありません。Agent の中止はホストのロールバックを意味しません。実機 Undo/Redo・遮蔽・永続化は未検証です。

## ArtMesh 最終計算結果の観測

`authoring.meshes.evaluated.read` は documentId/modelId/objectId と同一セッションの meshes.read authoringSnapshotId を受け付け、snapshotId/offset/limit で同じ immutable 観測をページ化します。各ページで保存状態、親/履歴、完全なネイティブ標本を照合し、姿勢・計算結果の変化を拒否します。model.update、描画、パラメータ/選択変更、保存/Core 幾何への代替は行いません。GUID ごとに重複表示 ID の値を保持します。

freshness は常に UNPROVEN。passEntryTimeMillis は計算開始であり成功完了ではありません。liveConsumedValuesMatch は GUID/値の比較のみ、live/consumed updateVersion は独立した符号付きカウンタ、interpolatedIdentity はネイティブ同一性です。どれも評価成功を証明しません。calculated form がなければ頂点行を返さず、coordinateType は原生ラベルとして保持し単位/Core/画素変換を推測しません。

上限は 65,536 頂点、live/consumed 各 2,048 パラメータ、4 MiB 標本、各ページ 512 行/240 KiB。authoring キャッシュ/セッション/TTL を共有し、依存標本挿入時も親の TTL を延長しません。実機・視覚受入は未完了です。

## オブジェクトの source 属性

`authoring.properties.read` は documentId/modelId/kind/objectId/authoringSnapshotId を受け付けます。kind は art_mesh/warp_deformer/rotation_deformer。同一セッションの対応する authoring token が必要です。scope=source_object の自身の visible/locked、ArtMesh の culling を返します。祖先込みの実効フラグや現在形状の opacity/色ではありません。完全な不変の保存状態/親を保持し、authoring TTL/件数/バイト予算を共有します。

`authoring.properties.write` は documentId/modelId/operationId と最大 8 個の targets:[{kind,objectId,snapshotId,properties}]。非空 patch は明示した対応 Boolean のみ。SDK トランザクション前/内部/書込後に全保存幾何/forms、source/親/履歴と対応属性を照合します。未指定属性と保存 form は不変で、同値 setter は省略します。locked:false は当該 source のみを変更し、自動解除や祖先変更はありません。要求 128 KiB 上限。

応答喪失時は `authoring.properties.result` で照合します。セッション/プロセス内 256 操作まで削除せず保持し、競合 ID は拒否、結果なしは不明です。Agent 中止は rollback ではありません。結果/証跡の形式はマスク操作と共有します。Part、現在 form の opacity/色、合成モードや generic SDK setter は未提供で、専用の識別/読み戻し/form 契約が必要です。実機視覚/Undo/永続化は未受入です。

## セッションファイル許可

`files.request_grant({operationId,mode,purpose,allowedExtensions})` は Bearer 必須です。mode は READ/WRITE、purpose は最大 200 文字、拡張子は 1–8 個。SDK の選択画面で承認し、パス/ネイティブ handle/内容は受け渡しません。`files.grant_status({operationId})` で照会し、GRANTED は同セッション/プロセス内の opaque grantId を返します。documents.save_as consumer を提供し、CMO3 OPEN は READ 許可を使用し、汎用の内容転送は未提供です。

准入から 5 分で失効し更新しません。`files.revoke_grant({operationId})`、セッション削除、サーバー停止で handle を解放し、遅延 grant も破棄します。取り消しは選択画面の終了を証明せず、nativeChooserPending/hostCancellationConfirmed を区別します。ネイティブ完了待ちはサーバー全体で 1 件、取り消し/失効でも枠を解放しません。再実行防止の要求 ID は 128 件まで保持し、満杯なら新規要求を拒否。許可作成には Undo がなく、モデルを開いたり保存したりしません。実機選択画面と永続化は未受入です。

## 許可付きモデル別名保存

`documents.save_as` は operationId、同セッションかつ allowedExtensions=["cmo3"] の WRITE grantId、authoring 読取の六フィールド expectedState、overwritePolicy=REJECT_EXISTING を要求します。許可消費前と native 呼出直前に文書/model/history を照合します。grant は一度だけ CONSUMED になり、handle は呼出終了まで保持して解放。READ/別セッション/失効/消費済みは拒否し、Undo や transaction.execute 内の実行は提供しません。

応答喪失時は `documents.file_result({operationId})` を照会。操作 ID はセッション分離・128 件まで削除せず保持し、同一再送で再保存しません。取消は UI 准入までで、native 開始後は実結果を待ちます。EXECUTED は native の成功報告（no-op 含む）のみ、FAILED/例外は OUTCOME_UNKNOWN、状態変化/取得失敗は STATE_READBACK_WARNING。persistenceVerified/reopenVerified は常に false です。CMO3 OPEN は exact 5.3.03 のみ准入し、PSD は未提供。上書きには独立したユーザー承認契約が必要なため現在は除外します。

## 許可されたファイル観測

`files.inspect({operationId,grantId,maxBytes})` は同セッション READ grant を一度消費し、即座に応答します。`files.inspect_result({operationId})` で照会。maxBytes は 1..134217728、旧 SDK では 16 MiB 超が RUNTIME_UNAVAILABLE になる場合があります。サーバー全体で読取/ハッシュ 1 件、SDK の一時バッファ複製に注意。結果は小さなメタデータのみで、パス/ファイル名/バイトは返しません。128 操作 ID を削除せず保持し同一再送で再読取しません。ファイルを書き換えませんが grant 消費には Bearer が必要で、transaction 非対応です。

非切詰め読取のみ COMPLETE と byteLength/SHA-256 を返し、TRUNCATED に prefix hash は付きません。worker は登録表ロック外でハッシュし、セッション削除/停止後の遅延結果を破棄して実際の I/O 完了後に handle を解放。native 取消は主張しません。fileStability=UNPROVEN、documentAssociation=UNVERIFIED：同時更新がないこと、SAVE_AS の保存先との同一性、モデル幾何との一致は保証しません。形式/同一性/再読込は別に検証し、persistenceVerified/reopenVerified は変更しません。

`turboism.rig.rotation.plan` は半自動 rig 用の読み取り専用処理です。rotations.read の snapshot、書込み operationId、policy=REPLACE_ANGLE_FROM_DEFAULT を指定します。旧単軸 parameterGuid/nativeAnglePerParameterUnit、または全通常軸を一度ずつ覆う axes の 1〜3 個の明示的 {parameterGuid,nativeAnglePerParameterUnit} を選択します。各軸は範囲内の一意な key を 2 個以上含み、正確な default が必要です。独立した完全 Cartesian グリッドは最大 65 cells/64 非 default edits です。対象 angle = 全軸 default 保存 angle + Σ((cellKey-axisDefault)×gain) とし、snapshot 軸順に加算し、最後に float へ丸めます。各 form の中心/scale/反転、default form、baseAngle、axes/cells と無関係な form を保持します。GUID で軸と座標を対応付け、表示 ID や入力順序では対応付けません。保存角度の加算であり、回転の合成、XYZ/中心の推論、単位変換は行いません。最大 128KiB。計画前後で完全な native 状態、親と履歴を確認します。返された keyforms.write は別途書込み准入と新鮮な CAS が必要です。setter、pose 移動、描画、transaction は行いません。評価時の補間、頭部体積・目口の連動、視覚受入は未検証です。

ArtMesh/Warp/Rotation の保存ページには幾何行より先に `ordinary_write_eligibility` 行を返します。formGuid、snapshotEligible、reason、hostWriteAdmission=UNPROVEN を含みます。default/shared/Morph 参照/通常 cell なし、default key/cell 欠落、cell 重複、共有 default を区別します。通常 writer と同じグリッド規則ですが、宿主書込み selector、権限、編集モード、payload 制限や transaction 准入は未検査です。不正グリッドは全対象を拒否しても読取りは可能です。発行済み不変 snapshot の情報であり、ページ読取りは native 幾何を再取得しません。実書込みには live CAS と native 准入が必要です。ツール数は変わりません。

保存ページには `ordinary_write_support` も含みます。hostVersion と reviewedHost/writerSelectors/historySelectors/transactionCoordinator/writePermission の UNKNOWN/PRESENT/ABSENT 観測です。新 Runtime は selector 授権メタデータを照会し、setter や Undo を実行しません。権限 wrapper は model.read を要求し model.write を別途報告します。SDK 既定値と旧 adapter の未実装照会は UNKNOWN。snapshot 発行時の観測であり、後のページで更新せず、編集モード・form/default 制限・payload/CAS・transaction 准入は保証しません。全て PRESENT でも書込み成功や実機受入とはせず、form の hostWriteAdmission は UNPROVEN のままです。

`turboism.rig.warp.plan` は既存の一〜三軸 GUID、default と両側 key を持つ完全独立 Warp グリッド（最大 65 cells）を計画します。各軸の負/正端点に 1..16 anchor（controlPointIndex/dx/dy、native 保存座標）を指定し、距離二乗逆数の正規化重みで補間します。anchor 位置を再現し、各 key は default から宣言済み極値 key までの比で変位を拡縮します。各軸の場は加算します。任意の `cornerCorrections` は異なる二つ/三つの既存 GUID の NEGATIVE・POSITIVE 方向と 1..16 個の残差 anchor を指定します。最大 20 個の一意な方向マップ（12 二軸象限 + 8 三軸八分空間）です。重みは宣言した軸の側の比だけを乗算し、宣言軸の default/反対側ではゼロ、対応する端点角で一です。二軸補正は第三軸の全 key に適用し、三軸補正は任意の default 面でゼロになります。明示的な保存 key 補正であり、遠近推論や評価補間の保証ではありません。REPLACE_CONTROLS_FROM_DEFAULT は対象 controls を置換し、default/寸法/quadTransform/axes/cells/未参照 form を保持します。最大 64 edits、4096 points、240KiB。計画前後に完全な native/親/履歴を照合し ordinary writer 事前検査を再利用します。返された keyforms.write は別途権限/transaction/live CAS が必要です。描画・書込み・美術/遠近/折畳み/連続性/視覚受入は行いません。再計画で変位を累積しません。

`turboism.validation.warp` は発行済み warps.read snapshot、通常 referenceFormGuid、1..32 formGuids、minimumAbsJacobianRatio、maxFindings を受け取ります。任意 proposedEdits は ordinary writer の事前検査のみを再利用し、選択対象だけを置換、基準 form の変更は拒否します。row-major セル四隅の正確な二進 float 双線形 Jacobian 行列式を計算し、符号混在は局所折返し、角の abs(det)/セル境界箱対角線二乗が閾値以下なら退化を報告します。正則セルは基準と方向比較し、正則な鏡像基準も有効です。row/column と四つの制御点 index を返します。非正則な基準/対象は方向比較を省略し comparisonComplete=false。default/shared 通常 form は観察可、未参照 form は拒否。基準込み 8192 セル標本、保持 200 findings、240KiB が上限で、超過時は verdict を返しません。切詰めでも総数は保持します。quadTransform=true でも双線形制御網の代理診断であり、native 補間・全体重複・評価幾何・親変換・pose 連続性・視覚品質は未検査です。前後に完全な native/親/文書/履歴を検証し、書込み・描画・transaction は行いません。審査後の keyforms.write は別途 fresh CAS が必要です。

rig.warp.plan の対象 edits を proposedEdits に渡し、formGuids はその edits から取得します。snapshot 全 forms には未参照 form が含まれ得るため一括指定しません。実際の通常基準 form を選び、変更せず保持してください。診断は後続の書込み権限を付与しません。

`turboism.rig.mesh.plan` は発行済み meshes.read snapshot と一〜三つの通常軸 GUID を使い、default と両側の宣言済み key がある完全独立グリッド（最大 65 cells）を要求します。各軸に負/正それぞれ 1..16 anchors（vertexIndex/dx/dy）を native 保存頂点座標・順序で指定し、source/画像/評価座標を混同しません。任意 cornerCorrections は Warp と同じ二/三軸 GUID の NEGATIVE/POSITIVE と最大 20 個の一意な残差 anchors を使います。共有の逆距離二乗エンジンで軸の default→宣言端点の比を適用し、残差は宣言軸の側の比だけを乗算します。二軸補正は第三軸全体に適用し、三軸補正は任意の default 面でゼロです。REPLACE_POSITIONS_FROM_DEFAULT は非 default 頂点を置換し再計画で累積しません。source positions/UV/triangleIndices、保存 draw order、default、axes/cells、無関係 forms/Morph 記録を保持。通常 writer の事前検査で default/shared/Morph 参照対象・基準を拒否し、実ホスト書込准入は保証しません。最大 64 edits/4096 頂点/240KiB、前後の完全 native/親/履歴を照合。トポロジー編集、美術・遠近推論、描画、pose 移動、transaction は行わず、後続 keyforms.write に別途権限と fresh CAS が必要です。

validation.geometry に任意の positions 専用 proposedEdits を追加しました。mesh 計画の対象 edits を渡し、formGuids はそこから取得、変更しない明示通常基準を選びます。書込みせず提案 snapshot を構築し、基準・未選択対象の変更を拒否、source/UV/indices/draw order を保持します。proposedEditCount を返し前後に元 snapshot を照合します。既存の検査、予算、切詰め/INCOMPLETE と未実装の視覚・評価検査は同じです。読取成功や空 findings はモデル受入を意味しません。

render.capture と render.pose_grid は任意の `crop:{x,y,width,height}` を native raster pixel（左上原点、x 右・y 下）で受け取ります。入力 width/height は全 native raster 寸法（各最大 1024）のままです。crop は描画や解像度を変えず、完全に範囲内の正寸法矩形のみ許可します。不正範囲は描画前、scan ではパラメータ書込み前に拒否。PNG/frame width/height は切出し寸法、renderWidth/renderHeight は元 raster、crop は切出し領域です。元 pixel=出力 pixel+crop 原点。省略時も全 raster 領域を明示します。modelToPixelTransform は null のままで、保存幾何座標への変換は推測しません。

scan 全 pose に同じ crop を使い、typed image ごとに元座標を保持し部分失敗・復元後の結果にも残します。同 operationId の crop 変更は競合、再送/result は保持画像を返し再描画しません。native の単一/累積 pixel・時間予算は全 raster を対象とし、PNG byte 予算は切出し結果を対象とします。目口の局所比較用の観察であり、native 拡大・視覚品質・実ホスト受入の証明ではありません。

`turboism.validation.raster` は同じ MCP セッション内の終了済み・未解放 pose-grid PNG を検査します。現在のモデル参照や再描画は行いません。operationId と 1..32 個の一意な check を指定し、frameIndex、切抜き後 PNG の左上原点 region (x/y/width/height)、包含範囲 minimumFraction/maximumFraction を与えます。color_fraction は R,G,B,A 順の minimumRgba/maximumRgba バイト範囲を使います。透明・背景色を除外する alpha を明示してください。frame_difference は referenceFrameIndex と tolerance を使い、乗算済み sRGB RGB または alpha の最大バイト差が tolerance を超える画素を数えます。完全 raster/crop 座標は一致が必要で、位置合わせ・再標本化はしません。分母は ROI 全画素、閾値比較は正確な個数で行います。上限は 4194304 ROI samples、1048576 復号画素、3MiB PNG、240KiB レポートです。PNG 長さ・SHA-256・実寸を検証し、設定/個数/割合/判定と frame の captureId/hash/pose/state/crop を返します。imageContentIndex は null、画像/base64 は返しません。checksPassed は明示した閾値だけの判定で、ok は検査実行成功を示します。部分終了 scan も実在 frame を検査できますが、scanOutcome/parametersRestored/allRequestedPosesCaptured は独立して返します。保存画像は現在のモデル鮮度を証明しません。実行中/解放済み/不存在 frame は拒否します。適切な ROI/閾値による白目漏れ等の画素証拠であり、目口の意味推定や美術品質の合格判定ではありません。

同じ `rig.warp.plan` は `policy:"PROJECT_HEAD_VOLUME_FROM_DEFAULT"` と、相互排他的な `headVolume:{centerX,centerY,radiusX,radiusY,depth,cameraDistance}`、`axes:[{parameterGuid,component,degreesPerParameterUnit}]` を受け取ります。全通常 GUID を一度ずつ一意な YAW/PITCH/ROLL に対応付け、anchor/cornerCorrections は指定しません。native 保存親座標で正の深度は仮想カメラ方向です。default 点の u=(x-centerX)/radiusX、v=(y-centerY)/radiusY から z=depth*sqrt(max(0,1-u²-v²))、楕円外は z=0。距離 d のカメラ射線に沿い X=(x-centerX)*(d-z)/d、Y も同様に持ち上げます。(cellKey-axisDefault)×gain 度で center/z=0 の周りに数学的右手 +Y yaw、+X pitch、+Z roll の順で回し、centerX+d*X'/(d-Z')、Y も同様に投影します。default 視点を保ち、ゼロ角度は元の点を直接複製します。画像定義の近似深度場であり、三次元楕円体復元・意味推論ではありません。正の半径、非負 depth、cameraDistance>depth かつ cameraDistance-depth≥距離の 1%、各 cell yaw/pitch±85°・roll±180°、カメラ分母≥d の 1% と有限 native float が必要です。通常 grid/default/shared 保護、65 cells/64 edits/4096 points/240KiB は維持します。default から対象を置換し累積せず、default/grid/quadTransform/axes/cells/無関係 forms を保持し cornerCorrectionCount=0 を返します。計画と validation.warp proposedEdits は読み取り専用で、適用には別の fresh CAS/承認が必要です。head Warp、座標、profile、軸符号、親子適合と実描画を確認してください。children 補間、遮蔽、目口連動、美術受入は保証しません。

`turboism.authoring.keyforms.preview` は `keyforms.write` と同一の閉じた入力を受け、独立した通常 form 制約、native/親/履歴の完全 CAS と予算を共有します。計算後にも状態を再検証し、オブジェクト/form ごとの変更フィールド、点/描画順/不透明度/反転の変更数、native 座標の最大変位、原点変位、角度絶対差（単位変換なし）、scale 差、before/expected-after ハッシュを返します。no-op は空の changedFields を保持します。上限は 8 対象/64 forms/16384 予算点（opacity/複合バッチの保持点を含む。order のみは 0 点）/768KiB 入力/240KiB 出力。applied=false、readbackVerified=false、writeAdmission=NOT_CHECKED。トランザクション取得、setter、pose/render、ID 予約、snapshot 無効化は行いません。admitted ID は result を照会してください。確認後の write は CAS と実際の准入を再検証します。書き込み許可や幾何/視覚品質の合格を意味しません。

`turboism.assets.binding.read` は documentId/modelId/objectId と meshes.read の authoringSnapshotId を要求します。source locked、実際の textureInput（未関連のみ null）、最大 1024 の一意な登録済み画像 id/name/寸法/groupIndex を返します。atlas-region は別 kind です。画像ピクセル、レイヤー意味や GPU sampling の証明ではありません。binding snapshot は既存 authoring cache（5 分/16 件/16MiB、native 状態込み）に属し、応答上限 240KiB です。

`assets.binding.bind` はこの snapshotId、未使用 operationId と発見済み modelImageId を取り、native geometry/forms/source、親、lock、既存入力（modelGeneration 込み）、画像候補 metadata と履歴を事前と独立 SDK transaction 内で再確認します。親 deformer がなく unlocked の未関連 ArtMesh のみ。同じ画像は native bind を呼ばず NO_CHANGE、異なる画像/atlas は拒否し、上書き/解除/親変更をしません。SDK handle と textureInput の model/image/mesh/generation が一致し、UV/geometry/forms/親/catalog が保存される必要があります。不一致は transaction 内で rollback。実 host の permission/Undo/extension 制約を迂回しません。path/grant/画像 bytes は受け取らず、PNG 登録は別の assets.images.register job を使います。

`assets.binding.result` は host を読まず過去の結果を返します。最大 256 操作を server 内に保持し、同一入力の再送は再実行せず結果を返します。競合 ID は拒否。結果欠落は未変更の証明ではありません。commit 後の読取失敗は APPLIED_WITH_READBACK_WARNING、応答喪失/復旧失敗は OUTCOME_UNKNOWN、盲目的再試行は禁止です。成功は実 textureInput と receipt を返します。hash は document/model/mesh/source GUID と入力 kind/資源 ID のみ（generation/geometry は別途 CAS）。成功 snapshot は無効化され、再読が必要です。この tool は独自 transaction を所有し transaction.execute に参加しません。render/視覚/実 host Undo/Redo は別途検証が必要です。

`turboism.assets.images.register` は 承認された PNG から raw image/新 group/model image と ArtMesh 関連付けを作ります。meshes.read と assets.binding.read で対象を発見し、allowedExtensions=["png"] の同 session READ grant を取得してください。documentId/modelId/objectId、binding snapshotId、grantId、operationId、明示 name（1..128、空白のみ/制御文字不可）、maxPngBytes（1..16MiB）、maxPixels（1..16777216）、任意の lowercase expectedSha256 を指定します。path/base64/上書き/自動 unlock は受け取りません。画像名は明示 name を使い、選択 filename は公開しません。

開始は即座に job を返し assets.images.result を poll します。phase は QUEUED/PREFLIGHT/READING/VALIDATING/HOST_PENDING/HOST_RUNNING/TERMINAL。grant 消費前に model preflight、読取後は PNG header/完全読取/画素予算（各辺 <=8192）と不変 authorized bytes の hash を検証します。実 decode は Runtime が検証。truncated/失敗/hash 不一致は登録しません。fileStability=UNPROVEN。native 准入前にも snapshot TTL と完全 geometry/forms/source/親/lock/input/catalog/history CAS を再確認。独立 SDK transaction 内で raw image/group/model image が各一つ追加され、明示 name/寸法と model/mesh/generation が一致し、既存 metadata/UV/geometry/forms が保存される必要があります。binding.read の groupCount で空 group 変化も検出します。失敗は rollback または recovery failure。実 host permission/Undo/extension 制約は迂回しません。

全体一 job が I/O/worker/host slot を実完了まで保持し、最大 128 operationId、同一要求は再実行せず結果を返します。READ stage を cancel せず、lease は実完了時のみ解放。assets.images.cancel は native transaction 准入直前まで有効です。hostAdmitted 後の取消/切断/server shutdown は host rollback の証明ではなく、実際の commit/rollback/unknown を保持。session close は未准入を防ぎ結果アクセスを破棄します。成功 writeResult は rawImageId/textureInput/receipt と metadata hash、fileEvidence は正確な不変 authorized bytes の SHA-256/寸法/長さです。結果に PNG bytes は残りません。metadata hash は pixel/geometry/ディスク安定性を含まず、geometry は別途完全 CAS。post-commit 読取失敗は APPLIED_WITH_READBACK_WARNING、応答喪失は OUTCOME_UNKNOWN、盲目的 retry は禁止。transaction.execute 非参加で、GPU/実 host Undo/Redo/save-reopen/完全 PSD import の証明ではありません。

tools/list は 50 件ずつなので nextCursor を追ってください。Pi 回帰は 80 tools と二ページ目の result/cancel 発見・実行を検証します。

## 完全矩形アトラス job

`turboism.atlas.read` は documentId/modelId と明示 maxTotalPixels（1..67108864）を受け、opaque native target を session と完全な history/native state に束縛して5分間保持します（最大16 snapshots）。path/target は公開・受付しません。native invocation と polygon layout は非対応です。

`turboism.atlas.plan` は snapshotId/operationId と policy=SHELF_HEIGHT_DESCENDING から applyRequest を返します。書き込み・operation 予約・最適性保証はありません。`turboism.atlas.apply` は完全な矩形 plan のみ：全 image を一度ずつ、元の寸法、回転/拡縮なし、明示的な一意のページ名/サイズ、margin/padding/pixel budget を検証します。最大512 images、32 pages、辺8192、outline合計8192 vertices、projection/request240KiB。outline は CAS に含め、packing には使いません。

apply は非同期、`turboism.atlas.result` で照合します。同時1 job、最大256 operation identities、exact replay は再適用しません。`turboism.atlas.cancel` は native admission 前のみ停止可能。切断/終了は admission 後の host cancellation を意味しません。native atlas Undo は独立で SDK transaction に参加せず、receipt/rollback を保証しません。Runtime admission が最終判断です。provider failure/rejection・応答消失は OUTCOME_UNKNOWN、成功後 readback 失敗は APPLIED_WITH_READBACK_WARNING。layout hash/readback は metadata の検証であり、ArtMesh binding、GPU pixels、実 Undo/Redo、保存再読込の証拠ではありません。

## 承認済み MOC package

`turboism.export.moc.plan` は documentId/modelId、operationId、同session WRITE grantId（filterは正確に["zip"]）、writePolicy=REPLACE_AUTHORIZED_FILE、全native settings（originX/originY/pixelsPerUnitと5つのboolean）、budgets（maxMocBytes64..67108864、maxArchiveBytes1..134217728、maxTotalPixels1..33554432）を取ります。float正規化、atlas metadata/Core inspection availabilityを確認しexecuteRequestを発行。export/byte inspection/grant消費なし、writeAdmission=NOT_CHECKED。5分TTL、最大16plans。source検証はhistory/atlas metadata/orderで、完全なimmutable geometry snapshotではありません。

execute は公式MOC5.3 exporter、settings/texture index寸法確認、Core V5_3/CONSISTENT検証後、top-left ARGB PNG と model.moc3、相対参照のみのstatic model.model3.json、hash/size/settings/sourceを含む export-manifest.json をZIP化します。任意のarchive path/inline bytes不可。physics/motions/expressions/pose/displayInfo/blink/lipsyncGroupsは含まず、fullCharacterDelivery/playerLoadVerified=false。

encoding後もTTL/history/atlas metadataを確認して一回限りのZIP WRITE grantを消費。REPLACE_AUTHORIZED_FILEはユーザー選択先のatomic replacementを明示的に許可します。新SDK writeBytesAtomicBoundedはRuntimeで明示128MiBまで、旧writerは8MiBのまま。互換defaultは8MiB以下を委譲し、それ以上はfail closed。native SDK allocationはMCPのpost-return budgetより先で、PNG/ZIPには一時copyも必要。同時1job、128identities、exact replayは再export/writeしません。resultで照合、cancelはfile write admission前のみ。pending stageはcancelせず、実完了までlease/slotを保持。WRITTENはSDK atomic replacement報告のみ、falseはNOT_WRITTEN、例外/応答消失はOUTCOME_UNKNOWN。Undo/自動retry/file reread/player受入を保証せず、resultにbytes/base64/path/handleを返しません。

### パラメーター構造計画

`parameters.structure.read` は documentId/modelId を要求し、フォルダーとパラメーターの実際の混合順序、定義、nullable な Combined マーカーと相互の相手IDを読み取ります。128フォルダー・512パラメーター・192KiBまで。現在値・binding・geometry・pose・選択は変更しません。重複表示IDや不整合な木/ペアは拒否します。各parameter行にnative `guid` を追加。identityはparameter GUID・一意な表示ID・構造フィールド・6項目のhistory bindingです。folderはID/fieldsのままでnative folder GUID CASではありません。同じ表示IDでもGUIDが変われば旧計画を拒否。重複GUID/reader unavailableも拒否し、ID/indexで代替しません。Combined不明を未配対と推定しません。

`parameters.structure.plan` は snapshotId と最大16個の逐次 edits を受け取ります。create_group（root直下）、rename_group、remove_group、move_parameter、combine、uncombine のみ。作成refは後続編集で `{"createdRef":"eyes"}` として参照可能です。既存フォルダーは `{"id":"取得済みID"}` を使用します。予測の `~created-group:` はnative IDではありません。新規作成直後の削除、フォルダー自体の移動/並べ替えは対象外。移動indexは移動元を除去した後の混合順序です。ペアの移動・分断前にuncombineが必要で、combineは同一フォルダーの未ペアNORMAL限定です。同じ既存ペアはno-op。

remove_groupは**子孫フォルダーとパラメーター/rigデータ全体を削除**します。自身を含む前順expectedGroupIdsとモデル順expectedParameterIdsの完全一致を要求します。rigを保持する削除ではありません。プレビューは書き込みせず writeAdmission=NOT_CHECKED、予測構造・削除範囲・hashを返します。snapshot/planは元snapshotから5分、各16件まで。

applyはdocumentId/modelId/planId/operationIdを検証し、独立したSDK authoringトランザクションで各段階の構造を照合します。native selector・権限・group-undo准入が別途必要です。transaction.executeにネストできず、legacy applyの資格も変えません。結果はreceipt、readbackVerified、rollbackStateVerified、createdGroups、actualAfterHashを含みます。予測hashは仮ID、実hashは取得済みIDを使用します。rollback照合は構造のみでbinding/form内容の復元検証ではありません。失敗/不明結果の作成IDの存続は保証しません。256操作IDをevictせず保持、result/同一リクエスト再生はホストを読みません。timeout後は結果を確認し、盲目的な再計画/再書き込みを避けてください。待機中断はnative commit取消ではありません。プロセス再起動後の復元や実機合格を主張しません。

### 新規 source/default メッシュ

`authoring.meshes.triangulate` は明示的 document/model、CANVAS positions/uvs/indices でnative分割をpreviewします。点の統合/並び替えがあり、結果UV対応は返さず推測もしません。モデル/選択を変更しません。

`authoring.meshes.create_plan` は1..8件の name、既存parentPartId、nullable modelImageId、sourceGeometry、別defaultPositionsを受け取ります。同じ点数/順序、UV[0,1]、非空/非重複/非退化面が必要です。1件4096点/24576indices、合計16384/65536、768KiB。親/祖先は可視かつunlocked。null画像は後続の承認済PNG登録向けの未bindingメッシュで、可視の証明ではありません。既存全meshのnative source/stored/GUID/親/lock/binding、Partの宣言フィールド、画像metadata/historyを保存。PartはID/fields/historyでありnative GUID CASではありません。上限512meshes/512Parts/128画像group/1024画像/262144点/4MiB encoded state、native割当は後段budget検査より先。16plans/5分TTL、約64MiB encoded stateにnative DTO overheadが加わります。planはwrite admissionを保証しません。

`authoring.meshes.create` はplanId/operationIdで独立SDK transaction内のbatch作成/任意画像bindingを行い、宣言状態と実ID/GUID/default form/source/default/UV/indices、既存対象保持をreadbackします。default draw order/nullable opacityは観測値で描画証明ではありません。`create_result`/同一再送はhost読み取りゼロ、256操作IDを削除せず保持し再起動復旧なし。createdMeshesは過去の観測で、verified=trueもrollback後の存在を保証しません。全体outcome/readbackVerified/rollbackStateVerifiedを確認。expectedAfterHashは要求hashです。unknown/commit後readbackwarning時は盲目的再作成禁止。取消はbridge admissionのみ、native commit取消やtransaction.execute入れ子は不可。実機描画/Undo/Redo/保存再開は未検証です。

### 文書IDと条件付き新規作成

`documents.read({})` はproject/文書がない場合も使用でき、pathなしのproject/document/content ID・名前・種類・関連とactive文書を返します。active modelのauthoring/history bindingは利用不能ならnull。pixels/native handleは返さず、全model geometryやnative document GUIDのCASではありません。宣言metadataを2回比較。128文書/128contents/256関連/192KiB、16session snapshots/5分TTL、native割当はbudget検査より先。完全asset一覧ではありません。

`documents.new_model({snapshotId,operationId})` はmetadata/active bindingを2回確認し、実際のavailableにNEW_MODELがある場合のみtyped命令を一度実行。native default/UIを使い、任意command/path/name/canvas指定なし。READYは旧文書metadata/orderとcontents metadataが保たれ、唯一の新model IDを持つactive MODELが1件増え、authoring/historyにアクセスできた場合のみ。全既存model内容/画布/pixels/render/保存の証明ではありません。EXECUTEDのみならEXECUTED_NOT_READY/isError=true、FAILED/応答紛失/post-state読取不能ならOUTCOME_UNKNOWN。UI操作後はdocuments.readで再発見し、新規作成の再実行をreadiness探査に使わないでください。

`documents.new_result`/同一再送はhost読取/再実行なし、128操作IDを削除せず保持、再起動復旧なし。後続UI変化のlive pollではありません。取消はbridge admissionまで、native取消/Undo/transaction入れ子なし。OPEN は以下の限定 dispatch 契約を使用し、first-open readiness は再発見が必要です。実機新規作成は未検証です。

### 通常パラメータグリッドの初期化

`authoring.bindings.initialize_plan` はdocumentId/modelId、kind（art_mesh/warp_deformer/rotation_deformer）、objectId、発行済みauthoringSnapshotId、1..3軸のparameterId/parameterGuid/keysを受け取ります。ゼロ軸・単一cell/formのみ対象です。通常・非repeat/Combined・現在defaultのパラメータに限定し、各軸3..9個の有限で異なるキーに正確なdefaultと範囲内の両側値を含めます。表示ID/GUID重複は拒否。上限は27 Cartesian cells、4096頂点/制御点、32768保存サンプル、512パラメータと240KiBメタデータ、初期状態4MiB、16計画/合計16MiB/5分TTL。元snapshotの期限は延長しません。pose/選択を変更せず、書込み准入は未証明です。

`authoring.bindings.initialize({documentId,modelId,planId,operationId,target})` は返された`plan.target`をそのまま送信し、権限UIに対象・source・パラメータ・キー範囲を表示します。専用SDK transactionで完全なsource/親/lock/editLevel/historyと全パラメータGUID/定義/値を確認し、架空point IDなしでbindValuesを呼びます。各軸後の実際のcell/formは完全Cartesian・独立GUIDで、default tupleを含む全form内容が元のゼロ軸formと一致する必要があります。mesh位置/drawOrder/nullable opacity、Warp制御点、Rotation全値、source geometry/grid/baseAngleを保護し、不完全/共有/余分なcellや変更内容はrollbackします。元form GUIDの配置は推定しません。Runtimeの正確なhost/階層/extension/権限/Undo准入が最終判断です。native割当てはMCP予算確認に先行する場合があり、実host検証ではありません。

最終readback成功時のみ実際のform GUID tupleを返します。rig編集前にauthoring snapshotとbinding resourceを再読込してください。tupleは書込みsnapshot tokenではありません。hashはdocument/model/object/source identityと保存投影に限定されます。`initialize_result`と同一要求replayはhostを読みません。最大128操作、破棄や再起動復旧なし。missing/unknown/warningを盲目的に再試行しないでください。cancelはbridge准入前のみでnative cancelやtransaction.execute入れ子には非対応です。画像・品質・実Undo/Redo・保存再読込は別途検証が必要です。


### 素材ソースとレイヤー関係メタデータ

`turboism.assets.sources.read` はdocumentId/modelIdと `query:{artMeshIds:[],modelImageIds:[]}`（各種類64個までの一意ID）を受け取ります。空queryはbinding情報のみです。`turboism.assets.relations.read` は制限付き全関係投影を読み、queryは受け取りません。typed texture metadataのみを読み、PSDを開かず、pixel/path/書込みにアクセスしません。未発見anchorはunresolved_anchor。currentRawImageIdとlinkedRawImageIds候補を区別し、現在metadataが欠けても候補で補完しません。rawLayerIdはrawImageId内のIDで、nullable数値psdLayerIdとは別です。usingArtMeshIdsは非active入力も含み、nullable currentInputIndexや空inputsは未bindingの証明ではありません。UNKNOWNとtransform/clipping可用性を保持し、行列/実際のclipping値/左右/顔パーツを推測しません。

初回はsnapshotIdなし、offset=0。続きは同session/tool/query/snapshotIdで取得します。limitは既定128、1..256、page240KiBで行数を縮小する場合があります。2回のtyped読取りで投影/native binding/generationを照合し、native observation revisionは増加を許容、減少は拒否します。これは観測番号で、history revisionや書込みCASではありません。cacheは不変の履歴metadataで、後続pageはactive document/model/historyのみ検査し、native関係を再読せず、最新性/全モデル原子性を保証しません。更新しない5分TTL、16件/合計16MiB、完全encoding各4MiB、1024 raw images、2048 model images/meshes、256 groups、8192 layers/depth64、16384 links、32768 rows、各row192KiB。native DTO割当はMCP予算検査より先です。書込みtoken、file grant、visual acceptanceには使用できません。


### 普通キーフォームの区間傾き診断

`turboism.validation.keyform_continuity` は同sessionのauthoring snapshotId、documentId/modelId、明示kind/objectId、thresholds（maximumPositionSlopeChange/maximumAngleSlopeChange/maximumScaleSlopeChange、有限0..1e12）、maxFindings1..200を受け取ります。1..3軸、各軸3..9個の異なる範囲内key、完全Cartesian gridに限定し、最大27 cells/forms、各form4096点、32768保存サンプルです。GUIDと実際のkey値で順序を決め、他軸の完全tupleを固定して各軸線の全隣接3点を検査。欠落/重複tuple、孤立form、未対応/予算超過はverdictなしで失敗。default/shared ordinary formは読めますがArtMesh Morphは拒否し、Warp/Rotation snapshotはMorph組合せを表しません。

区間傾きは保存値の差÷（key間隔÷パラメータ全範囲）、右傾き−左傾きを比較します。Mesh頂点/Warp制御点/Rotation原点はEuclidean norm、angleは最短角度補正なしのnative度数、scaleは生のscalar、reflection変化は常に警告。thresholdは呼出側の明示値で美術既定値ではありません。実際のaxes/ranges/keys、3つのform GUID、完全center tuple、key値、pointIndexまたはRotation component、observed/thresholdを返します。非適用angle/scale最大値はnull。全検査後のみanalysisComplete=true、maxFindingsは保持件数だけを制限しfindingsTruncatedを明示します。

任意proposedEditsは閉じたpositions/controlPoints/完全Rotation formとGUID座標を受け、ordinary writerのreadonly preflightを再利用。default/sharedへの編集は拒否しcommitしません。前後でnative全snapshot/親/historyを照合し、pose/選択/描画/書込みなし。request768KiB/result240KiB、native読取り割当は予算検査より先です。意図した曲率/reflectionも警告し得ます。no findingsはこの保存傾き検査だけの結果で、native C0/C1連続性、key間幾何安全、混合微分、drawOrder/opacity、親変形やvisual acceptanceの証明ではありません。


### 既存rig幾何の明示的な移行計画

`turboism.rig.transfer_geometry.plan` は同種類の異なる2 objectのsource/target authoring snapshotからtargetのみのkeyforms.writeリクエストを生成します。documentId/modelId/operationId、kind、policy=REPLACE_FROM_SOURCE_DEFAULT_DELTAS、source/target各objectId/snapshotId、全sourceParameterGuid→targetParameterGuidと完全sourceValue→targetValue key対応を明示。defaultは正確にdefaultへ対応し、表示ID/名前/nearest keyで推測しません。既存の完全1..3軸Cartesian grid、各軸2..65 keys、各object65 cells/formsまで、孤立form不可。ArtMesh Morphは拒否、Warp/Rotation snapshotはMorph組合せを表しません。sourceのshared ordinary formは読めますがtargetのdefault/shared writer保護は維持します。

Mesh/Warpは同点数と完全な一意permutation sourcePointIndices[targetIndex]=sourceIndex、可逆deltaTransform={xx,xy,yx,yy}（有限係数±1e6）を必須とし、targetDefault+matrix*(sourceForm-sourceDefault)を計画。target default/source/UV/topology/binding/drawOrder/opacityを保持し、親座標や頂点意味対応を自動判定しません。Rotationは点permutationなしでangleGain（±1000）、reflectionPolicy=RELATIVE_XORを必須とします。raw angle差をgain倍、origin差をmatrix変換、scaleはtargetDefault×sourceForm/sourceDefault、反射は3つのbitのXORです。分量移行であり回転/行列の合成ではなく、angleGainをmatrixから推論しません。

全nondefault target値を置換し、既存rigとの無損失mergeやbinding作成は行いません。前後で両native全snapshot/identity/親/historyを照合しstate/default IDを返します。sourceReadScope=VERIFIED_AT_PLAN_ONLYで、後のwrite CASはtargetだけを保護しsource変更は追跡しません。各form4096点、合計262144保存sample、4096計画sample（Rotationはform/originを1件と数える）、request768KiB/output240KiB、native割当は検査より先。読取りのみ、operation予約/snapshot無効化/transaction/write/pose/選択/renderなし。別途preview/承認/実際のwrite准入と幾何/描画確認が必要で、完全rig copyや美術acceptanceではありません。

### 許可付き CMO3 OPEN

`documents.open({operationId,grantId,documentSnapshotId})` は同セッションの READ 許可（拡張子 `["cmo3"]`）と `documents.read` の有効な snapshot を要求します。許可消費前と native dispatch 前に全文書一覧・authoring 状態・TTL・容量を検証します。model read/write と file read 権限が必要で、exact 5.3.03 のみ准入します。パス・handle・PSD・close・overwrite は受け付けません。

`EXECUTED` / `NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS` は native 呼出の復帰のみで、既存タブの focus を含みます。`documents.read` で再発見してください。first-open readiness、ファイル identity、永続化、reopen の証明ではありません。不確定時は `documents.file_result` を照会し、盲目的に再実行しないでください。OPEN/SAVE_AS は session 内 operation identity を共有し、同一再送は再分派しません。Undo/native cancel の保証はありません。

`documents.read.state.hostObserved=false` は project/document がない状態を含み、Editor command service が利用不能とは限りません。OPEN は現在のsnapshot、command service、exact Runtime版・権限・grantを引き続き検証します。
