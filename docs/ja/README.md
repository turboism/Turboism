[English](../en/README.md) / [简体中文](../zh/README.md) / [日本語](../ja/README.md) / [한국어](../ko/README.md)

# Turboism

## 免責事項

Turboism は独立したサードパーティープロジェクトであり、Live2D Inc. との提携・推奨・スポンサー関係はなく、Cubism 公式製品ではありません（Live2D、Cubism および関連標章は Live2D Inc. または各権利者に帰属します）。

Turboism は Cubism Editor を配布せず、ライセンス認証の回避、制限機能の解除、保有ライセンスの範囲を超えた権限取得・利用を補助することは一切ありません。別途、正規ライセンスを取得した Cubism Editor が必要です。

本ソフトウェアは [AGPL-3.0-only](../../LICENSE) の下で**現状のまま**提供されます。導入前に [EULA](EULA.md) をご確認のうえ、プロジェクトのバックアップを各自で保管してください。

## 概要

Turboism は **Live2D Cubism Editor** 向けのランタイム拡張ツール兼プラグインフレームワーク（Windows 主軸）です。Java Agent と公開 SDK をベースに、パラメータ・メッシュ編集支援、PSD 補助、パレット拡張、ローカル自動化などの機能を追加します（利用可能な機能はプラグインと Editor バージョンに依存します）。

## 現在の機能

### フレームワーク

- **パフォーマンス・JVM チューニング**：描画、モデル更新、クリッピングマスク、テクスチャ転送、メッシュ分割などのオプトイン最適化に加え、Cubism 用 JVM 設定（ZGC / GraalVM 等）の切り替えや起動ダイアログ抑制に対応。
- **プラグイン基盤・マーケット**：内蔵のプラグイン管理と Ed25519 署名付きマーケットプレイス（導入・更新・有効化切替）、権限および Cubism バージョンごとのアドミッション検証、フレームワーク更新確認を提供。
- **シェル統合・開発基盤**：Turboism メニュー、ツールバーホーム入口、共通設定・キーバインド画面、保存先履歴、診断ログ、公開 Java SDK（`dev.turboism:sdk`）、別プロセス動作の GraalJS スクリプトホストを実装。

### ファーストパーティプラグイン

標準リリースには 35 個のファーストパーティプラグインを同梱しています（`parameter-lock` はプラグインマーケットから導入可能）。各プラグインの対応バージョンは[プラグイン互換性マトリクス](../../compatibility/cubism/plugins_ja.md)を参照してください：

- **モデリング・メッシュ・デフォーマ**：`turbo-mesh`（曲率対応の自由打点・三角形分割）、`geometric-mesh-layout`（円形・多角形メッシュ生成）、`selection-brush`、`artmesh-mirror`、`mesh-edit-mirror-axis-enhance`、`boundingbox-warp-mirror`、`warp-deformer-alt-symmetry`、`deformer-batch-rename`。
- **パラメータ・物理演算・アニメーション**：`parameter`（CSV 入出力・バインド反転/移行）、`parameter-batch-transfer`、`parameter-sync`、`parameter-lock` *（マーケット）*、`physics-editor`（物理リスト一括選択・状態保持）、`frame-animation-import`（連番・スプライトシート取込）。
- **PSD・3D・テクスチャアトラス**：`external-psd-edit`（外部アプリでの元 PSD リアルタイム編集）、`psd-clip-mask-import`、`model-import-3d`（GLB/glTF/OBJ 取込）、`atlas-maxrects-bssf`、`atlas-dalsoo-polygon`、`atlas-bitmask-nesting`、`atlas-folder-layout`、`texture-atlas-stats`。
- **ワークスペース・UI・検査**：`ui-theme`、`command-palette`（`Ctrl+K`）、`cubism-tab-filter`、`palette-label-style`、`scene-palette-enhancer`、`recent-project-board`、`recent-preview`、`history-panel`（履歴タイムライン）、`clipmask-viewer`、`perf-stats`、`project-work-time`。
- **バックアップ・書き出し・AI / 自動化**：`webdav-backup`、`protected-export`（Cubism 5.1+ 向け保護書き出し）、`mcp`（ローカル MCP サーバー）、`acp`（ACP v1 エージェント会話ウィンドウ）。

## 対応プラットフォーム

