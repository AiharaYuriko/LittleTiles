import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.Direction;
import team.creative.creativecore.common.util.math.base.Axis;
import team.creative.creativecore.common.util.math.base.Facing;
import team.creative.creativecore.common.util.type.list.Pair;

/** Executes original+0.2.17 neighbor callback and native methods against the same controlled world; test-only. */
public final class PatchNeighborComparison implements Opcodes {
    static int checks;
    public static void main(String[] args) throws Exception {
        var original = probe(Path.of(args[2]), "OriginalNeighbor", false);
        var nativeMethod = probe(Path.of(args[0]), "NativeNeighbor", false);
        var patch = probe(Path.of(args[1]), "PatchNeighbor", true);
        Object oldReceiver = original.getDeclaringClass().getConstructor().newInstance();
        Object nativeReceiver = nativeMethod.getDeclaringClass().getConstructor().newInstance();
        Object patchReceiver = patch.getDeclaringClass().getConstructor().newInstance();
        int differing = 0, addedHiding = 0, positionsDiffer = 0;
        Pos origin = new Pos(602, 86, 10);
        for (Direction side : Direction.values()) for (boolean contactCache : List.of(false, true))
            for (boolean backCache : List.of(false, true)) for (boolean geometryFull : List.of(false, true)) {
                Tiles tiles = new Tiles(geometryFull, side);
                if (contactCache) tiles.sideCache.covered.add(side);
                if (backCache) tiles.sideCache.covered.add(side.getOpposite());
                View world = new View(tiles);
                State oldNeighbor = new State(true), newNeighbor = new State(true);
                boolean raw = (boolean) original.invoke(oldReceiver, world, origin, null, oldNeighbor, side);
                var callback = new CallbackInfoReturnable<Boolean>("hidesNeighborFace", true, raw);
                patch.invoke(patchReceiver, world, origin, null, oldNeighbor, side, callback);
                boolean reference = callback.getReturnValue();
                boolean actual = (boolean) nativeMethod.invoke(nativeReceiver, world, origin, null, newNeighbor, side);
                check(!reference || raw, "0.2.17 never adds hiding to an original false decision");
                check(geometryFull || !reference && !actual, "a physical half-face cannot hide the whole neighbor");
                if (reference != actual) { differing++; if (actual && !raw) addedHiding++; }
                if (reference && actual && !Objects.equals(oldNeighbor.sampled, newNeighbor.sampled)) positionsDiffer++;
        }
        System.out.println("COMPARE neighbor decisions: " + differing + "/48 differ; native adds hiding in " + addedHiding
            + " cases; solidity query positions differ in " + positionsDiffer + " shared-positive cases");
        check(differing == 0, "native decisions must match the effective original+0.2.17 callback on all 48 inputs");
        check(positionsDiffer == 0, "controlled parity build retains the reference solidity sampling position");
        System.out.println("PASS patch neighbor comparison: " + checks + " assertions; actual JAR methods, independent cached/physical coverage inputs (no game/Mixin claim)");
    }

