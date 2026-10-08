---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.acp
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: integration
tags: automation, acp, agent
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# Turboism ACP

> **Turboism 公式プラグイン** · **ステータス: 開発中 — 未公開**

Agent 会話ウィンドウと Agent 設定ウィンドウを開き、ユーザーがインストールした ACP v1 対応エージェントを Agent Client Protocol 経由で駆動し、認証情報を持たない stdio ブリッジでローカル Turboism MCP サーバーへ接続します。

## このプラグインの機能

- **Agent**（会話）と **Agent 設定** の 2 つの Swing ウィンドウを開きます。
- stdin/stdout JSON-RPC でユーザーインストールの ACP エージェントを起動します：Claude Agent ACP、Codex ACP、Antigravity、Gemini CLI、OpenCode、Pi、Devin CLI、またはカスタムコマンド。
- ACP v1 契約に従い `initialize`、`authenticate`、`session/new`、`session/load`、`session/resume`、`session/prompt`、`session/cancel`、`session/close`、`session/list`、`session/update`、`session/request_permission`、`session/set_config_option`、`logout` を駆動します。
- Turboism MCP サーバーを ACP stdio MCP サーバーとして接続し、エージェントが Cubism ツールを利用できるようにします。HTTP エンドポイントは HTTP MCP サポートのみを宣言するエージェント向けの読み取り専用フォールバックとして残ります。
- PATH および一般的なインストール先でエージェント実行ファイルを検出し、少量のプラグイン保有状態（選択エージェント、カスタムコマンド、永続セッション ID、初期指示）を保存します。

## 要件と互換性

- Turboism SDK API 範囲 `[0.1.0,0.2.0)`。
- ユーザーがインストールした ACP v1 対応エージェント実行ファイル。PATH 上にあるか、**Agent 設定** でカスタムコマンドとして設定します。
- MCP ツールを使う場合は、同梱の `dev.turboism.plugin.mcp` プラグインが Turboism MCP サーバーを提供します。
- インストール済みの Cubism Editor ホストが必要です。スタンドアロンプレビュー内では動作しません。

## インストールと有効化

- このプラグインは Turboism に同梱され、既定で有効です。
- **Agent 設定** を開いてエージェントを選択するか、カスタムコマンドを入力してください。

## 使用方法

1. メインツールバーの ACP Agent アイコンをクリックするか、Turboism メニューから Agent ウィンドウを開きます。
2. **Agent 設定** でエージェントを選択するか、カスタムコマンドを入力して保存します。
3. **接続** を押します。エージェントが認証を要求する場合は **サインイン…** を押して宣言された方式を完了します。
4. 会話フィールドにプロンプトを入力します。エージェントは Turboism MCP ツールを呼び出せます。権限プロンプトはウィンドウ内のダイアログとして表示されます。
5. エージェントが設定オプションを宣言している場合は、セッションオプションタブで provider、model、mode を切り替えます。

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

## 制限とステータス

- 開発中プラグイン：インターフェースと永続状態の形式はリリース間で変更される場合があります。
- エージェント側機能（永続セッション、モデルセレクター、モード）は各エージェントが宣言する機能に依存します。

## トラブルシューティング

- **「Executable not found」で接続に失敗する**：**Agent 設定** でカスタムコマンドを設定するか、検出対象のいずれかのエージェントをインストールして PATH 上に置いてください。
- **「エージェントがサインインを必要としています」**：**サインイン…** を押してエージェントの認証方式を完了し、再接続してください。
- **セッションで Cubism ツールが使えない**：Agent トランスクリプトに MCP が書き込み可能（stdio ブリッジ）、読み取り専用（HTTP）、未接続のいずれで接続されたか表示されます。`dev.turboism.plugin.mcp` プラグインが有効で起動しているか確認してください。
- **セッションを読み込めない**：保存された永続セッションが古い可能性があります。プラグインは自動で新規セッションへフォールバックします。

## サポートとライセンス

- 問題は Turboism のサポートチャンネルへ報告してください。
- Turboism と同じライセンスで配布されます。
