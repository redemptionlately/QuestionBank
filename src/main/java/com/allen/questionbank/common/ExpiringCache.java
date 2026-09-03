package com.allen.questionbank.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Small process-local cache used as a baseline before Redis is introduced. */
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
        V loaded = loader.get();
        if (loaded != null) entries.put(key, new Entry<>(loaded, Instant.now().plus(ttl)));
        return loaded;
    }
    public void evict(K key) { entries.remove(key); }
    public void clear() { entries.clear(); }
}
