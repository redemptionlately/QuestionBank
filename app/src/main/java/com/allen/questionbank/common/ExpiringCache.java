package com.allen.questionbank.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 进程内 cache-aside，带单飞（singleflight）防击穿。
 *
 * <p>修复前：getOrLoad 在 miss 时每个线程都直接执行 loader——热点 key 过期瞬间，
 * N 个并发请求会同时打到数据库（缓存击穿）。
 *
 * <p>现在的加载路径分两级：
 * <ul>
 *   <li>fast path 无锁：命中直接返回，热路径零争用；</li>
 *   <li>miss 后 synchronized(this) 粗粒度加锁 + 双检：并发 miss 时只有第一个线程执行
 *       loader，其余线程等锁后复用回填结果；加载时顺手清扫全表过期项。</li>
 * </ul>
 *
 * <p>边界与权衡：粗粒度锁在当前单热点 key 空间（发布试卷缓存，容量 ≤1）没有实际损失；
 * 若未来 key 数量增长，应先改 per-key 锁再谈性能。锁是 JVM 内的，多实例部署时不同实例
 * 仍可能同时 miss（残余并发 = 实例数上限），配合 Redis 缓存侧的 TTL 抖动与数据库兜底可接受。
 */
public class ExpiringCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) {}
    private final Duration ttl;
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

    public ExpiringCache(Duration ttl) { this.ttl = ttl; }
    public V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt().isBefore(Instant.now())) { entries.remove(key, entry); return null; }
        return entry.value();
    }
    public V getOrLoad(K key, Supplier<V> loader) {
        V current = get(key);
        if (current != null) return current;
        synchronized (this) {
            // 双检：等锁期间可能已有线程完成加载
            current = get(key);
            if (current != null) return current;
            // 顺手清扫：把全表过期项一起摘掉，避免冷 key 过期后常驻内存
            Instant now = Instant.now();
            entries.values().removeIf(e -> e.expiresAt().isBefore(now));
            V loaded = loader.get();
            if (loaded != null) entries.put(key, new Entry<>(loaded, Instant.now().plus(ttl)));
            return loaded;
        }
    }
    public void evict(K key) { entries.remove(key); }
    public void clear() { entries.clear(); }

    /** 仅供测试与运维观察内部条目数（验证清扫行为），不属于缓存契约。 */
    int size() { return entries.size(); }
}
