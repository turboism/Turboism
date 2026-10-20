---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.acp
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: integration
tags: automation, acp, agent
turboismApi: "[0.67.1,1.0.0)"
requiresCubism: true
interface: swing
---

# Turboism ACP

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

Agent 会話ウィンドウと Agent 設定ウィンドウを開き、ユーザーがインストールした ACP v1 対応エージェントを Agent Client Protocol 経由で駆動し、認証情報を持たない stdio ブリッジでローカル Turboism MCP サーバーへ接続します。

## 機能概要

- **Agent**（会話）と **Agent 設定** の 2 つの Swing ウィンドウを開きます。
- stdin/stdout JSON-RPC でユーザーインストールの ACP エージェントを起動します：Claude Agent ACP、Codex ACP、Antigravity、Gemini CLI、OpenCode、Pi、Devin CLI、またはカスタムコマンド。
- ACP v1 契約に従い `initialize`、`authenticate`、`session/new`、`session/load`、`session/resume`、`session/prompt`、`session/cancel`、`session/close`、`session/list`、`session/update`、`session/request_permission`、`session/set_config_option`、`logout` を駆動します。
- Turboism MCP サーバーを ACP stdio MCP サーバーとして接続し、エージェントが Cubism ツールを利用できるようにします。HTTP エンドポイントは HTTP MCP サポートのみを宣言するエージェント向けの読み取り専用フォールバックとして残ります。
- PATH および一般的なインストール先でエージェント実行ファイルを検出し、少量のプラグイン保有状態（選択エージェント、カスタムコマンド、永続セッション ID、初期指示）を保存します。

## 要件と互換性

- Turboism SDK API 範囲 `[0.67.1,1.0.0)`。
- ユーザーがインストールした ACP v1 対応エージェント実行ファイル。PATH 上にあるか、**Agent を管理…** でカスタムコマンドとして設定します。
- MCP ツールを使う場合は、同梱の `dev.turboism.plugin.mcp` プラグインが Turboism MCP サーバーを提供します。
- インストール済みの Cubism Editor ホストが必要です。スタンドアロンプレビュー内では動作しません。

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全レビュー済みバージョン.
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->

## インストールと有効化

- このプラグインは Turboism に同梱され、既定で有効です。
- 入力欄で Agent を選択します。カスタムコマンドは **Agent を管理…** から設定します。

## 使い方

1. メインツールバーの会話／ターミナルアイコン、または Turboism メニューから Agent を開きます。
2. 入力欄で Agent を選ぶと接続します。メニューの **Agent を管理…** から実行ファイル検出、ログイン／ログアウト、カスタムコマンド、再接続を行えます。
3. 入力欄でモデル、思考の強度、権限、対応するモードを選びます。
4. **＋** でファイルや画像を選択するか、Ctrl/Cmd+V でクリップボードの画像やファイルを貼り付けます。通常のテキストのコピー、切り取り、貼り付けも維持します。添付は送信前に削除でき、添付だけの送信も可能です。
5. 歯車ボタンは初期プロンプトと保存ボタンだけの設定画面を開きます。

### 画像とファイル

- ACP の画像ブロック、画像リソース、ツールが返す画像をサムネイル表示します。単独行の Markdown 画像参照も、ローカルの絶対パス、ACP セッションディレクトリからの相対パス、`file:` URI に対応します。リモート URL は自動取得しません。読み込めない参照はテキストのまま残します。
- 添付は最大8件、合計4 MiB。プレビューの元画像は2000万画素までで、560×320以内に縮小します。読み込みと画像デコードはバックグラウンドで実行します。
- ACP `promptCapabilities` に応じて画像、埋め込みリソース、ローカルリソースリンクを使い分けます。クリップボード画像には画像または `embeddedContext` の対応が必要です。失敗時は下書きを保持します。
- 添付はローカルの Agent に渡され、Agent の設定に応じてリモートプロバイダーへ送信される場合があります。プラグイン設定には保存しません。

### MCP 接続方式

- MCP サーバーが stdio 起動記述子を公開している場合、セッションは `{name:"turboism", command, args, env:[]}` で接続し、ブリッジプロセス自身が永続化された bearer token を付与します。エージェントは読み取り・書き込みツールを利用できます。
- HTTP エンドポイントのみ利用可能で、エージェントが HTTP MCP サポートを宣言している場合、セッションはエンドポイントを読み取り専用として接続します。変更系の呼び出しは引き続き bearer token でゲートされ、その token が MCP 状態ディレクトリの外に出ることはありません。
- どちらの形式も利用できない場合、セッションは MCP ツールなしで実行されます。

## 機能

| 機能 | ユーザーへの効果 |
|---|---|
| `automation.agent.acp` | Agent Client Protocol v1 経由でユーザーインストールの ACP 対応エージェントへ接続します。 |
| `mcp.client` | 認証情報を持たない stdio ブリッジでローカル Turboism MCP サーバーをエージェントセッションへ接続し、HTTP エンドポイントを読み取り専用フォールバックとして使います。 |
| `ui.window` | Agent 会話ウィンドウと Agent 設定ウィンドウを開きます。 |

