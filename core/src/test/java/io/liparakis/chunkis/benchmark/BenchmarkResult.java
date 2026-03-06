package io.liparakis.chunkis.benchmark;

/**
 * Holds the results of a single benchmark run.
 */
public record BenchmarkResult(
        String name,
        int iterations,
        int warmupIterations,
        long totalRuntimeNs,
        long avgRuntimeNs,
        long minRuntimeNs,
        long maxRuntimeNs,
        long totalAllocationsBytes,
        long avgAllocationsBytes,
        long ramBeforeBytes,
        long ramAfterBytes,
        long ramDeltaBytes,
        long totalCpuTimeNs,
        double avgCpuCoresUsed,
        int availableCores) {

    /**
     * Returns a formatted report of this benchmark result.
     */
    public String report() {
        return String.format(
                """
                        BENCHMARK: %-47s
                            Iterations:      %,10d  (warmup: %,8d)
                            Available Cores: %,10d
                        RUNTIME
                            Total:           %,10.3f ms
                            Avg / iteration: %,10.3f ms
                            Min / iteration: %,10.3f ms
                            Max / iteration: %,10.3f ms
                        ALLOCATIONS
                            Total:           %,10.2f KB
                            Avg / iteration: %,10.2f KB
                        HEAP RAM USAGE
                            Before:          %,10.2f MB
                            After:           %,10.2f MB
                            Delta (live):    %,10.2f MB
                        CPU USAGE
                            Total CPU time:  %,10.3f ms
                            Avg cores used:  %,10.2f / %-5d cores
                        """,
                name,
                iterations, warmupIterations,
                availableCores,
                totalRuntimeNs / 1_000_000.0,
                avgRuntimeNs / 1_000_000.0,
                minRuntimeNs / 1_000_000.0,
                maxRuntimeNs / 1_000_000.0,
                totalAllocationsBytes / 1024.0,
                avgAllocationsBytes / 1024.0,
                ramBeforeBytes / (1024.0 * 1024.0),
                ramAfterBytes / (1024.0 * 1024.0),
                ramDeltaBytes / (1024.0 * 1024.0),
                totalCpuTimeNs / 1_000_000.0,
                avgCpuCoresUsed, availableCores);
    }
}
