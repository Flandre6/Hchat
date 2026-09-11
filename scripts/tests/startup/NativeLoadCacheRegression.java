package h.Hchat.loader.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class NativeLoadCacheRegression {
    public static void main(String[] args) throws Exception {
        NativeLoadCache cache = new NativeLoadCache();
        ClassLoader loader = new ClassLoader() {};
        AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) results.add(pool.submit(() -> {
                start.await();
                return cache.load(loader, "dexkit", () -> { loads.incrementAndGet(); return true; });
            }));
            start.countDown();
            for (Future<Boolean> result : results) check(result.get(5, TimeUnit.SECONDS), "load result");
            check(loads.get() == 1, "same library loaded repeatedly");
            check(cache.load(loader, "silk", () -> { loads.incrementAndGet(); return true; }), "second library");
            check(loads.get() == 2, "different library skipped");
            check(cache.load(new ClassLoader() {}, "dexkit", () -> { loads.incrementAndGet(); return true; }), "second loader");
            check(loads.get() == 3, "different loader reused wrong success");
            AtomicInteger retries = new AtomicInteger();
            check(!cache.load(loader, "optional", () -> { retries.incrementAndGet(); return false; }), "failure changed to success");
            check(cache.load(loader, "optional", () -> { retries.incrementAndGet(); return true; }), "failure not retried");
            check(retries.get() == 2, "failure cached");
            boolean threw = false;
            try { cache.load(loader, "mandatory", () -> { throw new IllegalStateException("test"); }); }
            catch (IllegalStateException expected) { threw = true; }
            check(threw, "exception swallowed");
            check(cache.load(loader, "mandatory", () -> true), "exception cached");
        } finally { pool.shutdownNow(); }
        System.out.println("PASS: native success reused under concurrent calls; failures retry; libraries and loaders isolated");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
