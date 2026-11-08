---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.turbo-mesh
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.68.1,1.0.0)"
requiresCubism: true
interface: embedded
---

# Turbo Mesh

> **Turboism 公式プラグイン** · **ステータス: Preview**

カスタム ArtMesh 布点：曲率適応の境界再サンプリング、領域自律分割、拘束 Delaunay 三角形分割を、1 回の Undo 可能な編集として書き戻します。

| 項目 | 値 |
|---|---|
| バージョン | `0.1.0` |
| プラグイン ID | `dev.turboism.plugin.turbo-mesh` |
| カテゴリ | `modeling` |
| タグ | mesh, artmesh, editing |
| Turboism API | `[0.68.1,1.0.0)` |
| Cubism 必須 | はい |
| インターフェース | `embedded` |
| ライセンス | Project License |

## 機能概要

- ArtMesh のコンテキストメニューに **Turbo Mesh: 再配置** 項目を追加します。
- メッシュの境界リングを抽出し、曲率適応の間隔で再サンプリングします。尖端にはアンカーと密な間隔を、平滑な曲線には弦高誤差で制御されたサンプルを配置します。
- 形状を自律領域に分割し（4 連結成分、細い領域で再帰分割）、領域面積に比例して内部点の予算を配分します。
- 内部サンプルはシード付きジッターグリッドで配置し、領域境界からの保護マージンを維持します。
- 全点を拘束 Delaunay で三角形分割し、領域の分割線をハードエッジにしてから、エディタの Undo エンベロープ経由でアトミックにコミットします——Undo/Redo がそのまま機能します。

## 要件と互換性

- **Turboism API:**`[0.68.1,1.0.0)`。
- **Cubism:** Cubism が必要です。Turboism が現在認めるのはレビュー済みの正確な Editor アーティファクト `5.2.03`、`5.3.02`、`5.3.03` のみです。本プラグインは宣言したサービスとケイパビリティが利用可能な場合にのみ各機能を公開します。
- **インターフェースモード:**`embedded`。
- **プラグイン依存関係:** 宣言なし。

<!-- compatibility:begin -->
**Cubism Editor バージョン対応（自動生成）：** 5.2.00–5.2.03, 5.3.00–5.3.04（それ以前の Cubism バージョンには機能自体がない）.
全機能（審査済みバージョン）：5.2.00–5.2.03, 5.3.00–5.3.04。
- メッシュジオメトリ読み取り のみ 5.2.00–5.3.04（それ以前の Cubism バージョンには機能自体がない）
完全なマトリクス: [compatibility matrix](../../compatibility/cubism/plugins_ja.md)
<!-- compatibility:end -->

## インストールと有効化

この公式プラグインは **store candidate** であり、まだ公開されたストア掲載ではありません。マーケットプレイス公開までは Turboism の公式リリースパッケージ経由でインストールし、**プラグイン管理** で有効化してください。不要になった場合は同じウィンドウから無効化またはアンインストールできます。

## 使い方

1. デフォーマ、パーツ、ワークスペースオブジェクトパネルで ArtMesh を選択します。
2. 右クリックで **Turbo Mesh：再配置** を選びます。
3. モードダイアログで配置モードを選択します（自動ブレンド / ソフトストリップ / ソフトシート / リジッド）。
4. 確認ダイアログで元メッシュと新メッシュの統計を比較し、確認で書き戻し、キャンセルで中断します。
5. メッシュは取り消し可能な単一編集として書き戻されます。Undo/Redo が機能します。

パラメータキーフォームを持つ ArtMesh は確認ダイアログで警告されます。ホストがダイアログを
表示できない場合、キーフォーム付き ArtMesh の再配置は阻止されます。

現時点の調整は JVM システムプロパティで行います（設定ダイアログは今後の予定です）：

| プロパティ | 既定値 | 意味 |
|---|---|---|
| `turboMesh.targetPoints` | 320 | 総ポイント数のソフト目標 |
| `turboMesh.features` | `false` | 0.2.0 特徴認識配置パスを有効化 |
| `turboMesh.features.preset` | `AUTO_BLEND` | プリセット: `AUTO_BLEND` / `SOFT_STRIP` / `SOFT_SHEET` / `RIGID_GEOMETRY` |
| `turboMesh.features.weightTexture` | プリセット毎 | テクスチャエッジ密度の重み（ソフトシグナル） |
| `turboMesh.features.directionInfluence` | プリセット毎 | 方向場がサンプリングへ与える影響（0 で無効） |
| `turboMesh.confirm` | `dialog` | `never` で確認ダイアログを省略（自動化/検証用） |
| `turboMesh.alphaThreshold` | `8` | テクスチャ alpha ドメインのしきい値 |

