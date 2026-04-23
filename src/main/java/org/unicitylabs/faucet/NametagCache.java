package org.unicitylabs.faucet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory TTL cache for nametag → Nostr pubkey resolutions.
 *
 * Only successful resolutions are cached. Misses and errors are not cached
 * so a newly-minted nametag becomes resolvable without waiting for a TTL.
 */
public class NametagCache {

    private static final Logger logger = LoggerFactory.getLogger(NametagCache.class);

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;

    public NametagCache(long ttlMillis, int maxEntries) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
    }

    public String get(String nametag) {
        Entry e = cache.get(nametag);
        if (e == null) return null;
        if (System.currentTimeMillis() >= e.expiresAt) {
            cache.remove(nametag, e);
            return null;
        }
        return e.pubkey;
    }

    public void put(String nametag, String pubkey) {
        if (cache.size() >= maxEntries) {
            evictExpired();
            if (cache.size() >= maxEntries) {
                logger.debug("Nametag cache at capacity ({}), skipping put for {}", maxEntries, nametag);
                return;
            }
        }
        cache.put(nametag, new Entry(pubkey, System.currentTimeMillis() + ttlMillis));
    }

    public int size() {
        return cache.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : cache.entrySet()) {
            if (now >= e.getValue().expiresAt) {
                cache.remove(e.getKey(), e.getValue());
            }
        }
    }

    private static final class Entry {
        final String pubkey;
        final long expiresAt;

        Entry(String pubkey, long expiresAt) {
            this.pubkey = pubkey;
            this.expiresAt = expiresAt;
        }
    }
}
