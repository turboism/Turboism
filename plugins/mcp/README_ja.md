---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.mcp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: mcp, automation, external-tools
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Turboism MCP サーバー

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

ローカルのループバックインターフェース上で、Bearer トークンにより保護された MCP Streamable HTTP サーバーを実行します。

| 詳細 | 値 |
|---|---|
| バージョン | `0.1.0` |
| プラグイン ID | `dev.turboism.plugin.mcp` |
| 公開カタログ | 5 ツール · 13 リソース · 2 テンプレート · 8 プロンプト |
| Turboism API | `[0.1.0,0.2.0)` |
| Cubism が必要 | はい |
| インターフェース | `none` |

## 機能概要

- 型付きのドメインレベルのモデルオブジェクト、パラメーター、バインディング、履歴、Editor コマンドツールを公開します。
- アクティブなドキュメント、モデル、ワークスペース、Cubism Core、およびサニタイズ済みランタイム診断を JSON リソースとして公開します。
- 検査、診断、編集、復旧、および範囲を限定した Editor 自動化のためのワークフロープロンプトを提供します。
- 認証済みのループバッククライアントのみを処理し、オリジン、本文サイズ、プロトコル、セッション、レートの制限を適用します。

## 要件と互換性

- **Turboism API:** `[0.1.0,0.2.0)`。
- **Cubism:** Turboism は現在、レビュー済みの正確な Editor アーティファクト `5.2.03`、`5.3.02`、`5.3.03` を受け入れます。ホスト向けリソースは、その公開 SDK 機能を利用できないとき、クローズドに失敗します。空の成功結果を捏造することはありません。
- **トランスポート:** プロトコル `2025-11-25` を使用する MCP Streamable HTTP。サポート対象の以前のプロトコルバージョンについては互換性ネゴシエーションを行います。
- **インターフェースモード:** `none`。
- **プラグイン依存関係:** 宣言されていません。

## インストールと有効化

1. Turboism の公式リリースパッケージおよび **Plugin Management** を通じてプラグインをインストールし、有効化します。
2. ユーザー単位のプラグイン状態ディレクトリから、生成された `mcp-connection.json` を読み取ります。Java のファイルシステムプロバイダーが POSIX または ACL 制御を公開する場合、Turboism は所有者専用権限を適用します。Windows の DOS プロバイダーでは、ユーザー単位ディレクトリ、所有者、通常ファイル、リパースポイントの検査を維持します。
3. 正確な数値ループバックエンドポイントと Bearer 認可値を使って、ローカル MCP クライアントを設定します。
4. `initialize` を完了し、`MCP-Session-Id` を保持して `notifications/initialized` を送信し、以降のリクエストにはネゴシエート済みのプロトコルバージョンを含めます。

接続ファイルにはローカルシークレットが含まれます。ログに記録したり、検証証跡にコピーしたり、信頼できないプロセスに公開したりしないでください。Turboism はトランスポートをループバックへバインドし、安全でない接続ファイルパスを拒否し、所有者とファイル種別を検証し、シンボリックリンクを追跡せず、プラットフォームが対応する場合は所有者専用権限を適用します。

## 使い方

### 公開 MCP カタログ

#### ツール

| ツール | 目的 |
|---|---|
| `turboism.model_objects.apply` | 順序付けられた作成、名前変更、再親子付け、削除操作を適用します。 |
| `turboism.parameters.apply` | 型付きパラメーター値および定義操作を適用します。 |
| `turboism.parameter_bindings.apply` | 型付きパラメーターバインディング操作とネイティブのアトミック転送を適用します。 |
| `turboism.history.move` | 世代/リビジョンガードを使ってネイティブ Undo 履歴を移動します。 |
| `turboism.editor_commands.execute` | 検出可能な直接 Editor コマンドと型付き非ファイル Editor コマンドを実行します。 |

