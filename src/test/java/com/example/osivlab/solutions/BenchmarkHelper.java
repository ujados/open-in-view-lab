package com.example.osivlab.solutions;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Simple benchmark helper: warmup + N repetitions + median.
 */
public final class BenchmarkHelper {

    public record Result(long medianMs, long minMs, long maxMs, long[] allMs) {
        @Override
        public String toString() {
            return String.format("%,dms (min=%,d, max=%,d)", medianMs, minMs, maxMs);
        }
    }

    /**
     * Runs warmupRuns + measuredRuns, returns median of measured runs.
     * The supplier should execute the operation and return the result count (for assertion).
     */
    public static Result measure(int warmupRuns, int measuredRuns, Supplier<Integer> operation) {
        // Warmup
        for (int i = 0; i < warmupRuns; i++) {
            operation.get();
        }

        // Measured runs
        long[] times = new long[measuredRuns];
        for (int i = 0; i < measuredRuns; i++) {
            long start = System.currentTimeMillis();
            operation.get();
            times[i] = System.currentTimeMillis() - start;
        }

        Arrays.sort(times);
        long median = times[measuredRuns / 2];
        long min = times[0];
        long max = times[measuredRuns - 1];

        return new Result(median, min, max, times);
    }

    private BenchmarkHelper() {}
}
