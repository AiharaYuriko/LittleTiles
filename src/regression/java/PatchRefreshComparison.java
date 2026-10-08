import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;

/** Compare the effective 0.2.17 refresh bytecode with native code; never launches Minecraft. */
public final class PatchRefreshComparison implements Opcodes {
    static int checks;
    public static void main(String[] args) throws Exception {
        Path nativeJar = Path.of(args[0]), patchJar = Path.of(args[1]);
        var patch = BoundaryRenderRegression.refresh(patchJar, "com/yuushya/compat/connected/BoundaryRefresh", true);
        var nativeRefresh = BoundaryRenderRegression.refresh(nativeJar, "team/creative/littletiles/client/render/material/BoundaryRefresh", true);
        var failures = new ArrayList<String>();
        for (int i = 0; i < patch.size(); i++) {
            var reference = patch.get(i).manager;
            var actual = nativeRefresh.get(i).manager;
            check(reference.requests == 1 && reference.fullRequests == 1, "actual 0.2.17 requests a complete render-box rebuild");
            check(actual.requests == 1, "native deferred refresh emits one request per affected LT entity");
            boolean oldCacheRetained = startsWithOldCache(nativeJar, actual.fullRequests != 0);
            check(!startsWithOldCache(nativeJar, reference.fullRequests != 0), "the patch's full request drops old boxes in the actual native reservation method");
            System.out.println("COMPARE affected LT " + i + ": patch fullRebuild=" + (reference.fullRequests != 0)
                + ", native fullRebuild=" + (actual.fullRequests != 0) + ", native retainsOldBoxes=" + oldCacheRetained);
            if (actual.fullRequests != reference.fullRequests || oldCacheRetained)
                failures.add("LT " + i + " does not preserve the working patch's deferred full rebuild");
        }
        try (JarFile jar = new JarFile(patchJar.toFile())) {
            String config = new String(jar.getInputStream(jar.getJarEntry("yuushya_lt_connected_combined.mixins.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            check(config.contains("BoundaryRefreshMixin") && config.contains("NeighborFaceMixin"), "0.2.17 effective refresh and neighbor hooks are registered");
            check(!config.contains("OcclusionRefreshMixin"), "the old 0.2.14 occlusion hook is not active in this comparison");
        }
        if (!failures.isEmpty()) {
            failures.forEach(message -> System.err.println("FAIL patch refresh parity: " + message));
            throw new AssertionError(failures.size() + " deferred refresh parity failures");
        }
        System.out.println("PASS patch refresh comparison: " + checks + " assertions; actual 0.2.17/native bytecode and native cache reservation (no game/Mixin claim)");
    }

    static boolean startsWithOldCache(Path nativeJar, boolean full) throws Exception {
        String owner = "team/creative/littletiles/client/render/block/BERenderManager", name = "NativeReservationProbe";
        ClassNode source = new ClassNode();
        try (JarFile jar = new JarFile(nativeJar.toFile())) { new ClassReader(jar.getInputStream(jar.getJarEntry(owner + ".class"))).accept(source, 0); }
        Map<String, String> types = new HashMap<>();
        types.put(owner, name);
        types.put("team/creative/littletiles/common/block/entity/BETiles", "java/lang/Object");
        types.put("team/creative/littletiles/client/render/cache/build/RenderingThread", Type.getInternalName(QueueStub.class));
        Remapper remapper = new SimpleRemapper(types);
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        out.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (FieldNode field : source.fields)
            out.visitField(ACC_PUBLIC | (field.access & ACC_STATIC), field.name, remapper.mapDesc(field.desc), null, null).visitEnd();
        MethodVisitor ctor = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0); ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN); ctor.visitMaxs(1, 1); ctor.visitEnd();
        for (String methodName : List.of("queue", "startBuildingCache")) {
            MethodNode method = source.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
            method.accept(new MethodRemapper(out.visitMethod(ACC_PUBLIC, method.name, remapper.mapMethodDesc(method.desc), null, null), remapper));
        }
        out.visitEnd(); byte[] bytes = out.toByteArray();
        Class<?> cls = new ClassLoader(PatchRefreshComparison.class.getClassLoader()) {
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        }.define();
        Object manager = cls.getConstructor().newInstance();
        Object oldBoxes = new Int2ObjectArrayMap<>();
        cls.getField("boxCache").set(manager, oldBoxes);
        cls.getField("blocked").set(manager, new AtomicInteger());
        cls.getMethod("queue", boolean.class, boolean.class, long.class).invoke(manager, full, false, 0L);
        cls.getMethod("startBuildingCache").invoke(manager);
        return cls.getField("boxCache").get(manager) == oldBoxes;
    }
    static void check(boolean value, String description) { checks++; if (!value) throw new AssertionError(description); }
    public static final class QueueStub { public static boolean queue(Object entity, boolean hasPos, long pos) { return true; } }
}
