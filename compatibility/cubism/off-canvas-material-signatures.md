# OffCanvas Material Signature Check

All 20 inspected JARs (`5.0.00`–`5.3.04`) hold exactly one public instance `setColor`.
Every return type is void. Every access is 17.

- owner: `com/live2d/graphics3d/material/GMaterial`.
- Two-argument descriptor: `(Ljava/lang/String;Lcom/live2d/type/CColor;)V`.
- Three-argument descriptor: `(Ljava/lang/String;Lcom/live2d/type/CColor;Ljava/lang/Integer;)V`.
- JAR directory: `/opt/dev/projects/turboism/cubism-ref/<version>/Live2D_Cubism.jar`.
- Every size and SHA-256 agrees with `reviewed-releases.json`.

| Reviewed version | Argument count | JAR size | SHA-256 |
| --- | --- | --- | --- |
| 5.0.00 | 2 | 36091669 | `42ee222a05478f48739bb1fbf7e7bc7350b21004e506e1b555d1d55c584d99cd` |
| 5.0.01 | 2 | 36099677 | `1aa93b1d9d8da88e8c28de06b680c41a73fc70243307d856d4a2967e6e487a0b` |
| 5.0.02 | 2 | 36100830 | `39e73c8238acba5270422a6e0f228c66a733177d009c710721381285ab0af072` |
| 5.0.03 | 2 | 36098490 | `05bc9b872527fed3dc2641ff833b03d86e6fdafbe6faa294eb3d0afcc0f88a66` |
| 5.0.04 | 2 | 36098501 | `7298c5731525a2bd5ac4e6d5246f1f08603651b0ec37cf5dc3e8ad1802ad8758` |
| 5.0.05 | 2 | 36644752 | `1225893a466d486415773b0a62ec61a75badb2cb5cb2fce8ae744b0123becec4` |
| 5.0.06 | 2 | 36677328 | `cbb9d9ecddb9be0252733ece055ecae7d709de5c56647b7226f6c44607e646f2` |
| 5.1.00 | 2 | 38664130 | `cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05` |
| 5.1.01 | 2 | 38682375 | `ddb1f5569bc8da49f3c9f6b6eaec3fe57e4f7abec67c9c44079233ea51056c02` |
| 5.1.02 | 2 | 38910839 | `0567130093015c969d32b91c6a48d4603c28c8224c6a14fa969491f8974f63ca` |
| 5.1.03 | 2 | 38910843 | `a3d916077bd7a176345233da95f1eb0a8bbdf8967fd08162776d5bf6d746009b` |
| 5.2.00 | 2 | 40625430 | `25c41c81ddb3af884b7f0d61a02eba9b09f0412bdc025997c419de7ab7158cf1` |
| 5.2.01 | 2 | 40625435 | `c1b70ca8c571c4f4df4ebce63b3381f6bed1b132ab0ed539046fd0609e2b00ac` |
| 5.2.02 | 2 | 40626770 | `ba1f31a1846490c3f111a4ae99887c36bcc0c1bc1a3cf970eb5a7aeed7902aff` |
| 5.2.03 | 2 | 40805584 | `bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd` |
| 5.3.00 | 3 | 41859315 | `c89dcaf17b6bfad7227db30bffc2f29cdf49b8e4b0cec1f12af2d85b1c13e767` |
| 5.3.01 | 3 | 41862401 | `694bb49c66ddcf15695dbd8f66e1cbaeb0097f651e2e77c327567244f801d2f4` |
| 5.3.02 | 3 | 41922739 | `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21` |
| 5.3.03 | 3 | 42010633 | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |
| 5.3.04 | 3 | 42203164 | `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d` |

Recheck a single version:

```bash
javap -p -s -classpath /opt/dev/projects/turboism/cubism-ref/<version>/Live2D_Cubism.jar com.live2d.graphics3d.material.GMaterial
```

The check uses the `parse_class` of `scripts/dev/cubism_matrix/bytecode.py`.
The input version set comes from `reviewed-releases.json`.
The test version set comes from `ReviewedHostArtifacts.reviewedCubismVersions()`.
The test states the verified signature boundary with `ReviewedVersions.since("5.3.00")`.

Production code binds by the actual signature. It never selects a method from that boundary.
A missing, ambiguous, static, or wrong-return-type member refuses the binding.
The constructor still checks the reviewed JAR identity first.

This round reads the classfile only. Cubism was neither started nor connected.
