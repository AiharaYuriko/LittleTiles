# Native connected material rendering: sourcefix1

This experimental Minecraft 1.21.1 / NeoForge change moves the tested Yuushya connected-texture implementation into LittleTiles and its CreativeCore model collection path. It does not require the separate Yuushya/LT compatibility mods.

Baseline: CreativeMD/LittleTiles `1.21`, commit `f7d8c2a05da59a78cd1c6691c55191503a1db789` (1.6.0-pre233).

## Implementation

- Neighbor changes clear native quad caches under the existing rebuild lock before generating new geometry. Cached and newly generated render boxes both receive the temporary connected material state before model selection.
- `BlockTile.hidesNeighborFace` uses the LT-to-neighbor direction supplied by NeoForge and the neighbor's actual position for solidity queries. A rectangle-union coverage check verifies that solid tile geometry really covers the whole boundary. Deformed boxes are excluded conservatively, and adjacent entity locks are not acquired by render workers.
- Pillar/beam adaptation uses material presence at block positions for display connectivity. It does not change saved tile states or server-side states. Missing axial end faces can use a capped display state through the same native collection and clipping path.
- Load and edit events queue a coalesced client refresh after entities are installed in their chunks, including neighboring ordinary blocks and independent LT render buffers.
- An optional Continuity/FRAPI collector is registered with CreativeCore only when the backend is present. It preserves native Sodium emitter behavior, split-quad output and transform ordering. External vanilla/Sodium/Continuity hooks use a presence-gated mixin configuration.

The present material adapter intentionally covers the verified Yuushya pillar/beam resources and properties. For an upstream API, expose third-party material adapters separately from the general cache, coverage and model-source fixes.

## Build and installation

Build together with [the CreativeCore source change](https://github.com/AiharaYuriko/CreativeCore/tree/codex/source-native-connected-fix) in the upstream [ForgeMods development setup](https://github.com/CreativeMD/ForgeMods). The existing source dependency on `:CreativeCore` is retained. The tested build selected only these two projects with Java 21, Minecraft 1.21.1 and NeoForge 21.1.233.

Replace both mod JARs together. Remove previous `nativefix1` test JARs and all three standalone compatibility mods: `yuushya_lt_connected_textures_compat`, `yuushya_lt_ctm_compat`, and `yuushya_lt_fusion_compat`. The metadata rejects these overlapping compatibility mods. Keep Yuushya, the selected Fusion/NeoContinuity backend, their normal dependencies and the matching resource packs.

Official mod version numbers are retained; the local test build uses a `sourcefix1` filename/manifest marker. This branch is experimental and has not been released to a mod distribution service.

## Validation

The full paired source build passed. An external offline regression harness, without standalone compatibility JARs or their class directories, passed 231 native/package assertions, 28 material-state assertions, 40,000 independent coverage raster/axis checks and six real Sodium 0.8.13 emitter cases.

Game-dependent checks use extracted-method fixtures and test-only render-layer identities; no test fixture classes are shipped. Minecraft launch, Mixin application and scene rendering remain unverified. In-game checks should cover pillar holes, beam caps, ordinary-block/LT connections in both directions, editing refreshes, saving/reloading, and Fusion/NeoContinuity scenes separately.
