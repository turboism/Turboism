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

Agent と Settings の 2 つのウィンドウを開き、ユーザーがインストールした ACP 対応エージェントを Agent Client Protocol (ACP) v1 経由で認証済み Turboism MCP サーバーへ接続します。

| 項目 | 値 |
|---|---|
| プラグイン ID | `dev.turboism.plugin.acp` |
| 表示名 | Turboism ACP |
| カテゴリ | 統合 |
| タグ | automation, acp, agent |
| 配布 | 開発ビルドのみ — リリースパッケージには同梱されません |

## 機能

- `PATH` またはカスタムコマンドからユーザーインストール済みのエージェント実行ファイルを検出・起動し、シェルを介さず監視付き stdio で ACP v1 を話します。
- エージェントランタイムは同梱しません。Turboism はサードパーティのエージェントバイナリをダウンロード・同梱・検証・管理しません。
- HTTP MCP 対応を宣言したエージェントへ現在の認証済み Turboism MCP エンドポイントを渡し、型付き Cubism 自動化ツールを呼び出せます。
- 永続セッションのサイドバー、色分けされた有界ライブトランスクリプト、IME 対応のプロンプト送信、キャンセル、権限確認、ACP 駆動の認証を備えた専用 Agent 会話ウィンドウを提供します。
- エージェントが対応するセッションケイパビリティを宣言した場合、永続セッションの一覧・作成・読み込み・再開・終了を行います。

## エージェント選択

Settings → Agent で内蔵カタログまたはカスタムコマンドを選びます。

| エージェント | 実行ファイル | ACP 起動 |
|---|---|---|
| Claude Agent (ACP) | `claude-agent-acp` | 直接 |
| Codex (ACP) | `codex-acp` | 直接 |
| Google Antigravity | `agy-acp` | 直接 |
| Gemini CLI | `gemini` | `--experimental-acp` |
| OpenCode | `opencode` | `acp` サブコマンド |
| Pi (ACP) | `pi-acp` | 直接 |
| Devin CLI | `devin` | `acp` サブコマンド |
| カスタムコマンド | ユーザー argv | 入力どおり |

**検出**は `PATH` と一般的なユーザー別インストールディレクトリを探します。エージェントの認証・プロバイダ・モデルはエージェント自身が管理します。ACP `authMethods` を公開するエージェントは Settings ページからサインインでき、端末ログインが必要なエージェントは **ログイン端末を開く** から起動します。Turboism はエージェントの資格情報を保存しません。

## 実行時とセキュリティモデル

- **プロセス境界:** 選択されたエージェントは Turboism が監視する子プロセスとして動作し、終了時にプロセスツリーごと停止します。
- **MCP 接続:** エージェントが HTTP MCP を宣言した場合のみ、認証済み loopback MCP エンドポイントをセッションへ注入します。URL にトークンは含まれず、信頼境界はユーザーがインストールして選んだエージェントバイナリです。
- **権限:** すべてのエージェントツール呼び出しは ACP `session/request_permission` ダイアログで事前に確認されます。
- **常時指示:** 固定の境界プロンプトはエージェントへ Turboism MCP ツールのみ使うよう指示しますが、これは助言であり隔離境界ではありません。ネイティブのファイル/端末ツールを持つエージェントは各エージェント自身の設定で管理されます。

## 使い方

1. 対応エージェントを 1 つインストールし、端末ログインが必要なら各 CLI でサインインします。
2. プラグイン管理で **Turboism MCP Server** と **Turboism ACP** を有効化します。
3. **Turboism → ACP Settings** を開き、エージェントを選ぶかカスタムコマンドを入力して、**検出** またはサインインを実行します。
4. メインツールバーの **ACP** アイコンで Agent ウィンドウを開き、セッションを開始します。

## 付与される権限

| 権限 | スコープ | 目的 |
|---|---|---|
| `turboism.action.register` | application | Agent ウィンドウと Settings を開くアクションを登録します。 |
| `turboism.ui.menu.contribute` | application | Turboism メニューへ **ACP Settings** を追加します。 |
| `turboism.ui.toolbar.main.contribute` | application | メインツールバーの Turboism Home の隣へ ACP Agent アイコンを追加します。 |
| `turboism.config.plugin.read` | application | 選択済みエージェント ID、カスタムコマンド、永続セッション ID、初期指示を復元します。 |
| `turboism.config.plugin.write` | application | その Turboism 管理の状態を保存します。エージェント資格情報やトランスクリプトは保存しません。 |
| `turboism.file.read` | application | PATH と一般的なユーザー別インストールディレクトリにあるエージェント実行ファイルを検出します。 |
| `turboism.process.run` | application | 選択されたエージェント実行ファイルを起動・監視します。 |
| `turboism.mcp.connection.read` | application | 現在の認証済み MCP エンドポイントを読み取り、その変更を購読して ACP セッションへ接続します。 |

## 既知の制限

- ACP v1 のみ対応。プロトコル v2（`auth/login`、`session/resume` 相当）はまだ交渉しません。
- セッション中に MCP サーバーを無効化・再起動すると、プラグインがエンドポイント変更を検知して自動的に再接続し、新しいエンドポイントにバインドし直します。永続セッションは agent の load/resume 機能で復元されます（ACP は既存セッションの `mcpServers` を再バインドできません）。
- 実エージェントバイナリに対するホストレベル検証は、廃止された fx プローブに代わるスクリプト化 ACP エージェントフィクスチャが用意されるまで手動です。