- **Windows（x64）** が現在主にサポート・メンテナンスされているプラットフォームです。
- **macOS**：Java のクロスプラットフォーム性により macOS 上でも動作する可能性があります（現在は未検証のプレビュー版 `turboism-<version>.zip` のみ提供）。現時点では macOS 環境での検証・保守は行えていないため、macOS をお持ちでテストや開発にご協力いただける方は [GitHub Issues](https://github.com/turboism/Turboism/issues) までご連絡ください。

## Cubism 対応状況

| Cubism Editor | 対応状況 |
| --- | --- |
| **5.0.00 – 5.0.06** | 一部対応 — 自動バックアップ、自動揺れ読み取り、ジオメトリ読み取り、統合ブリッジ、モデリングカスタムツール、保護付きエクスポート、トップメニュー統合、MOC エクスポート読み取り、パーツインスペクター書き込みが未対応。アニメーション/タイムラインは一部対応 |
| **5.1.00 – 5.1.03** | 一部対応 — ジオメトリ読み取り、モデリングカスタムツール、MOC エクスポート読み取り、パーツインスペクター書き込みが未対応。アニメーション/タイムラインは一部対応（5.1.02 未満の統合ブリッジも一部対応） |
| **5.2.00 – 5.2.03** | ほぼ完全対応 — 5.3 専用 API（アルファ合成、パーツインスペクター書き込み、MOC エクスポート読み取り、Core parameterRepeat）のみ未対応 |
| **5.3.00 – 5.3.04** | 完全対応 — 検証済みの全機能グループが利用可能 |

記載のないバージョンや未対応機能は安全のため無効化されます。API ごとの詳細は[ケイパビリティマトリクス](../../compatibility/cubism/capabilities_ja.md)をご覧ください。

## インストール

Cubism Editor を終了してプロジェクトをバックアップしたうえで、[最新の GitHub Release](https://github.com/turboism/Turboism/releases/latest) からパッケージをダウンロードしてください。標準パッケージには同梱のファーストパーティプラグインと GraalJS のオフライン依存が含まれています（互換 GraalVM ランタイムは設定ツールから別途導入できます）。

現在のインストーラーにはコード署名がないため、実行前に `.sha256` でハッシュを確認することを推奨します：

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### EXE インストーラー（Windows 推奨）

`TurboismInstaller-<version>.exe` を実行し、ウィザードに従ってインストールしてください（インストーラー UI は英語・簡体字中国語・日本語・韓国語に対応。実行時言語は `config.json` で設定します）。Cubism 公式の起動 `.bat` との統合は既定で有効（アンインストール時に自動復元）となり、アップグレード時は既存の `disabledPlugins` 設定を引き継ぎます。導入後は生成されたショートカットから起動してください。

### ZIP（ポータブル版）

1. `turboism-<version>.zip` を Cubism 本体とは別のフォルダーに展開します。
2. 設定ツールを実行し、対象の Cubism とプラグインを選択して保存します：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```
3. 生成されたショートカット、またはフォルダー内の `launch-cubism-turboism.bat` から Cubism を起動します。

## 開発

### プラグイン開発

プラグインを作るだけなら Turboism 本体のクローンやビルドは不要で、**JDK 17 以上**があれば開発できます：

1. [templates/plugin-template](../../templates/plugin-template/) を別プロジェクトとしてコピーし、`./scripts/setup.sh`（Windows は `.\scripts\setup.ps1`）を実行して `turboism-sdk-<version>.jar` と `turboism-event-processor-<version>.jar` を取得・検証します（手動で `libs/` に配置することも可能です）。
2. `./gradlew build`（Windows は `gradlew.bat build`）でビルドします。

このリポジトリ内で試す場合は、[デモプラグイン](../../plugins/demo/README.md)（[build.gradle.kts](../../plugins/demo/build.gradle.kts)、[plugin.json](../../plugins/demo/src/main/resources/META-INF/turboism/plugin.json)）を参照し、次のコマンドでビルドできます：

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

> 注：`plugins:demo` は開発専用であり、配布パッケージには含まれません。プラグインは `:sdk`（`dev.turboism:sdk`）のみを `compileOnly` で参照し、SDK の同梱やランタイム内部・`com.live2d.*` への直接依存は避けてください。ライフサイクルや権限ルールは[プラグイン開発ガイド](../../sdk/plugin-author-guide.md)に記載しています。

### フレームワーク開発

**Git** と **JDK 17** が必要です（リポジトリ内の `./gradlew` / Windows では `gradlew.bat` を使用）：

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

- **開発・検証環境**：**Windows x64** ではネイティブの Cubism ホスト検証を実行でき、**Linux** では Xvfb 上でのヘッドレス検査・単体テストと Proton（Wine）経由の Cubism ホスト検証に対応しています。
- **ローカル SDK 検証**：スタンドアロンプラグインからローカル変更を参照したい場合は、SDK と `@SubscribeEvent` アノテーションプロセッサを `~/.m2` に公開できます：
  ```bash
  ./gradlew :sdk:publishToMavenLocal :event-processor:publishToMavenLocal
  ```
- モジュール境界、トランザクション、検証ゲートの詳細は[アーキテクチャ](../architecture/ARCHITECTURE.md)を参照してください。

## ドキュメント

- [ユーザー・開発者向けドキュメント](https://docs.turboism.dev)
- [アーキテクチャ](../architecture/ARCHITECTURE.md) · [ロードマップ](../ROADMAP.md)
- [SDK API 契約](../../sdk/api-contracts/) · [現在の公開 SDK サーフェス](../../sdk/api-contracts/current.txt)
- [プラグイン開発ガイド](../../sdk/plugin-author-guide.md) · [プラグインテンプレート](../../templates/plugin-template/) · [デモプラグイン](../../plugins/demo/README.md)
- [リリース手順](../RELEASING.md) · [変更履歴](CHANGELOG.md)

## ライセンス

Copyright © 2026 Turboism Contributors. 本ソフトウェアは [AGPL-3.0-only](../../LICENSE) の下で公開されています（サードパーティー部品には各ライセンスが適用されます）。
