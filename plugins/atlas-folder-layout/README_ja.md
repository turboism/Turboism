---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-folders
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.67.4,1.0.0)"
requiresCubism: true
interface: swing
---

# パーツフォルダー別アトラス配置


新しく読み込んだ PSD にアトラスがない場合は、ネイティブの初期配置を実行せずに出力ページを直接作成します。テクスチャ画像の生成と配置を一度の取り消し可能な編集で完了し、アトラスエディターで再確認する必要はありません。

## 機能概要

最上位のパーツフォルダーごとにテクスチャページを作成し、収まらない場合はサブフォルダーに分割します。登録済みの配置アルゴリズムを利用し、特定のアルゴリズムプラグインには依存しません。

## 要件と互換性

Java 17 以降、Turboism API [0.67.4,1.0.0)、編集可能な Cubism モデルが必要です。アトラス全体の配置に対応するアルゴリズムを有効にしてください。

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全審査済みバージョン（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04、20 個の正確なバージョン）.
全機能（審査済みバージョン）：5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
- トップメニュー統合 対象外 5.0.00–5.0.06（それ以前の Cubism バージョンには機能自体がない）
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->

## インストールと有効化

Turboism のプラグイン管理で JAR をインストールし、このプラグインと、MaxRects-BSSF、Bitmask Nesting、Dalsoo などアトラス全体に対応するアルゴリズムを有効にします。

Bitmask Nesting と Dalsoo は画像ローカル座標のメッシュ輪郭を使用し、取得できない場合は画像全体の矩形に戻します。原寸・回転なしで、フォルダー順を優先しながら空き領域を共有し、新しいページを作る前に既存ページを充填します。

## 使い方

**Turboism → パーツフォルダー別にアトラスを配置** を開きます。最大辺長（1024、2048、4096、8192）、アルゴリズム、余白、間隔、自動サイズ調整を選択します。配置全体を一度の取り消し可能な編集で置き換え、ページ順はパーツツリーに従います。

## 機能

- `cubism.texture-atlas.layout`
- `cubism.model.read`
- `ui.menu.contribute`
- `ui.status.notify`

## 権限

- `turboism.action.register`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`
- `turboism.cubism.model.read`
- `turboism.cubism.model.write`

## プライバシーとデータ

現在のモデルを読み取り、エディター内でアトラス配置を更新します。モデルデータのアップロードやネットワーク通信は行いません。

## 状態と制限

プレビュー版です。現在のページのみに対応するアルゴリズムは表示されますが選択できません。メニューは MaxRects と独立していますが、アトラス全体に対応するアルゴリズムが必要です。

## トラブルシューティング

利用可能なアルゴリズムがない場合は、対応するプラグインを有効にしてください。アトラスがない場合は現在のモデルで作成するか開いてください。元に戻す操作で以前の配置を復元できます。

## 変更履歴

### 0.1.0

- 初回リリース。

## サポートとライセンス

https://github.com/turboism/Turboism/issues に再現手順、エディターのバージョン、選択したアルゴリズムを記載してください。リポジトリーの Project License が適用されます。
