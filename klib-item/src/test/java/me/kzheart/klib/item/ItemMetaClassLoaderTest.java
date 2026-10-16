package me.kzheart.klib.item;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ItemMetaClassLoaderTest {
    public static class Feature {
        public boolean hasCustomModelData() { return true; }
    }

    @Test
    void sameBinaryNameInTwoClassLoadersHasIndependentMethodCache() throws Exception {
        String name = Feature.class.getName();
        byte[] bytes;
        try (InputStream input = Feature.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            bytes = output.toByteArray();
        }
        Class<?> first = new IsolatedLoader(name, bytes).loadClass(name);
        Class<?> second = new IsolatedLoader(name, bytes).loadClass(name);
        assertNotSame(first, second);
        Method find = ItemMetaReflection.class.getDeclaredMethod("find", Class.class, String.class, Class[].class);
        find.setAccessible(true);
        assertSame(first, found(find, first).getDeclaringClass());
        assertSame(second, found(find, second).getDeclaringClass());
    }

    private static Method found(Method find, Class<?> owner) throws Exception {
        Optional<?> value = (Optional<?>) find.invoke(null, owner, "hasCustomModelData", new Class<?>[0]);
        return (Method) value.get();
    }

    private static final class IsolatedLoader extends ClassLoader {
        private final String name;
        private final byte[] bytes;
        IsolatedLoader(String name, byte[] bytes) { super(Feature.class.getClassLoader()); this.name = name; this.bytes = bytes; }
        @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(requested)) return super.loadClass(requested, resolve);
            Class<?> type = findLoadedClass(requested);
            if (type == null) type = defineClass(requested, bytes, 0, bytes.length);
            if (resolve) resolveClass(type);
            return type;
        }
    }
}
