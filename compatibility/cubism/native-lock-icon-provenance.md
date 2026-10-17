# Native Lock Icon Provenance & Audit

Evidence record for the native Parts Tab lock icon resource family referenced by `CubismIcon.LOCK`.

## Bytecode Evidence

Live2D Cubism Editor's native Parts palette (`treeTable`) lock column bytecode references:
- **Cubism 5.0.00 – 5.3.02:** `com/live2d/ui/treeTable/h.class` static initialization references `image/icons/Lock_16x16_Default.png`.
- **Cubism 5.3.03 – 5.3.04:** `com/live2d/ui/treeTable/i.class` static initialization references `image/icons/Lock_16x16_Default.png`.

Parts toolbar uses 24x24 icons (`Lock_24x24`), while the row/column entry uses 16x16 icons (`Lock_16x16`).

## Artifact Verification & Hashes

Across all 20 reviewed Cubism versions (`5.0.00` through `5.3.04`), the 20 PNG variants have identical SHA-256 digests and dimensions (16px base logical):

### Light Theme
- `res/image_light/icons/Lock_16x16_Default.scale-100.png`: `a3bdb4be715e637c187a98321177da6a7e96fb66a6cb8107cd15e84c08bf6275`
- `res/image_light/icons/Lock_16x16_Default.scale-125.png`: `9d0837e0fa4f76a4f1e069e81c74a825feac24591b618c33c9f670446871988f`
- `res/image_light/icons/Lock_16x16_Default.scale-150.png`: `cf7fabd8c74f9431bc9b1707170e87cecd9d5bdc7bf519da70a0c31a448f1121`
- `res/image_light/icons/Lock_16x16_Default.scale-175.png`: `f2dc0fad2d00fd62ccf03dd41502b1a9afb9b80891c14dd51f47de1c725ba63b`
- `res/image_light/icons/Lock_16x16_Default.scale-200.png`: `222c10fbd5195a5def9c030529f44992e49635ca018da65643831e2e9c390f97`

- `res/image_light/icons/Lock_16x16_Disabled.scale-100.png`: `826500666e3eeb7dff742c12a7f1acbc693305e6db55b7e42c04c7503394485d`
- `res/image_light/icons/Lock_16x16_Disabled.scale-125.png`: `365b4cb6d871feebf28e900b996cc976d8389f80a094e53e161f8d999eaaad5f`
- `res/image_light/icons/Lock_16x16_Disabled.scale-150.png`: `925492c2320e0f51ffc2ecc33111994f95a25136e56079424903cc059f6042fa`
- `res/image_light/icons/Lock_16x16_Disabled.scale-175.png`: `1e54da54e340c4bf037e87e8577cd5ebf9e7a94b575efad5b45d0e0ac169cb21`
- `res/image_light/icons/Lock_16x16_Disabled.scale-200.png`: `71dbd8dcea112bc3f5e5ce7347b275e6c4740706cedbc402448d654ee9ec90dd`

### Dark Theme
- `res/image_dark/icons/Lock_16x16_Default.scale-100.png`: `dbf96fd836e7341a62c22f0e0d09520556412ce9500dbb7c0576813a70532874`
- `res/image_dark/icons/Lock_16x16_Default.scale-125.png`: `7d8209c681cb652ff7c0e3d103d139497d18883d273201bac8e7b0fbfa7a2ec9`
- `res/image_dark/icons/Lock_16x16_Default.scale-150.png`: `fc3ea8c80391f5d0709f874cd63e8bac3c93c99f7c2418c0e8bfaa8e7cc5abc4`
- `res/image_dark/icons/Lock_16x16_Default.scale-175.png`: `68c424a9a4b2c802523b9239818fecb113027adca9eb691c1d693b45e031c990`
- `res/image_dark/icons/Lock_16x16_Default.scale-200.png`: `ebcd6ca6b7f0034035506589987b36ac2b14c586bcd5a419c9d8e50c734666e3`

- `res/image_dark/icons/Lock_16x16_Disabled.scale-100.png`: `e41ca8a4753ac5c6178177d0a47e363cb4d324301b9ab4d4031eed33881c059f`
- `res/image_dark/icons/Lock_16x16_Disabled.scale-125.png`: `132635f38e291598f6040f12b28ef67e001e29d41b545ce6a85ea728ce49c2fd`
- `res/image_dark/icons/Lock_16x16_Disabled.scale-150.png`: `e1eb2947990507340d1273255e46f4e799e1abedefca796531098ca6489ccf29`
- `res/image_dark/icons/Lock_16x16_Disabled.scale-175.png`: `12377c6fd083fd4bace21f887c9688b9f7d98cc928e815fa98599a96d76a6a94`
- `res/image_dark/icons/Lock_16x16_Disabled.scale-200.png`: `b85561f3f8fccc5db5b4caa612aed096d0c9bfd79364173907215831fcfd280c`

