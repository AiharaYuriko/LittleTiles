# Exclusive render-cache ownership: sourcefix3

The user confirmed that sourcefix2 does not resolve the visible reproduction: carve a through-hole spanning two block positions, then deepen a small piece on either side of their boundary. The supplied instance log confirms the sourcefix2 pair and two LT rendering threads. These findings do not establish the cause of the visible missing faces.

## Native defects reproduced on sourcefix2

1. `checkLoaded()` checks `isBlocked()` separately from `startBuildingCache()`. Two workers can both pass the check and acquire the same mutable render-box cache. The cache acquisition itself did not reject an existing owner.
2. Every worker iteration called `unsetBlocked()` in its cleanup, including an iteration rejected by `checkLoaded()` before it acquired anything. Such a task releases another worker's reservation and can drive the counter negative.
3. The empty-entity queue shortcut acquired the cache but did not release it. Later work could remain blocked and enter the faulty rejected-task cleanup above.

These are LT defects independent of material selection and connected-texture providers. Shared boxes contain mutable face decisions and quad caches used by the renderer. Exclusive ownership is required while constructing them and consuming them into the mesh; the tests do not establish that the screenshot followed this interleaving.

## Source changes

- Atomically reserve the cache only when the blocked count is zero. A rejected reservation returns `BUILDING_BLOCKED` without consuming an invalidation request or changing the counter.
- Keep acquisition ownership in the worker's local iteration, since a requeued context can be polled by another worker before the original cleanup finishes. Only an iteration that acquired the reservation releases it, including when error cleanup throws.
- Release the empty-entity shortcut's reservation in `finally`. Queue a deferred request when another builder or a grid conversion holds it.

CreativeCore has no new functional changes for sourcefix3. It is rebuilt from the existing paired source branch. No standalone compatibility mod or post-build class patch is required.

## Verification

Run `./gradlew :LittleTiles:boundaryRenderRegression :LittleTiles:renderBuildOwnershipRegression` in the paired development workspace. The isolated regression source set and its minimal render-layer fixture are excluded from the runtime and source JARs.

`RenderBuildOwnershipRegression` tests the real render-manager acquisition with two barrier-synchronized workers for 100 iterations, conversion contention, and extracted native cleanup and empty-queue methods with controlled entity fixtures. All three groups fail on the actual sourcefix2 runtime JAR. Sourcefix3 passes 310 assertions. The prior 54 boundary assertions and existing source/package, material, coverage and Sodium emitter checks also pass.

These are offline regression results, without Minecraft startup or Mixin application. The user's visible scene still requires an in-game check with the paired JARs and the original two-thread configuration.
