# Boundary geometry refresh after editing: sourcefix2

The reported reproduction uses ordinary materials: carve a through-hole across two adjacent block positions, then deepen a small piece on either side of the shared boundary. Some faces disappear after editing and can return on a later update. The test uses the paired sourcefix1 JARs with no standalone compatibility mods.

Three deterministic offline regression groups failed on sourcefix1:

1. A neighbor update arriving during cached-quad invalidation was cleared by `beforeBuilding` after the new update had already requested a follow-up build.
2. A new render box reused an outside face's saved occlusion flag instead of evaluating the current client neighbor geometry. Both stale-hidden and stale-visible decisions are covered.
3. The deferred refresh introduced in sourcefix1 skipped ordinary-material LT entities because it was restricted to the Yuushya material adapter.

## Changes

- Publish the neighbor-dirty flag and its queue request under the render-manager monitor. Consume the flag at build entry, before doing geometry/quad invalidation, so updates arriving during that work remain pending.
- Recheck external faces against current client neighbors when creating render boxes. Preserve the material pointer used by subsequent neighbor rechecks. Internal face flags keep their existing behavior.
- Refresh every loaded LT entity at the edited position and its six adjacent positions, independently of material support. Mark ordinary terrain there dirty as well. Use the neighbor-update path instead of dropping only the render boxes.

CreativeCore has no additional functional changes for sourcefix2. Use the previously paired Core source branch.

## Reproducible offline test

In the paired ForgeMods development workspace, run `./gradlew :LittleTiles:boundaryRenderRegression`. The isolated `regression` source set contains a test-only `RenderType` fixture; it is not part of the main source set or the published mod JAR.

The test exercises the real render-manager entry point with a deterministic update interleaving and extracted native methods with controlled geometry/world fixtures. It tests lost notifications, all six stale boundary-face decisions, and ordinary-material neighbor redraws. It does not launch Minecraft or recreate the user's visible scene.

The reported scene remains an in-game verification requirement. Passing these tests demonstrates that the three defects above are addressed, not that every missing-face cause has been excluded.

The full sourcefix2 build and `boundaryRenderRegression` passed with 54 assertions. The existing offline checks also passed: 231 native/package assertions, 28 material-state assertions, 40,000 independent coverage checks and six Sodium emitter cases.