## 権限

| 権限 | スコープ | 要求理由 |
|---|---|---|
| `turboism.action.register` | `application` | Agent ウィンドウを開くアクションを登録します。 |
| `turboism.ui.menu.contribute` | `application` | Turboism メニューに Agent 設定の項目を追加します。 |
| `turboism.ui.toolbar.main.contribute` | `application` | メインツールバーの Turboism Home の隣に Agent ボタンを追加します。 |
| `turboism.config.plugin.read` | `application` | 選択エージェント ID、カスタムコマンド、永続セッション ID、初期指示を復元します。 |
| `turboism.config.plugin.write` | `application` | 上記のプラグイン保有状態を保存します。エージェント認証情報や MCP 認可は保存しません。 |
| `turboism.file.read` | `application` | PATH と一般的なインストール先でユーザーインストールのエージェント実行ファイルを検出します。 |
| `turboism.process.run` | `application` | 選択したエージェント実行ファイルを起動・管理します。 |
| `turboism.mcp.connection.read` | `application` | 認証情報を持たない stdio 起動記述子を含む現在の MCP 接続スナップショットを読み取り、エンドポイント変更を購読して接続済みセッションを再接続します。 |

## プライバシーとデータ

- プラグインは自身のスコープにプラグイン保有設定のみを保存します：選択エージェント、カスタムコマンド、永続セッション ID、初期指示。
- MCP bearer token は ACP ペイロード、コマンドライン引数、環境変数、UI テキスト、ログのいずれにも入りません。stdio ブリッジが MCP プラグイン状態ディレクトリ内でそれを読み取ります。
- プロンプトとトランスクリプトの内容はローカルに起動したエージェントプロセスにのみ送られます。

## 状態と制限

- プレビュープラグイン：インターフェースと永続状態の形式はリリース間で変更される場合があります。
- エージェント側機能（永続セッション、モデルセレクター、モード）は各エージェントが宣言する機能に依存します。

## トラブルシューティング

- **「Executable not found」で接続に失敗する**：**Agent を管理…** でカスタムコマンドを設定するか、検出対象のいずれかのエージェントをインストールして PATH 上に置いてください。
- **「エージェントがサインインを必要としています」**：**サインイン…** を押してエージェントの認証方式を完了し、再接続してください。
- **セッションで Cubism ツールが使えない**：Agent トランスクリプトに MCP が書き込み可能（stdio ブリッジ）、読み取り専用（HTTP）、未接続のいずれで接続されたか表示されます。`dev.turboism.plugin.mcp` プラグインが有効で起動しているか確認してください。
- **セッションを読み込めない**：保存された永続セッションが古い可能性があります。プラグインは自動で新規セッションへフォールバックします。

## サポートとライセンス

- 問題は Turboism のサポートチャンネルへ報告してください。
- Turboism と同じライセンスで配布されます。

## 入力欄のコントロール

- Agent セレクターには各 Agent のアイコンを表示します。カスタム Agent はターミナルアイコンを使用します。
- モデル、思考の強度、権限はアイコンなしのテキストセレクターです。選択肢は ACP `configOptions` に従います。
- 高速モード（稲妻）と計画モード（チェックリスト）はアイコンのみで表示し、ツールチップとアクセシブル名に現在値を含めます。接続した Agent が提供する項目のみを表示します。対応状況は Agent、アダプターのバージョン、モデル、セッションによって異なります。その他のモードはテキスト表示を維持します。
- 思考と連続したツール／コマンド呼び出しは、装飾アイコンなしの折りたたみ行です。

## UI の参考と謝辞

本プラグインのチャット UI は、コンパクトな入力欄、Agent アイコン、対応機能に応じたモード選択、思考／ツール履歴の折りたたみ表示について [Paseo](https://github.com/getpaseo/paseo) を参考にしています。Swing 実装は Turboism が保守しています。

素材の出典、上流リビジョン、変更内容、ライセンスは [Agent アイコンの謝辞](src/main/resources/META-INF/licenses/agent-icons.md) を参照してください。Paseo のライセンスと第三者に関する通知、および Lobe Icons の MIT ライセンスを同梱しています。Agent の名称とロゴは各権利者に帰属します。本プラグインは Paseo や各提供元と提携しておらず、承認を受けたものでもありません。

### 初期プロンプトと参考資料

初期プロンプト欄の役割説明はプレースホルダーであり、ユーザーの編集内容として保存されません。各リクエストには同梱の[モデリング参考情報](src/main/resources/context/modeling-reference.md)を注入します。内容は作業の概要、Live2D 公式チュートリアル／マニュアル、Turboism ドキュメントへの確認済みリンクです。サイト全文のコピーではなく、オフラインの要約と索引です。ユーザーの初期指示は固定コンテキストの後に追加します。

機能アイコンには Lucide v0.468.0 を統一使用し、Agent のブランドマークは維持します。添付画像はトリミングしたサムネイル、ファイルは中央省略した名前と種類を表示し、完全な名前は削除ツールチップで確認できます。
