package io.liparakis.chunkis.benchmark;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;

/**
 * Lightweight benchmark runner that measures:
 * <ul>
 * <li><b>Runtime</b> – wall-clock time via {@code System.nanoTime()}</li>
 * <li><b>Allocations</b> – thread-level bytes allocated via
 * {@link ThreadMXBean}</li>
 * <li><b>RAM Usage</b> – JVM heap delta (before/after GC) via
 * {@link Runtime}</li>
 * <li><b>CPU Usage per core</b> – derived from
 * {@link ThreadMXBean#getThreadCpuTime} vs
 * elapsed wall time × available processors</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * 
 * <pre>{@code
 * BenchmarkResult result = Benchmark.run("MyBench", 10_000, 500, () -> {
 *     // code under test
 * });
 * System.out.println(result.report());
 * }</pre>
 */
public final class Benchmark {

    private static final ThreadMXBean THREAD_MX;

    static {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        if (raw instanceof ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            sun.setThreadAllocatedMemoryEnabled(true);
            THREAD_MX = sun;
        } else {
            THREAD_MX = null;
        }
        if (ManagementFactory.getThreadMXBean().isThreadCpuTimeSupported()) {
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        }
    }

    private Benchmark() {
    }

    /**
     * Runs a benchmark with default 100 warmup iterations.
     *
     * @param name       human-readable name for the benchmark
     * @param iterations number of measured iterations
     * @param task       the code under test
     * @return {@link BenchmarkResult} with all measured metrics
     */
    public static BenchmarkResult run(String name, int iterations, Runnable task) {
        return run(name, iterations, 100, task);
    }

    /**
     * Runs a benchmark.
     *
     * @param name             human-readable name for the benchmark
     * @param iterations       number of measured iterations
     * @param warmupIterations number of warmup iterations (not measured)
     * @param task             the code under test
     * @return {@link BenchmarkResult} with all measured metrics
     */
    public static BenchmarkResult run(String name, int iterations, int warmupIterations, Runnable task) {
        int availableCores = Runtime.getRuntime().availableProcessors();
        long threadId = Thread.currentThread().threadId();

        // --- Warmup ---
        for (int i = 0; i < warmupIterations; i++) {
            task.run();
        }

        // Force GC before measurement to get a stable heap baseline
        forceGc();

        long ramBefore = usedHeapBytes();

        // --- Measurement ---
        long[] runtimes = new long[iterations];
        long totalAllocations = 0;
        long cpuBefore = threadCpuTime(threadId);
        long wallStart = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            long allocBefore = threadAllocatedBytes(threadId);
            long t0 = System.nanoTime();

            task.run();

            long t1 = System.nanoTime();
            runtimes[i] = t1 - t0;

            long allocAfter = threadAllocatedBytes(threadId);
            if (allocAfter >= allocBefore) {
                totalAllocations += (allocAfter - allocBefore);
            }
        }

        long wallEnd = System.nanoTime();
        long cpuAfter = threadCpuTime(threadId);

        // Force GC before final measurement so pure garbage isn't counted as retained
        // heap
        forceGc();
        long ramAfter = usedHeapBytes();

        // --- Aggregate ---
        long totalRuntime = 0, minRuntime = Long.MAX_VALUE, maxRuntime = 0;
        for (long rt : runtimes) {
            totalRuntime += rt;
            if (rt < minRuntime)
                minRuntime = rt;
            if (rt > maxRuntime)
                maxRuntime = rt;
        }

        long totalWallNs = wallEnd - wallStart;
        long totalCpuNs = Math.max(0, cpuAfter - cpuBefore);

        // avgCpuCoresUsed = cpuTime / (wallTime * availableCores)
        // clamped to [0, availableCores]
        double avgCpuCoresUsed = totalWallNs > 0
                ? Math.min((double) totalCpuNs / totalWallNs, availableCores)
                : 0.0;

        return new BenchmarkResult(
                name,
                iterations,
                warmupIterations,
                totalRuntime,
                iterations > 0 ? totalRuntime / iterations : 0,
                minRuntime == Long.MAX_VALUE ? 0 : minRuntime,
                maxRuntime,
                totalAllocations,
                iterations > 0 ? totalAllocations / iterations : 0,
                ramBefore,
                ramAfter,
                ramAfter - ramBefore,
                totalCpuNs,
                avgCpuCoresUsed,
                availableCores);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long threadAllocatedBytes(long threadId) {
        if (THREAD_MX == null)
            return 0L;
        try {
            return THREAD_MX.getThreadAllocatedBytes(threadId);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long threadCpuTime(long threadId) {
        java.lang.management.ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (!mx.isThreadCpuTimeEnabled())
            return 0L;
        try {
            return mx.getThreadCpuTime(threadId);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void forceGc() {
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}