    static Method probe(Path jarPath, String name, boolean patch) throws Exception {
        String owner = patch ? "com/yuushya/compat/connected/mixin/NeighborFaceMixin" : "team/creative/littletiles/common/block/mc/BlockTile";
        ClassNode source = read(jarPath, owner);
        MethodNode method = source.methods.stream().filter(m -> m.name.equals(patch ? "yuushyaConnected$verifyBoundary" : "hidesNeighborFace")).findFirst().orElseThrow();
        Map<String, String> names = mapping(); names.put(owner, patch ? name : Type.getInternalName(Lookup.class));
        String coverageName = name + "Coverage";
        names.put("team/creative/littletiles/common/math/face/NeighborFaceCoverage", coverageName);
        Remapper remapper = new SimpleRemapper(names);
        ClassWriter out = writer(name);
        for (FieldNode field : patch ? source.fields : List.<FieldNode>of())
            out.visitField(ACC_PUBLIC | ACC_STATIC, field.name, field.desc, null, null).visitEnd();
        var target = out.visitMethod(ACC_PUBLIC, "test", remapper.mapMethodDesc(method.desc), null, null);
        method.accept(new MethodRemapper(target, remapper) {
            @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                // The world fixture is a class; adapt only its interface invocation opcode.
                if (owner.equals("net/minecraft/world/level/BlockGetter")) { opcode = INVOKEVIRTUAL; isInterface = false; }
                super.visitMethodInsn(opcode, owner, name, desc, isInterface);
            }
        });
        out.visitEnd(); ProbeLoader loader = new ProbeLoader();
        if (!patch && Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                && call.owner.equals("team/creative/littletiles/common/math/face/NeighborFaceCoverage"))) {
            ClassNode helper = read(jarPath, "team/creative/littletiles/common/math/face/NeighborFaceCoverage");
            ClassWriter coverage = writer(coverageName);
            MethodNode full = helper.methods.stream().filter(m -> m.name.equals("full")).findFirst().orElseThrow();
            full.accept(new MethodRemapper(coverage.visitMethod(ACC_PUBLIC | ACC_STATIC, "full", remapper.mapMethodDesc(full.desc), null, null), remapper));
            coverage.visitEnd(); loader.define(coverageName, coverage.toByteArray());
        }
        Class<?> cls = loader.define(name, out.toByteArray());
        if (patch) for (var field : cls.getFields()) if (field.getType() == AtomicInteger.class) field.set(null, new AtomicInteger());
        return patch ? cls.getMethod("test", View.class, Pos.class, State.class, State.class, Direction.class, CallbackInfoReturnable.class)
            : cls.getMethod("test", View.class, Pos.class, State.class, State.class, Direction.class);
    }

    static Map<String, String> mapping() {
        Map<String, String> names = new HashMap<>();
        names.put("net/minecraft/world/level/BlockGetter", Type.getInternalName(View.class));
        names.put("net/minecraft/core/BlockPos", Type.getInternalName(Pos.class));
        names.put("net/minecraft/world/level/block/state/BlockState", Type.getInternalName(State.class));
        names.put("net/minecraft/world/level/block/entity/BlockEntity", "java/lang/Object");
        names.put("team/creative/littletiles/common/block/entity/BETiles", Type.getInternalName(Tiles.class));
        names.put("team/creative/littletiles/common/block/entity/BETiles$SideSolidCache", Type.getInternalName(SideCache.class));
        names.put("team/creative/littletiles/common/block/entity/BETiles$SideState", Type.getInternalName(Side.class));
        names.put("team/creative/littletiles/common/block/little/tile/parent/IParentCollection", Type.getInternalName(Parent.class));
        names.put("team/creative/littletiles/common/block/little/tile/LittleTile", Type.getInternalName(Tile.class));
        names.put("team/creative/littletiles/common/math/box/LittleBox", Type.getInternalName(Box.class));
        names.put("team/creative/littletiles/common/grid/LittleGrid", Type.getInternalName(Grid.class));
        names.put("com/yuushya/compat/connected/BoundaryCoverage", "team/creative/littletiles/common/math/face/BoundaryCoverage");
        names.put("com/yuushya/compat/connected/BoundaryCoverage$Rect", "team/creative/littletiles/common/math/face/BoundaryCoverage$Rect");
        names.put("com/yuushya/compat/connected/MaterialDiagnostics", Type.getInternalName(Diagnostics.class));
        return names;
    }
    static ClassWriter writer(String name) {
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS); out.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor ctor = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0); ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN); ctor.visitMaxs(1, 1); ctor.visitEnd(); return out;
    }
    static final class ProbeLoader extends ClassLoader {
        ProbeLoader() { super(PatchNeighborComparison.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
    static ClassNode read(Path path, String owner) throws Exception { try (JarFile jar = new JarFile(path.toFile())) {
        ClassNode node = new ClassNode(); new ClassReader(jar.getInputStream(jar.getJarEntry(owner + ".class"))).accept(node, 0); return node;
    } }
    static void check(boolean value, String description) { checks++; if (!value) throw new AssertionError(description); }
    public record Pos(int x, int y, int z) { public Pos relative(Direction d) { return new Pos(x+d.getStepX(), y+d.getStepY(), z+d.getStepZ()); } }
    public static final class View { final Tiles tiles; View(Tiles tiles) { this.tiles = tiles; } public Object getBlockEntity(Pos pos) { return tiles; } }
    public static final class State { final boolean solid; Pos sampled; State(boolean solid) { this.solid = solid; }
        public boolean isSolidRender(View world, Pos pos) { sampled = pos; return solid; } }
    public static final class Tiles {
        public final SideCache sideCache = new SideCache(); final boolean physicalFull; final List<Pair<Parent, Tile>> pairs;
        Tiles(boolean full, Direction side) {
            physicalFull = full; Box box = new Box(); if (!full) box.min[Facing.get(side).one().ordinal()] = 8;
            pairs = List.of(new Pair<>((Parent) () -> new Grid(), new Tile(box)));
        }
        public boolean hasLoaded() { return true; } public Iterable<Pair<Parent, Tile>> allTiles() { return pairs; }
    }
    public static final class SideCache { final Set<Direction> covered = EnumSet.noneOf(Direction.class); public Side get(Facing side) { return new Side(covered.contains(side.toVanilla())); } }
    public static final class Side { final boolean full; Side(boolean full) { this.full = full; } public boolean doesBlockLight() { return full; } }
    public interface Parent { Grid getGrid(); }
    public static final class Grid { public final int count = 16; }
    public static final class Tile implements Iterable<Box> { final Box box; Tile(Box box) { this.box = box; }
        public boolean doesProvideSolidFace() { return true; } public Iterator<Box> iterator() { return List.of(box).iterator(); } }
    public static final class Box {
        final int[] min = {0,0,0}, max = {16,16,16};
        public boolean isFaceAtEdge(Grid grid, Facing side) { return side.positive ? getMax(side.axis) == grid.count : getMin(side.axis) == 0; }
        public int getMin(Axis axis) { return min[axis.ordinal()]; } public int getMax(Axis axis) { return max[axis.ordinal()]; }
    }
    public static final class Lookup { public static Tiles loadBE(View world, Pos pos) { return world.tiles; } }
    public static final class Diagnostics { public static final boolean ENABLED = false; }
}
