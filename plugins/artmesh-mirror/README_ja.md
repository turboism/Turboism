---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.artmesh-mirror
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing, symmetry
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: none
---

# アートメッシュミラー

## 機能概要

通常のモデリング画面でアートメッシュの頂点を対称編集します。選択するメッシュが変わると、軸は選択範囲の中心へ戻ります。

## 要件と互換性

Turboism API: `[0.67.0,1.0.0)`. Cubism Editor: 5.0.00–5.3.04.

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 全審査済みバージョン（5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04、20 個の正確なバージョン）.
全機能（審査済みバージョン）：5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04。
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->

## インストールと有効化

Turboism の公式配布に同梱されます。プラグイン管理で有効・無効を切り替えます。

## 使い方

専用のキャンバスボタンで切り替えます。中空のひし形または軸線をドラッグして移動し、中空の四角をドラッグして回転します。ダブルクリックでリセットします。角度は背景なしの大きな縁取り文字です。ハンドル位置はズームに追従し、クリックする大きさは維持します。ワープデフォーマの対称編集は独立したボタンを使用し、ショートカットは登録しません。

## 機能

| Capability | Effect |
|---|---|
| `cubism.artmesh.mirror` | 頂点の対称編集とキャンバス軸を操作します。 |

## 権限

| Permission | Scope | Effect |
|---|---|---|
| `turboism.cubism.model.write` | `application` | 頂点の対称編集とキャンバス軸を操作します。 |
| `turboism.ui.toolbar.contribute` | `application` | 頂点の対称編集とキャンバス軸を操作します。 |
| `turboism.ui.canvas.hint` | `application` | 頂点の対称編集とキャンバス軸を操作します。 |

## プライバシーとデータ

ネットワーク通信・テレメトリー・プラグインデータの永続化はありません。変更はネイティブ操作の Undo/Redo に含まれます。

## 状態と制限

プレビュー版。任意の軸角度で反射位置から約 20 画面ピクセル以内を検索し、相互に一意な対応点を使用します。頂点は追加しません。メッシュ構造の編集では専用のミラーツールを使用します。

## トラブルシューティング

軸が表示されない場合は、プラグインとボタンを有効にし、通常のモデリング画面でアートメッシュを選択してください。対応点が動かない場合は反射位置付近に頂点があるか確認してください。

## 変更履歴

### 0.1.0

- 初回リリース。

## サポートとライセンス

[Issues](https://github.com/turboism/Turboism/issues) · [Project License](../../LICENSE)
