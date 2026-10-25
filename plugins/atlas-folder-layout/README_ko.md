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
# í´ëë³ ìíë¼ì¤ ë°°ì¹


新しく読み込んだ PSD にアトラスがない場合は、ネイティブの初期配置を実行せずに出力ページを直接作成します。テクスチャ画像の生成と配置を一度の取り消し可能な編集で完了し、アトラスエディターで再確認する必要はありません。

## 기능 개요

最上位のパーツフォルダーごとにテクスチャページを作成し、収まらない場合はサブフォルダーに分割します。登録済みの配置アルゴリズムを利用し、特定のアルゴリズムプラグインには依存しません。

## 요구 사항 및 호환성

Java 17 以降、Turboism API [0.67.4,1.0.0)、編集可能な Cubism モデルが必要です。アトラス全体の配置に対応するアルゴリズムを有効にしてください。

<!-- compatibility:begin -->
**Cubism Editor 버전 지원(자동 생성):** 검토된 모든 버전(5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04, 정확한 버전 20개).
전체 기능(검토된 버전): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- 상단 메뉴 통합 제외 5.0.00–5.0.06（이전 Cubism 버전에는 기능 자체가 없음）
전체 매트릭스: [compatibility matrix](../../compatibility/cubism/plugins_ko.md)
<!-- compatibility:end -->

## 설치 및 활성화

Turboism のプラグイン管理で JAR をインストールし、このプラグインと、MaxRects-BSSF、Bitmask Nesting、Dalsoo などアトラス全体に対応するアルゴリズムを有効にします。

Bitmask Nesting と Dalsoo は画像ローカル座標のメッシュ輪郭を使用し、取得できない場合は画像全体の矩形に戻します。原寸・回転なしで、フォルダー順を優先しながら空き領域を共有し、新しいページを作る前に既存ページを充填します。

## 사용 방법

**Turboism → パーツフォルダー別にアトラスを配置** を開きます。最大辺長（1024、2048、4096、8192）、アルゴリズム、余白、間隔、自動サイズ調整を選択します。配置全体を一度の取り消し可能な編集で置き換え、ページ順はパーツツリーに従います。

## 기능

- `cubism.texture-atlas.layout`
- `cubism.model.read`
- `ui.menu.contribute`
- `ui.status.notify`

## 권한

- `turboism.action.register`
- `turboism.ui.menu.contribute`
- `turboism.ui.status.notify`
- `turboism.cubism.model.read`
- `turboism.cubism.model.write`

## 개인정보 및 데이터

현재 모델을 읽고 에디터 내에서 아틀라스 배치를 업데이트합니다. 모델 데이터 업로드나 네트워크 통신은 수행하지 않습니다.

## 상태 및 제한 사항

プレビュー版です。現在のページのみに対応するアルゴリズムは表示されますが選択できません。メニューは MaxRects と独立していますが、アトラス全体に対応するアルゴリズムが必要です。

## 문제 해결

利用可能なアルゴリズムがない場合は、対応するプラグインを有効にしてください。アトラスがない場合は現在のモデルで作成するか開いてください。元に戻す操作で以前の配置を復元できます。

## 변경 이력

### 0.1.0

- 초회 릴리스.

## 지원 및 라이선스

https://github.com/turboism/Turboism/issues に再現手順、エディターのバージョン、選択したアルゴリズムを記載してください。リポジトリーの 프로젝트 라이선스 が適用されます。
