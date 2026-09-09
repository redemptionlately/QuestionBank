package com.allen.questionbank.jvm;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类加载机制的可复现实验：双亲委派是不是真的？"同一个类"由什么决定？委派能不能被打破？
 *
 * 结论不是背出来的，是下面每个用例跑出来的：
 *  - 委派：给自定义加载器一个 JDK 类，它根本没机会加载（bootstrap 抢先）
 *  - 类身份：类名相同但加载器不同 → 是两个不同的类，互相 cast 直接 ClassCastException
 *  - 打破委派：重写 loadClass 先自己 define，父加载器就靠边站（委派是 loadClass 的默认实现，是约定不是强制）
 *  - TCCL：父加载器加载的代码通过线程上下文加载器"向下"看到子加载器的类（SPI 就是这么干的）
 */
class ClassLoaderEvidenceTest {

    /** 被自定义加载器加载的样本类：故意只依赖 java.lang，避免依赖解析干扰实验。 */
    public static final class Sample {
        public String tag() { return "sample"; }
    }

    /** 从字节流定义类的加载器；defineFirst=true 时重写 loadClass 打破双亲委派。 */
    static final class ByteLoader extends ClassLoader {
        private final String targetName;
        private final byte[] bytes;
        private final boolean defineFirst;

        ByteLoader(String targetName, byte[] bytes, ClassLoader parent, boolean defineFirst) {
            super(parent);
            this.targetName = targetName;
            this.bytes = bytes;
            this.defineFirst = defineFirst;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (defineFirst && targetName.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (targetName.equals(name)) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }

    private static byte[] bytesOf(Class<?> c) throws Exception {
        String res = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(res)) {
            assertNotNull(in, "找不到类文件字节：" + res);
            return in.readAllBytes();
        }
    }

    private static Object newInstance(Class<?> c) throws Exception {
        Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /** 双亲委派：父类能加载的类，自定义加载器根本没机会定义。 */
    @Test
    void delegationGivesJdkClassToBootstrapAndAppClassToAppLoader() throws Exception {
        byte[] bytes = bytesOf(Sample.class);
        ByteLoader custom = new ByteLoader(Sample.class.getName(), bytes,
                ClassLoader.getSystemClassLoader(), false);

        Class<?> stringByCustom = custom.loadClass("java.lang.String");
        Class<?> sampleByCustom = custom.loadClass(Sample.class.getName());

        assertNull(stringByCustom.getClassLoader(),
                "java.lang.String 必须由 bootstrap 加载（getClassLoader() 返回 null）");
        assertSame(String.class, stringByCustom, "委派到顶层后拿到的是同一个 Class 对象");
        assertSame(Sample.class, sampleByCustom,
                "父（应用加载器）能加载 Sample，自定义加载器的 findClass 不会被调用");

        System.out.println("[evidence] 双亲委派：自定义加载器请求 java.lang.String → 实际由 bootstrap 提供(loader=null)；"
                + "请求 Sample → 由应用加载器提供(findClass 未执行)");
    }

    /** 类身份 = 类本身 + 定义它的加载器。同名不同加载器 = 两个不同的类。 */
    @Test
    void sameClassNameFromTwoLoadersAreDifferentTypes() throws Exception {
        byte[] bytes = bytesOf(Sample.class);
        ClassLoader platform = ClassLoader.getPlatformClassLoader(); // 看不到应用类，强制由我们定义

        ByteLoader l1 = new ByteLoader(Sample.class.getName(), bytes, platform, false);
        ByteLoader l2 = new ByteLoader(Sample.class.getName(), bytes, platform, false);
        Class<?> c1 = l1.loadClass(Sample.class.getName());
        Class<?> c2 = l2.loadClass(Sample.class.getName());

        assertEquals(c1.getName(), c2.getName(), "二进制名必须相同");
        assertNotEquals(c1, c2, "不同加载器定义的同名类是两个 Class 对象");
        assertEquals(l1, c1.getClassLoader());
        assertEquals(l2, c2.getClassLoader());

        Object o2 = newInstance(c2);
        assertFalse(c1.isInstance(o2), "c1.isInstance(c2 的实例) 必须为 false");
        assertFalse(Sample.class.isInstance(o2), "应用加载器里的 Sample 也认不出 c2 的实例");
        assertThrows(ClassCastException.class, () -> {
            Sample s = (Sample) o2;   // 编译期是 Sample，运行期是另一个加载器的"同名类"
            assertNotNull(s.tag());
        });

        System.out.println("[evidence] 类身份：同名=" + c1.getName() + " loader1=" + c1.getClassLoader()
                + " loader2=" + c2.getClassLoader() + " | c1!=c2，互转抛 ClassCastException");
    }

    /** 双亲委派是 loadClass 的默认实现——是约定，不是强制。 */
    @Test
    void overridingLoadClassBreaksDelegation() throws Exception {
        byte[] bytes = bytesOf(Sample.class);
        ByteLoader rebel = new ByteLoader(Sample.class.getName(), bytes,
                ClassLoader.getSystemClassLoader(), true);   // defineFirst=true

        Class<?> c = rebel.loadClass(Sample.class.getName());
        assertNotEquals(Sample.class, c, "先自己 define 就绕过了父（应用）加载器");
        assertSame(rebel, c.getClassLoader(), "这一次由自定义加载器自己定义");

        Object o = newInstance(c);
        assertThrows(ClassCastException.class, () -> {
            Sample s = (Sample) o;
            assertNotNull(s.tag());
        });
        System.out.println("[evidence] 打破委派：重写 loadClass 先自己 defineClass，父加载器不再优先；"
                + "与父加载器的同名类互转抛 ClassCastException");
    }

    /** TCCL：父加载器加载的代码靠线程上下文加载器"向下"访问子加载器的类（SPI 的机制基础）。 */
    @Test
    void threadContextClassLoaderExposesChildClassToCaller() throws Exception {
        byte[] bytes = bytesOf(Sample.class);
        ByteLoader child = new ByteLoader(Sample.class.getName(), bytes,
                ClassLoader.getPlatformClassLoader(), false);

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        AtomicRef<Class<?>> seen = new AtomicRef<>();
        try {
            Thread.currentThread().setContextClassLoader(child);
            Thread worker = new Thread(() -> {
                try {
                    seen.value = Thread.currentThread().getContextClassLoader()
                            .loadClass(ClassLoaderEvidenceTest.Sample.class.getName());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            worker.start();
            worker.join(5000);
        } finally {
            Thread.currentThread().setContextClassLoader(original);   // 必须还原，否则污染其他测试
        }

        assertNotNull(seen.value, "通过 TCCL 必须能加载到子类加载器里的类");
        assertSame(child, seen.value.getClassLoader(), "加载到的类由 TCCL 指向的子加载器定义");
        assertSame(ClassLoader.getSystemClassLoader(), Thread.currentThread().getContextClassLoader(),
                "finally 中已还原 TCCL");
        System.out.println("[evidence] TCCL：把线程上下文加载器指向自定义加载器后，代码可加载到该类"
                + "（loader=" + seen.value.getClassLoader() + "），实验后已还原");
    }

    private static final class AtomicRef<T> {
        T value;
    }
}
