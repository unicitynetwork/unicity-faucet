package org.unicitylabs.faucet;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Tests for {@link FaucetService#resolveNametagDeduplicated}.
 *
 * Covers the in-flight de-dup contract that prevents N concurrent requests
 * for the same nametag from each opening their own Nostr REQ — the bug that
 * tripped the relay's per-connection subscription cap during bursty top-ups.
 */
public class NametagDedupTest {

    @Test
    public void cacheHitShortCircuitsWithoutCallingLoader() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
        cache.put("alice", "abcd");

        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<CompletableFuture<String>> loader = () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture("should-not-be-used");
        };

        String result = FaucetService.resolveNametagDeduplicated("alice", cache, inFlight, loader)
                .get(1, TimeUnit.SECONDS);

        assertEquals("abcd", result);
        assertEquals("loader must not be called on cache hit", 0, loaderCalls.get());
        assertEquals("in-flight map must stay empty", 0, inFlight.size());
    }

    @Test
    public void concurrentSameNametagSharesSingleLoaderInvocation() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch loaderRelease = new CountDownLatch(1);

        Supplier<CompletableFuture<String>> loader = () -> {
            loaderCalls.incrementAndGet();
            loaderEntered.countDown();
            CompletableFuture<String> f = new CompletableFuture<>();
            new Thread(() -> {
                try {
                    loaderRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                f.complete("the-pubkey");
            }, "test-loader-completer").start();
            return f;
        };

        int callers = 5;
        @SuppressWarnings("unchecked")
        CompletableFuture<String>[] futures = new CompletableFuture[callers];
        for (int i = 0; i < callers; i++) {
            futures[i] = FaucetService.resolveNametagDeduplicated(
                    "zacpok", cache, inFlight, loader);
        }

        // Loader must have been entered exactly once.
        assertTrue("loader should have been entered",
                loaderEntered.await(2, TimeUnit.SECONDS));
        assertEquals("exactly one in-flight slot for the shared nametag",
                1, inFlight.size());

        loaderRelease.countDown();

        for (CompletableFuture<String> f : futures) {
            assertEquals("the-pubkey", f.get(2, TimeUnit.SECONDS));
        }

        assertEquals("loader was invoked exactly once", 1, loaderCalls.get());
        assertEquals("in-flight slot must be cleaned up after settle",
                0, inFlight.size());
        assertEquals("successful resolution must be cached",
                "the-pubkey", cache.get("zacpok"));
    }

    @Test
    public void distinctNametagsDoNotShareInFlightSlot() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        Supplier<CompletableFuture<String>> loader = () -> {
            int n = loaderCalls.incrementAndGet();
            CompletableFuture<String> f = new CompletableFuture<>();
            new Thread(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                f.complete("pk-" + n);
            }).start();
            return f;
        };

        CompletableFuture<String> a = FaucetService.resolveNametagDeduplicated(
                "alice", cache, inFlight, loader);
        CompletableFuture<String> b = FaucetService.resolveNametagDeduplicated(
                "bob", cache, inFlight, loader);

        assertEquals("two distinct nametags must each create their own slot",
                2, inFlight.size());
        release.countDown();

        a.get(2, TimeUnit.SECONDS);
        b.get(2, TimeUnit.SECONDS);

        assertEquals("loader called once per distinct nametag", 2, loaderCalls.get());
        assertEquals(0, inFlight.size());
    }

    @Test
    public void nullPubkeyIsNotCachedAndNotLeaked() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

        Supplier<CompletableFuture<String>> loader =
                () -> CompletableFuture.completedFuture(null);

        String result = FaucetService.resolveNametagDeduplicated(
                "ghost", cache, inFlight, loader).get(1, TimeUnit.SECONDS);

        assertNull("null result must propagate", result);
        assertNull("null result must not be cached", cache.get("ghost"));
        assertEquals("in-flight slot must be cleaned up", 0, inFlight.size());
    }

    @Test
    public void loaderExceptionIsPropagatedAndNotCached() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

        Supplier<CompletableFuture<String>> loader = () -> {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("boom"));
            return f;
        };

        CompletableFuture<String> result = FaucetService.resolveNametagDeduplicated(
                "boomtag", cache, inFlight, loader);

        try {
            result.get(1, TimeUnit.SECONDS);
            fail("expected ExecutionException");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof RuntimeException);
            assertEquals("boom", e.getCause().getMessage());
        }

        assertNull("failure must not poison cache", cache.get("boomtag"));
        assertEquals("in-flight slot must be cleaned up after failure",
                0, inFlight.size());
    }

    @Test
    public void synchronousLoaderThrowDoesNotLeakInFlightSlot() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

        Supplier<CompletableFuture<String>> loader = () -> {
            throw new IllegalStateException("synchronous failure");
        };

        CompletableFuture<String> result = FaucetService.resolveNametagDeduplicated(
                "syncfail", cache, inFlight, loader);

        assertTrue("future must be done", result.isDone());
        assertTrue("future must be completed exceptionally",
                result.isCompletedExceptionally());
        assertEquals("in-flight slot must be cleaned up", 0, inFlight.size());
    }

    @Test
    public void afterCacheIsPopulatedSubsequentCallSkipsLoader() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<CompletableFuture<String>> loader = () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture("first-pk");
        };

        // Initial query populates the cache.
        FaucetService.resolveNametagDeduplicated(
                "alice", cache, inFlight, loader).get(1, TimeUnit.SECONDS);

        // Subsequent query must hit the cache, not the loader.
        String second = FaucetService.resolveNametagDeduplicated(
                "alice", cache, inFlight, loader).get(1, TimeUnit.SECONDS);

        assertEquals("first-pk", second);
        assertEquals("loader called only on the first invocation", 1, loaderCalls.get());
    }

    @Test
    public void retryAfterFailureRunsLoaderAgain() throws Exception {
        NametagCache cache = new NametagCache(60_000L, 100);
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<CompletableFuture<String>> failing = () -> {
            loaderCalls.incrementAndGet();
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("transient"));
            return f;
        };

        CompletableFuture<String> first = FaucetService.resolveNametagDeduplicated(
                "alice", cache, inFlight, failing);
        try {
            first.get(1, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException expected) {
            // expected
        }
        assertEquals(1, loaderCalls.get());

        // After failure, retry must invoke loader again (not return stale failure).
        CompletableFuture<String> second = FaucetService.resolveNametagDeduplicated(
                "alice", cache, inFlight, failing);
        try {
            second.get(1, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException expected) {
            // expected
        }
        assertEquals("loader must run again on retry after failure", 2, loaderCalls.get());
    }
}
