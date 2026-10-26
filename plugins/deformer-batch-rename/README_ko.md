---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.deformer-batch-rename
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: deformer, rename, batch-edit
turboismApi: "[0.67.0,1.0.0)"
requiresCubism: true
interface: swing
---
# ëí¬ë¨¸ ì´ë¦ ì¼ê´ ë³ê²½

## 기능 개요

子孫名と関連パラメータに基づいてワープ・回転デフォーマを一括リネームします。

## 요구 사항 및 호환성

Turboism 0.67.0 以降と開いたモデルが必要です。以下の互換性は SDK から導出され、実機検証とは異なります。

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- 상단 메뉴 통합 제외 5.0.00–5.0.06（이전 Cubism 버전에는 기능 자체가 없음）
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

`./gradlew :plugins:deformer-batch-rename:jar` でビルドし、プラグイン管理から JAR をインストールして有効にします。

## 사용 방법

1. モデルを開き、**Turboism → プラグイン → デフォーマを一括リネーム** を選びます。
2. ネイティブのワープ／回転アイコン・変更前・変更後・適用の4列を確認します。
3. 変更する行を選択します。最終列の見出しで全選択・解除できます。名前が変わらない行は選択できません。
4. 確定後に変更します。キャンセル、ウィンドウを閉じる操作、Escape では変更しません。
5. Cubism の Undo で変更全体を一度で元に戻せます。

変更後の名前は自動生成され、このダイアログでは直接編集できません。

末端の子孫名の共通接頭辞を使い、共通部分がなければ最短名を使います。同じ長さならモデル順です。子がないデフォーマは変更しません。自身の関連パラメータ名を追加し、同組の X/Y/Z をまとめます。

### 名前の例

| 末端の子孫名 | 自身の関連パラメータ | 変更後 |
| --- | --- | --- |
| `髪1`、`髪1 影` | なし | `髪1` |
| `髪1`、`髪1 影` | `頭部X`、`頭部Y` | `髪1 頭部XY` |
| `左目`、`右目の影` | なし | `左目` |

表示名のみを変更します。ID、階層、パラメータの関連付けは変更しません。同名の競合は自動解決しません。

## 기능

- `cubism.model.read`
- `cubism.model.write`
- `ui.dialog.contribute`
- `ui.menu.contribute`
- `ui.status.notify`

## 권한

- `turboism.cubism.model.read`
- `turboism.cubism.model.write`
- `turboism.action.register`
- `turboism.ui.dialog.contribute`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`

## 개인정보 및 데이터

すべてローカルで処理し、通信や設定の保存は行いません。

## 상태 및 제한 사항

リリース候補版です。Cubism 5.3.03 でプレビューと両種類のネイティブアイコンを実機確認しました（12 行、テキストへのフォールバックなし）。各対応バージョンでの名前変更と Undo の実機検証は未完了です。ID は変更せず、変更全体を一度で元に戻せます。

## 문제 해결

モデルを開いてください。プレビュー中にモデル、階層、名前、パラメータの関連付けを変更した場合は、キャンセルして開き直してください。古いプレビューの適用は拒否されます。失敗時はプラグインログを確認してください。

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

[問題を報告](https://github.com/turboism/Turboism/issues)。리포지토리 라이선스를 따릅니다.