書き込みはランタイム権限および引数チェック後に実行されます。混在バッチは部分的に成功し、操作ごとの結果を報告することがあります。基盤となる SDK バッチがアトミックでない限り、トランザクションとして提示されません。

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
| `turboism://active/document/history` | ネイティブ Undo の可用性、項目、世代、リビジョン、位置。 |
| `turboism://environment/cubism-core` | 受け入れられた Cubism Core バージョンと公開機能フラグ。 |
| `turboism://environment/workspace` | 現在および利用可能なワークスペースと型付き可用性。 |
| `turboism://environment/workspace/layout` | 型付き可用性を備えた順序付き読み取り専用ドックレイアウトツリー。 |
| `turboism://environment/diagnostics` | 範囲が限定され、パスが伏せられた Turboism 診断問題。 |
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
| `mcp.streamable-http` | 数値ループバック上で認証済みの MCP Streamable HTTP を提供します。 |
| `mcp.tools` | 5 つの型付きツールワークフローを公開します。 |
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
| `turboism.cubism.parameter.read` | `application` | アクティブな Cubism モデルパラメーターを読み取ります。 |
| `turboism.cubism.project.read` | `application` | アクティブなプロジェクト、ワークスペース、レイアウト、テーマ状態を読み取ります。 |
| `turboism.cubism.model.write` | `application` | 型付きのモデル、パラメーター、バインディング、履歴、モデル設定の書き込みを適用します。 |
| `turboism.file.write` | `application` | 直接 Editor `SAVE` コマンドを許可します。 |
| `turboism.network.fetch` | `application` | 型付きの外部アプリケーション設定コマンドを許可します。 |
| `turboism.process.run` | `application` | 型付きの外部アプリケーション設定コマンドを許可します。 |
| `turboism.mcp.connection.publish` | `application` | 権限が承認された自動化プラグインに、認証済みループバックエンドポイントをプロセス内ランタイム交換経由で公開します。 |

診断拡張によって `host.unsafe`、パフォーマンス、ファイル読み取り、設定、イベント、UI 変更の権限が追加されることはありません。

## プライバシーとデータ

### ネットワーク

サーバーは `127.0.0.1` でのみ待ち受けます。すべてのリクエストには、生成または設定された Bearer トークン、受け入れられるループバックオリジン、1 MiB 以下の本文、および設定済みのレート制限が必要です。リモートアクセス用には設計されていません。

### ローカルデータ

プラグインは、プラグイン状態ストレージに接続メタデータのみを書き込みます。POSIX システムでは、所有者のみの権限を設定しようとします。診断およびモデルリソースは、生のファイルシステムパス、ネイティブホストオブジェクト、画像バイト、Bearer トークンを公開しません。

`turboism://environment/diagnostics` は `DiagnosticReport.Problem.path()` を省略し、問題リストを制限し、メッセージを 1 行に変換し、メッセージ長を上限設定し、Unix パス、Windows パス、`file:` URI を伏せます。

### テレメトリー

このプラグインからテレメトリーが送信されることはありません。プラグイン ID を付して、プラグインのライフサイクルおよび障害の記録が Turboism のセッションログと Cubism のホストログに表示される場合があります。

## 状態と制限

- **ステータス:** プレビュー。
- デフォルトのポート `0` はエフェメラルポートを選択します。`turboism.mcp.port`、`turboism.mcp.token`、`turboism.mcp.requestsPerMinute` は高度なシステムプロパティのオーバーライドです。
- GET SSE、リソースサブスクリプション、リスト変更通知、進行状況通知、MCP Tasks は実装されていません。
- ワークスペース切り替えおよびデフォルトレイアウトの変更は、ランタイムが現在 `turboism.host.unsafe` でそれらをゲートしているため、意図的に利用不可です。
- MCP セッションが、生のパスを受け取らずに実際の `UserFileHandle` 認可を受け取れるようになるまで、`EditorFileCommandRequest`、インポート/エクスポート、別名保存、バックアップ、その他のハンドルベースのファイルワークフローは利用できないままです。
- 汎用 SDK 呼び出し、リフレクション、任意のネイティブメンバー、シェル実行、生のパス、ダイアログ自動化、ライフサイクル登録 API は公開されません。
- パフォーマンスサンプリング、キャンバス/プロファイル、物理/アニメーション、テクスチャアトラスオーサリング、スクリーンショット、バイナリリソースは、独自の権限および正確なホスト証跡要件を持つ、独立した将来の機能のままです。
- 削除は破壊的です。デフォルトでは参照されているオブジェクトを拒否します。カスケードは明示的に要求する必要があります。

## トラブルシューティング

| 症状 | 確認事項 |
|---|---|
| MCP クライアントが接続できない | 現在の接続ファイルを読み取り、プロセスが実行中であることを確認し、その正確なループバックエンドポイントを使用します。 |
| リクエストが認可されない | 現在のセッションの接続ファイルにある Bearer 認可値を使用します。 |
| リクエストがディスパッチ前に拒否される | メソッド、オリジン、セッション、MCP プロトコルバージョン、本文サイズ、コンテンツタイプ、レート制限を確認します。 |
| リソースが `UNAVAILABLE` を返す | アクティブなドキュメント/モデル状態と正確なホスト機能の受け入れを確認してください。空の成功値として扱わないでください。 |
| リソースが権限拒否を返す | プラグインディスクリプターの許可と、上記で指定した特定のランタイム権限を確認します。 |

## サポートとライセンス

- **プロジェクト Web サイト:** [https://turboism.dev](https://turboism.dev)
- **発行者:** Turboism Contributors
- **ライセンス:** Project License
- **プラグイン ID:** `dev.turboism.plugin.mcp`
