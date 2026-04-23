package org.unicitylabs.faucet;

import org.junit.Test;

import static org.junit.Assert.*;

public class NametagCacheTest {

    @Test
    public void missReturnsNull() {
        NametagCache cache = new NametagCache(60_000L, 100);
        assertNull(cache.get("nobody"));
        assertEquals(0, cache.size());
    }

    @Test
    public void putThenGetReturnsCachedValue() {
        NametagCache cache = new NametagCache(60_000L, 100);
        cache.put("alice", "abcd");
        assertEquals("abcd", cache.get("alice"));
        assertEquals(1, cache.size());
    }

    @Test
    public void repeatedPutsForSameKeyDontGrowCache() {
        NametagCache cache = new NametagCache(60_000L, 100);
        cache.put("alice", "abcd");
        cache.put("alice", "abcd");
        cache.put("alice", "abcd");
        assertEquals(1, cache.size());
    }

    @Test
    public void entryExpiresAfterTtl() throws Exception {
        NametagCache cache = new NametagCache(30L, 100); // 30ms TTL
        cache.put("alice", "abcd");
        assertNotNull(cache.get("alice"));
        Thread.sleep(60L);
        assertNull("entry should have expired", cache.get("alice"));
    }

    @Test
    public void zeroTtlMeansInstantExpiry() {
        NametagCache cache = new NametagCache(0L, 100);
        cache.put("alice", "abcd");
        assertNull(cache.get("alice"));
    }

    @Test
    public void capacityLimitPreventsUnboundedGrowth() {
        NametagCache cache = new NametagCache(60_000L, 3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.put("d", "4"); // should be rejected (cache full of non-expired entries)
        assertEquals(3, cache.size());
        assertNull(cache.get("d"));
    }

    @Test
    public void expiredEntriesReclaimedWhenAtCapacity() throws Exception {
        NametagCache cache = new NametagCache(30L, 3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        Thread.sleep(60L); // let entries expire
        cache.put("d", "4"); // should trigger eviction and then put 'd'
        assertEquals("4", cache.get("d"));
    }

    @Test
    public void concurrentPutsAndGetsDoNotCorruptCache() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 10_000);
        int threads = 8;
        int perThread = 500;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    String key = "nametag-" + threadId + "-" + i;
                    cache.put(key, "pk-" + i);
                    assertEquals("pk-" + i, cache.get(key));
                }
            });
            workers[t].start();
        }
        for (Thread w : workers) w.join();
        assertEquals(threads * perThread, cache.size());
    }
}