### 自動メッシュ生成のアルゴリズム

エディタの「自動メッシュ生成」に 2 つのアルゴリズムを登録します。どちらもエディタ標準の生成器の
隣に並び、標準の項目を選べばホストの生成器はそのまま動作します。

| アルゴリズム | 内容 | プリセット |
|---|---|---|
| `turbo-mesh.region` | テクスチャを領域に分割し、領域の分界線を硬い辺として各領域を三角形分割します。 | 疎 / 詳細 / ハードサーフェス |
| `turbo-mesh.adaptive` | 境界を曲率に応じて再サンプリングし、内部を領域ごとの点で埋めます。 | バランス / ソフトストリップ / ソフトシート / リジッド |

領域アルゴリズムは ArtMesh のテクスチャ画素を読み取ります。ホストが画素を公開しない場合は、
推測したメッシュではなく `no-texture` の理由で拒否します。自動の項目には切り替えスイッチが
付きます。スイッチがオンのとき、プランナーはその値をシルエットの大きさから算出し、隣の数値は
使いません。

ランタイムが自動メッシュのレジストリを公開していない場合、プラグインは 1 行だけログを残し、
ArtMesh のコンテキストメニュー動作をこれまで通り維持します。

## 機能

| 宣言ケイパビリティ | ユーザーへの効果 |
|---|---|
| `cubism.mesh.read` | 布点計画のために選択した ArtMesh ジオメトリを読み取ります。 |
| `cubism.mesh.write` | 生成したメッシュジオメトリを ArtMesh に書き戻します。 |
| `cubism.mesh.automesh-algorithms` | エディタの「自動メッシュ生成」に領域方式とアダプティブ方式を提供します。 |
| `ui.context-menu.contribute` | ArtMesh のコンテキストメニューに布点項目を追加します。 |
| `ui.dialog.contribute` | 配置モードの選択とビフォー・アフターの確認ダイアログを表示します。 |
| `ui.status.notify` | 布点結果と失敗をホストのステータス通知で報告します。 |

## 権限

| 権限 | スコープ | 要求理由 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 布点計画のために選択した ArtMesh ジオメトリを読み取るため。 |
| `turboism.cubism.model.write` | `application` | 生成したメッシュジオメトリを ArtMesh にコミットするため。 |
| `turboism.action.register` | `application` | カスタム布点アクションを登録するため。 |
| `turboism.ui.context-menu.contribute` | `application` | ArtMesh のコンテキストメニューに布点項目を追加するため。 |
| `turboism.ui.dialog.contribute` | `application` | 配置モードの選択とビフォー・アフターの確認ダイアログを表示するため。 |
| `turboism.ui.status.notify` | `application` | 布点結果と失敗を報告するため。 |
| `turboism.cubism.mesh.automesh.contribute` | `application` | エディタの「自動メッシュ生成」に領域方式とアダプティブ方式を提供するため。 |

## プライバシーとデータ

### ネットワーク

ネットワーク接続は行いません。

### ローカルデータ

モデルデータやプラグイン設定を永続化しません。布点結果は通常の編集として開いているモデルドキュメントにのみ書き込まれます。

### テレメトリ

このプラグインはテレメトリを送信しません。

プラグインのライフサイクルと失敗の記録は、プラグイン ID 付きで Turboism のセッションログと Cubism のホストログに残る場合があります。

## 状態と制限

- **ステータス:** Preview。
- ジオメトリ書き込みは**現在のキーフォームのみ**が対象です。他のキーフォームで既に変形済みのメッシュを再配置すると頂点数が変わり、それらのキーフォームは自動で再マップされません——変形前に配置するか、まずコピーで確認してください。
- 配置ドメインはメッシュ既存の UV 輪郭から取得します（穴対応、トポロジー保持）。テクスチャ alpha 認識の配置は、審査済みのピクセル読み取り面が揃い次第、拡張として追加予定です。

## トラブルシューティング

| 症状 | 確認事項 |
|---|---|
| メニュー項目が表示されない | プラグインが有効で、選択が ArtMesh を含んでいるか確認してください。 |
| 布点が失敗を報告する | ステータス通知とセッションログのプランナー診断を確認してください。 |
| メッシュに変化がない | メッシュが有効な三角形トポロジーを持ち、ロックされていないか確認してください。 |

## サポートとライセンス

- **プロジェクトサイト:**[https://turboism.dev](https://turboism.dev)
- **発行者:** Turboism Contributors
- **ライセンス:** Project License
- **プラグイン ID:** `dev.turboism.plugin.turbo-mesh`
