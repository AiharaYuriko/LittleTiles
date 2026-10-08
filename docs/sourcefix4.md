# Restore effective 0.2.17 behavior: sourcefix4

The user reports that the standalone compatibility patch briefly loses a face after editing and then recovers, while sourcefix1–3 leave the same boundary carving scene broken. The reference is `yuushya-lt-connected-textures-compat-0.2.17-mc1.21.1.jar`, SHA-256 `e5edbee5d99fa718233ffbba88a05b2a4b9065cb6f30aa53c7fb835bb1e6563f`. The inspected instance uses official LT pre233 and Core 2.13.50 with that patch. This comparison reads the actual patch JAR, its active Mixin configuration and the built source JARs.

## Two confirmed migration differences

### Neighbor hiding became stronger

The official `BlockTile.hidesNeighborFace` first consults `sideCache.get(Facing.get(dir).opposite())` and samples `neighborState.isSolidRender(level, pos)`. The active 0.2.17 `NeighborFaceMixin` runs at RETURN only after an original true result. It checks loaded tiles and proves complete solid rectangular coverage on `Facing.get(dir)`. It can withdraw hiding, but cannot turn an original false into true.

The initial source migration also changed the cached side to `Facing.get(dir)` and the solidity sampling position to `pos.relative(dir)`. Those API interpretation changes were not in the tested patch. With independent cached-side and physical-coverage inputs across six directions, sourcefix3 differs from official+0.2.17 in 12 of 48 cases. Six cases introduce hiding where the effective patch returns false; six shared-positive cases sample solidity at a different position. This policy difference existed from sourcefix1 onward.

Sourcefix4 preserves the official preliminary decision and adds the patch's loaded check and actual geometry proof. It deliberately preserves the original opposite-side cache and position lookup for parity with the tested patch. This does not establish that those lookups are the ideal API implementation. Any future direction/position correction should be separately validated rather than bundled into migration of the working patch.

### Deferred refresh stopped rebuilding boxes

The effective patch's delayed `BoundaryRefresh.tick` calls `render.queue(true, false, 0)` for supported material containers. The first argument invalidates the render-box cache. Sourcefix2 generalized the refresh to all materials but called `onNeighbourChanged()`, which queues without discarding boxes. Sourcefix3 retained that change. Their refresh therefore preserves the old box objects despite receiving a neighbor-change notification.

Sourcefix4 adds `onNeighbourChanged(boolean eraseBoxCache)` and uses `onNeighbourChanged(true)` from the delayed refresh. This both preserves the neighbor-change flag required for quad invalidation and requests fresh boxes. Ordinary neighbor notifications retain the existing no-argument behavior. All-material refresh and surrounding section invalidation remain enabled.

Sourcefix1 already requested full rebuilding for supported materials. The refresh regression therefore cannot by itself explain every sourcefix1 failure. The earlier neighbor decision difference is a separate confirmed mismatch. Neither offline comparison identifies which path produced the particular screenshot.

## Excluded explanations

`OcclusionRefreshMixin` is present as a leftover class in the patch, but is not registered in the effective 0.2.17 configuration. The backend plugin excludes it. Restoring this inactive code would not reproduce the user's working patch. The unculled-quad cache and material adapter match their migrated counterparts; Core needs no additional functional change in this revision.

The cache ownership corrections from sourcefix3 are retained. Their previous tests exposed real concurrency defects, but passing those tests did not demonstrate equivalence to 0.2.17 or resolve this visual scene.

## Verification and limits

`PatchRefreshComparison` extracts the actual patch/native delayed refresh methods and the native queue/cache acquisition methods. For three affected supported-material LT containers, sourcefix3 retains old boxes and fails; sourcefix4 requests full rebuilding and discards them, passing 11 assertions. `BoundaryRenderRegression` separately covers all-material refresh, with 57 assertions.

`PatchNeighborComparison` executes the official method followed by the actual patch callback, and the actual native method and native geometry helper, against the same controlled world. Sourcefix3 has 12/48 decision differences; sourcefix4 has 0/48, zero additional hiding and zero shared-positive sampling-position differences, passing 98 assertions. These are controlled inputs, not an exhaustive proof of every block/material state.

The paired source build also passes 231 source/package assertions, 310 cache ownership assertions, 40,000 rectangle coverage cases, 28 material assertions and six Sodium emitter cases. Regression fixtures are excluded from runtime and source JARs. No standalone compatibility mod or post-build bytecode mutation is required.

In the parent paired workspace, run:

```powershell
./build-source-connected-fix.ps1
./SourceNativeFix/compare-patch-behavior.ps1 -NativeVersion sourcefix3 # expected mismatch
./SourceNativeFix/compare-patch-behavior.ps1 -NativeVersion sourcefix4 # expected parity
```

The comparison script needs the locally retained official and 0.2.17 reference JARs and development dependencies. The existing Gradle boundary/ownership regression tasks can run without the external patch comparison setup. This is a background Java regression harness; Minecraft startup, Mixin application and visual recovery in the user's world have not been tested.