## Runtime Resolution & Safety

- Pinned in `CubismNativeIconCatalog` with total catalog size of 120 variants (6 reviewed icon types × 20 variants, including the unlocked ring).
- Verified by `CubismNativeIconResolver` with bounded cache size (`<= 120`).
- No host image bytes or classes are bundled in the repository or distribution artifacts; resolution extracts from the attested host JAR at runtime off-EDT and validates SHA-256 digests.

## Unlocked ring placeholder (2026-10-17)

The same Parts/Deformer checkbox paint branch uses `None_16x16_Default` when unselected, rather than hiding its icon. `CubismIcon.LOCK_PLACEHOLDER` resolves that native ring for unlocked parameter and group rows, continuously visible without hover. Locked rows retain `Lock_16x16_Default`; mixed groups retain the disabled lock. All 20 exact reviewed JARs reference the None resource in their treeTable checkbox and contain identical hashes for the following 20 variants. The catalog and cache now hold 120 variants. No native image bytes are bundled.

- `res/image_light/icons/None_16x16_Default.scale-100.png`: `8d23c68e835a7c6cc03c9e3669082d097d66cba16c0191935e2e1dbbdc111fe4`
- `res/image_light/icons/None_16x16_Default.scale-125.png`: `4ce6a4a643c9d261d62a60b959e8969026674e7095060a8ffc2d3319808e3021`
- `res/image_light/icons/None_16x16_Default.scale-150.png`: `40a96fcf699ee1eb5590f160e3df7b11996c56754d4fc56238efc780766120d9`
- `res/image_light/icons/None_16x16_Default.scale-175.png`: `2b8ce8426cb92912297a11f5c8f6d3430074ba92142563cc31d26f544d245223`
- `res/image_light/icons/None_16x16_Default.scale-200.png`: `43e83983d13460ac2803a7f5861fd2e12b2dcae388250914b015fea2032f6cd2`
- `res/image_light/icons/None_16x16_Disabled.scale-100.png`: `f264c9155bd10ca4b8e29cae05d3addee2bc74d1e831d4466305fe93868d3570`
- `res/image_light/icons/None_16x16_Disabled.scale-125.png`: `15807767ed0964a3ee1930c27a75580fa8b5a11162b92ccdd0ba691954808451`
- `res/image_light/icons/None_16x16_Disabled.scale-150.png`: `3c9302efff36906d02b2ce69c2bf02b574068a8b149bb2105c8403494f3bc145`
- `res/image_light/icons/None_16x16_Disabled.scale-175.png`: `65a61a6f369044a4fe516155d389bb20bbeba517c3e23a330a3684c4160c3cd9`
- `res/image_light/icons/None_16x16_Disabled.scale-200.png`: `14eae125f72e2dfaedcce6421a0e3afbc57bec8e7ab302fd5a416445d27c73ea`
- `res/image_dark/icons/None_16x16_Default.scale-100.png`: `a8c3c523c9bb965daadddedb289183cb15151ad4edf7ea24953fb38d02b8bce2`
- `res/image_dark/icons/None_16x16_Default.scale-125.png`: `8d64d5aa031d56b11c4b3dea4a67ebdc296369219f5f3fa8a1501087c7d4d031`
- `res/image_dark/icons/None_16x16_Default.scale-150.png`: `b2c329bbc7b297747ee2f0fc496b5550c0c98a9de69184790958647592e71e40`
- `res/image_dark/icons/None_16x16_Default.scale-175.png`: `bfdb7d43026cd89adb51e6ab6df95106666c70b5305c8602ce69c533b3151f1c`
- `res/image_dark/icons/None_16x16_Default.scale-200.png`: `b5fdf390ed8b4b973075c121b2986313aa606ef8019a3d65d7d276be18a5512b`
- `res/image_dark/icons/None_16x16_Disabled.scale-100.png`: `9b8e94e5418e480c8da6e333597df90f410d9cc964cf9118804bce907aa8c8b6`
- `res/image_dark/icons/None_16x16_Disabled.scale-125.png`: `176d11b152a1b09096b91822dd64a767a4dc9ae6b24b0a46d4076e7333e6f58d`
- `res/image_dark/icons/None_16x16_Disabled.scale-150.png`: `6d16604a768c5dca1f514fc8817a9be2c542ab43e8c6b1a06c174266f886a9d5`
- `res/image_dark/icons/None_16x16_Disabled.scale-175.png`: `80bc22929cdcf2b8d0625cebf4e998fef8cdd57bde5e7af19f7b9ff607e4033c`
- `res/image_dark/icons/None_16x16_Disabled.scale-200.png`: `7cb4cc65b1aff8d45345b1f2a24a479c599789672076b2d3892c156f99e40626`
