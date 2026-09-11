package com.example.benchmark;

import io.github.beagle4j.core.sql.SqlNormalizer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * What SQL normalisation costs, hit and miss.
 *
 * <p>The number that matters is the hit: normalisation runs on every statement, so if a
 * cache hit were expensive the whole design would need rethinking.
 *
 * <h2>Two traps this benchmark had to be written around</h2>
 * <ol>
 *   <li><b>Warming the cache in the wrong order.</b> The cache stops accepting entries
 *       once full, so the statement under test has to be admitted before the filler runs.
 *       Getting this backwards leaves it permanently uncached and turns the "hit"
 *       benchmark into a re-scan of a long string — which is what the first version of
 *       this file measured, reporting the hit as twice the cost of the miss. There is now
 *       an assertion in {@link #setUp()} that fails the run rather than publishing it.</li>
 *   <li><b>Constant inputs.</b> Calling a pure function with the same constant argument
 *       in a loop lets the JIT hoist the result out entirely; the first corrected run
 *       reported 1.6 ns/op, roughly four cycles, which is not a hash lookup — it is the
 *       compiler declining to do the work twice. The benchmark now rotates through a set
 *       of pre-cached statements so the input varies and the lookup actually happens.</li>
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@org.openjdk.jmh.annotations.Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@State(Scope.Benchmark)
public class SqlNormalizerBenchmark {

    /** A power of two, so the rotation is a mask rather than a division. */
    private static final int VARIANTS = 16;

    private SqlNormalizer normalizer;
    private String[] cached;
    private int cursor;
    private long counter;

    @Setup(Level.Trial)
    public void setUp() {
        this.normalizer = new SqlNormalizer();
        this.cached = new String[VARIANTS];
        for (int i = 0; i < VARIANTS; i++) {
            cached[i] = "SELECT o.id, o.customer_id, o.total FROM orders o "
                    + "WHERE o.customer_id = ? AND o.status = 'OPEN' AND o.region = " + i + " "
                    + "ORDER BY o.created_at DESC";
            // Admitted while there is still room. Order matters: see the class javadoc.
            normalizer.normalize(cached[i]);
        }

        // Fill past the ceiling so that the miss benchmark really misses.
        for (int i = 0; i < 4_000; i++) {
            normalizer.normalize("SELECT * FROM filler WHERE id = " + i + " AND tag = 'x" + i + "'");
        }

        for (String sql : cached) {
            if (normalizer.normalize(sql) != normalizer.normalize(sql)) {
                throw new IllegalStateException(
                        "statement is not memoised; this would measure the wrong thing");
            }
        }
    }

    @Benchmark
    public String normalizeCacheHit() {
        return normalizer.normalize(cached[cursor++ & (VARIANTS - 1)]);
    }

    @Benchmark
    public String normalizeCacheMiss() {
        return normalizer.normalize(
                "SELECT * FROM orders WHERE id = " + (counter++) + " AND note = 'n" + counter + "'");
    }
}
