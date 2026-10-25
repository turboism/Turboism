---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.command-palette
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: launcher, commands, shortcut
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---
# ì»¤ë§¨ë íë í¸

> **Turboism 公式プラグイン** · **ステータス: プレビュー**

**Ctrl+K** で起動するコマンドランチャーです。Editor の対応メニューコマンドおよび他プラグインが登録したアクションを ID またはローカライズ名で検索して実行できます。

| 세부 정보 | 값 |
|---|---|
| 버전 | `0.1.0` |
| 플러그인 ID | `dev.turboism.plugin.command-palette` |
| 카테고리 | `workflow` |
| 태그 | launcher, commands, shortcut |
| Cubism 必須 | 예 |
| 인터페이스 | `swing` |
| 라이선스 | 프로젝트 라이선스 |

## 기능 개요

- **Ctrl+K** でコマンドパレットを開きます（**Turboism → コマンドパレット** からも開けます）。
- ホストが現在許可しているすべての Editor コマンドと、他の有効なプラグインが登録したアクションを表示名順で一覧表示します。
- 入力に応じて部分列（サブシークエンス）一致で絞り込みます。表示名と安定 ID（例：`new.model` や `perf-stats.window.show`）の両方が検索対象です。
- 各結果行で一致した文字を緑色でハイライト表示します。
- 最上位候補に対してグレーのインライン補完ヒントを表示し、**Tab** で確定できます。
- **Enter** で選択中のコマンドまたはアクションを実行してパレットを閉じます。候補リストが空でも完全な ID を入力して **Enter** を押せば実行できます。
- **Up/Down** で選択移動、**Esc** またはフォーカス外クリックで何も実行せずに閉じます。

## 요구 사항 및 호환성

- **SDK API 範囲:** `[0.67.0,1.0.0)`。
- **Cubism:** Cubism が必要です。レビュー済みの Editor（`5.2.03`、`5.3.02`、`5.3.03` など）をサポートし、宣言されたサービスと機能が利用可能な場合にのみ各機能を提供します。
- **インターフェースモード:** `swing`。
- **プラグイン依存関係:** なし。

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04（이전 Cubism 버전에는 기능 자체가 없음）.
전체 기능(검토된 버전): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- 상단 메뉴 통합 제외 5.0.00–5.0.06（이전 Cubism 버전에는 기능 자체가 없음）
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

Turboism 公式リリースパッケージに同梱されています。インストーラーで選択するか、**プラグイン管理** から有効化・無効化できます。

## 사용 방법

1. **Ctrl+K** を押します（または **Turboism → コマンドパレット** を選択します）。
2. `undo`、`new model`、`new.model` のようにコマンド名や ID の一部を入力します。
3. **Tab** で補完ヒントを受け入れるか、**Up/Down** で候補行を選択します。
4. **Enter** で実行、**Esc** でパレットを閉じます。

## 기능

| 宣言された機能 | ユーザーへの効果 |
|---|---|
| `cubism.editor-commands.execute` | パレットで選択された Editor コマンドをホストのメニューシステム経由で実行します。 |
| `action.catalog.invoke` | 他プラグインの登録アクションを一覧表示し、選択されたアクションを呼び出します。 |

## 권한

| 宣言された権限 | スコープ | ユーザーへの効果 |
|---|---|---|
| `turboism.action.register` | `application` | コマンドパレットを開くアクションと既定の Ctrl+K ショートカットを登録します。 |
| `turboism.action.invoke` | `application` | 他プラグインの登録アクションをパレット候補として列挙・実行します。 |
| `turboism.ui.menu.contribute` | `application` | Turboism トップメニューにコマンドパレット項目を追加します。 |
| `turboism.cubism.model.read` | `application` | パレットで選択された読み取り系 Editor コマンドを実行します。 |
| `turboism.cubism.model.write` | `application` | パレットで選択された書き込み系 Editor コマンドを実行します。 |
| `turboism.file.write` | `application` | SAVE などのファイル保存系 Editor コマンドをパレットから実行できるようにします。 |
| `turboism.network.fetch` | `application` | Live2D ウェブページを開く Editor コマンドをパレットから起動できるようにします。 |
| `turboism.process.run` | `application` | ローカルフォルダーを開く Editor コマンドをパレットから起動できるようにします。 |

## 개인정보 및 데이터

### 네트워크

プラグイン自身はネットワーク接続を行いません。Live2D ウェブページを開くコマンドはホスト Editor に処理を委譲します。

### 로컬 데이터

データは永続化されません。検索クエリと選択状態はパレットが開いている間のみ保持されます。

### 원격 측정ー

テレメトリーは送信しません。

プラグインのライフサイクルやエラー記録は、プラグイン ID 付きで Turboism セッションログおよび Cubism ホストログに記録される場合があります。

## 상태 및 제한 사항

- **ステータス:** プレビュー。
- `EditorCommandService` が公開する引数なし Editor コマンドおよび `ActionCatalogService` が公開するプラグインアクションのみを表示します。ファイルパスやパラメータを伴うコマンドは対象外です。
- 現在の状態でホストが利用可能と報告し、かつ権限が満たされているコマンドのみが表示されます。自身のアクションおよび内部 `turboism.core` アクションは除外されます。
- ユーザー自身の Ctrl+K キーバインド設定がある場合はそちらが優先されます。

## 문제 해결

| 증상 | 확인 사항 |
|---|---|
| Ctrl+K を押しても反応しない | プラグインが有効であるか、他のキーバインドが Ctrl+K を上書きしていないか確認してください。 |
| コマンドが一覧に表示されない | 現在の状態ではホスト側で利用不可となっているか、必要な権限が不足しています。 |
| プラグインアクションが表示されない | 所有プラグインが無効であるか、アクションを登録していません。内部 `turboism.core` アクションは非表示です。 |
| Enter を押しても実行されない | ホストログを確認してください。拒否または失敗時には結果ステータスが記録されます。 |

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

- **プロジェクトウェブサイト:** [https://turboism.dev](https://turboism.dev)
- **게시자:** Turboism Contributors
- **라이선스:** 프로젝트 라이선스
- **플러그인 ID:** `dev.turboism.plugin.command-palette`
