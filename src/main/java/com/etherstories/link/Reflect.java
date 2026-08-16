package com.etherstories.link;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class.getMethods() 会把这个类所有方法签名里的类型全部解析一遍。
 * 模组的物品类里带着渲染方法，签名指向 PoseStack / AbstractClientPlayer 这些客户端类，
 * 服务端上 Forge 的 RuntimeDistCleaner 会拒绝加载并抛异常。
 * 一次无所谓，但放在按物品调用的热路径上就是每 tick 几千次异常 + 类加载，主线程直接卡死。
 * 所以按类缓存一次，失败也缓存。
 */
final class Reflect {
    private static final Method[] NONE = new Method[0];
    private static final Map<Class<?>, Method[]> CACHE = new ConcurrentHashMap<>();

    private Reflect() {}

    static Method[] methods(Class<?> type) {
        if (type == null) return NONE;
        Method[] hit = CACHE.get(type);
        if (hit != null) return hit;
        Method[] found;
        try {
            found = type.getMethods();
        } catch (Throwable t) {
            found = NONE;
        }
        CACHE.put(type, found);
        return found;
    }

    static Method[] of(Object target) {
        return target == null ? NONE : methods(target.getClass());
    }

    private static final Map<String, Object> LOOKUP = new ConcurrentHashMap<>();
    private static final Object MISS = new Object();

    /** getMethod 也会解析整张声明方法表，代价和 getMethods 一样，命中和未命中都得缓存。 */
    static Method method(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        if (type == null) throw new NoSuchMethodException(name);
        StringBuilder sb = new StringBuilder(type.getName()).append('#').append(name);
        for (Class<?> p : params) sb.append('/').append(p == null ? "null" : p.getName());
        String id = sb.toString();
        Object hit = LOOKUP.get(id);
        if (hit == MISS) throw new NoSuchMethodException(id);
        if (hit != null) return (Method) hit;
        Method found = resolve(type, name, params);
        if (found == null) {
            LOOKUP.put(id, MISS);
            throw new NoSuchMethodException(id);
        }
        LOOKUP.put(id, found);
        return found;
    }

    /**
     * 从最上层祖先往下找。模组的物品类自己带渲染方法，去解析它的方法表就会触发客户端类加载；
     * 在 net.minecraft.world.item.Item 这种基类上拿到的 Method 一样是虚派发，结果不变。
     */
    private static Method resolve(Class<?> type, String name, Class<?>... params) {
        java.util.ArrayDeque<Class<?>> chain = new java.util.ArrayDeque<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) chain.addFirst(c);
        for (Class<?> c : chain) {
            try {
                return c.getMethod(name, params);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static Method find(Class<?> type, String name, int params) {
        for (Method m : methods(type)) {
            if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        }
        return null;
    }
}
