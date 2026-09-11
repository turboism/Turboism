[EN / English](README.md) · [ZH / 简体中文](README_zh.md) · [JP / 日本語](README_ja.md) · [KR / 한국어](README_ko.md)

# Turboism

## 著作権と注意事項

Copyright © 2026 Turboism Contributors。Turboism は [MIT License](LICENSE) に基づくオープンソースソフトウェアです。

Turboism は**独立したサードパーティープロジェクト**であり、Live2D Inc. との提携関係はなく、同社による推奨やスポンサー提供も受けていません。Live2D、Cubism および関連する名称・標章の権利は、Live2D Inc. またはそれぞれの権利者に帰属します。Turboism は Cubism Editor を配布せず、そのライセンスを付与・代替したり、認証を回避したりするものではありません。別途、正規のライセンスを取得した Cubism Editor をインストールする必要があります。

インストール前に[エンドユーザー実行声明・免責事項](EULA.md)をお読みください。この声明は MIT License が付与する権利を制限するものではなく、簡体字中国語の正式文書が優先されます。本ソフトウェアは**現状のまま**提供されます。プロジェクトの内容を変更するプラグインや自動化機能を使う前に、独立したバックアップを保存してください。

## プロジェクト概要

Turboism は **Live2D Cubism Editor** 向けの、Windows を主な対象とするランタイム拡張ツール兼プラグインフレームワークです。Java Agent と公開 SDK により、パラメータ・メッシュツール、PSD 補助機能、パレット拡張、ローカル自動化などのモデリング作業を支援します。利用できる機能は、インストール済みプラグイン、権限、Editor の正確なバージョンによって異なります。

## 対応する Cubism Editor のバージョン

| Cubism Editor | 対応状況 |
| --- | --- |
| **5.2.03** | 当該バージョン専用のアダプターを提供 |
| **5.3.02** | 当該バージョン専用のアダプターを提供 |
| **5.3.03** | 当該バージョン専用のアダプターを提供 |

Cubism のホスト環境として対応しているのは **Windows x64** です。記載のない Editor バージョンとの互換性は保証しません。必要なアダプターや機能が利用できない場合は、安全のため動作を拒否します。対応バージョンであっても、すべてのプラグイン機能が利用できるとは限りません。

Java インストーラーは macOS と Linux へのファイル配置にも対応しています。ただし、macOS のパッケージングはプレビュー段階で、Cubism ホストとしての動作は未検証です。Linux はインストーラーとファイル配置の動作のみを対象としており、Cubism ホストとしては非対応です。

## インストール

[最新の GitHub Release](https://github.com/turboism/Turboism/releases/latest)からパッケージと対応する `.sha256` ファイルをダウンロードしてください。以下の**いずれか一つ**の方法を選びます。インストールや更新の前に Cubism を終了し、プロジェクトをバックアップしてください。

すべての Turboism インストーラーは**英語、簡体字中国語、日本語、韓国語**に対応しています。言語の選択はインストーラー自身の表示だけを変え、実行時の Turboism の言語は引き続き `config.json` の `locale` で決まります。

現在のインストーラーにはコード署名がありません。実行前に、ダウンロードしたファイルの SHA-256 を付属のチェックサムと照合してください。PowerShell の例です（`<version>` はダウンロードしたバージョンに置き換えます）。

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### ZIP — Windows での手動セットアップ

1. 同梱のファーストパーティープラグインを使う場合は `turboism-<version>-full.zip`、プラグイン JAR を含まないランタイムのみの場合は `turboism-<version>-lite.zip` を選びます。
2. **アーカイブ全体**を独立した Turboism フォルダーに展開してください。Cubism のインストール先には展開しないでください。
3. 展開先のフォルダーから設定ツールを開きます。

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```

   実行ポリシーの指定は、この PowerShell プロセスだけに適用されます。使用する Cubism のインストール先とプラグインを選択し、保存してください。
4. 作成された Turboism ショートカット、または同じフォルダー内の `launch-cubism-turboism.bat` から Cubism を起動します。

### JAR — Java インストーラー

**Java 17 以降**をインストールしてから実行します。

```bash
java -jar "TurboismInstaller-<version>.jar"
```

ライセンスと声明を確認し、インストール先とパッケージのオプションを選んでセットアップを完了します。ウィザードの起動時に 4 言語の言語選択ダイアログが開きます。`-language eng|chn|jpn|kor` を渡すと選択を省略できます。Windows では、必要に応じてインストール先の `configure_turboism.ps1` で Cubism を設定し、Turboism 経由で起動してください。macOS/Linux にファイルをインストールできることは、その環境での Cubism ホスト対応を意味しません。

### EXE — Windows での推奨方法

`TurboismInstaller-<version>.exe` を実行し、ウィザードに従ってインストール先、プラグイン、起動オプションを選択します。ようこそページの前に言語選択（英語、簡体字中国語、日本語、韓国語）が表示され、インストーラーにのみ適用されます。インストール後は、作成された Turboism ショートカットを使用してください。

Cubism 公式の起動 BAT との統合は**任意**であり、明示的な選択が必要です。ハッシュ検証付きのバックアップを使用しますが、後から手動で編集されたファイルは自動復元できない場合があります。これらのインストーラー管理下のバックアップとは別に、プロジェクトのバックアップを保存してください。

どのリリースパッケージにも、管理対象の fx ランタイムファイルや開発専用の Turboism with fx プラグインは含まれません。

## 開発

**Git と JDK 17** が必要です。リポジトリに付属する Gradle Wrapper を使用するため、Gradle を別途インストールする必要はありません。

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

Windows では `./gradlew` の代わりに `gradlew.bat` を使用してください。変更は専用の機能ブランチまたは worktree で行ってください。

プラグイン開発は、[デモプラグイン](plugins/demo/README.md)、その[ビルド設定](plugins/demo/build.gradle.kts)、[プラグイン記述ファイル](plugins/demo/src/main/resources/META-INF/turboism/plugin.json)を参考に始められます。プラグインは `compileOnly` で `:sdk` に依存し、ランタイムの内部実装や `com.live2d.*` クラスには直接依存しないでください。

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

デモは開発専用であり、リリースパッケージには含まれません。変更を提出する前に、影響範囲のテストと `./gradlew devCheck` を実行してください。API、ライフサイクル、トランザクション、検証の規則は[アーキテクチャ](ARCHITECTURE.md)を参照してください。

## ドキュメント

- [ユーザー・開発者向けドキュメント](https://docs.turboism.dev)
- [アーキテクチャ](ARCHITECTURE.md)と[ロードマップ](ROADMAP.md)
- [SDK API 契約と互換性](sdk/api-contracts/)、[SDK v10 移行ガイド](sdk/api-contracts/sdk-api-v10-review.md)、[SDK v9](sdk/api-contracts/sdk-api-v9-review.md)・[SDK v7](sdk/api-contracts/sdk-api-v7-review.md) レビューは履歴監査として保持
- [デモプラグイン](plugins/demo/README.md)
- [Java インストーラーの詳細](packaging/java-installer/README-java-installer.md)
- [リリース手順](RELEASING.md)と[変更履歴](CHANGELOG.md)
