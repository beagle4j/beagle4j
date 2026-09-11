package com.example.benchmark;

import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.model.CallSite;
import io.github.beagle4j.core.stack.CallSiteResolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The design decision behind attribution, measured rather than assumed.
 *
 * <p>Finding the line of user code that issued a statement means locating one frame in a
 * deep stack. The conventional idiom is {@code new Throwable().getStackTrace()}, which
 * materialises every frame eagerly; {@link StackWalker} streams them and can stop at the
 * first frame that matters. Under a JDBC call in a Spring application the stack is
 * routinely 80 frames of proxies, so the difference is not academic.
 *
 * <p>Both variants pay equally for whatever the JIT does to the recursion that builds the
 * stack, so the ratio between them is the trustworthy part. The absolute numbers are
 * softer.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@org.openjdk.jmh.annotations.Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@State(Scope.Benchmark)
public class StackCaptureBenchmark {

    /** Shallow, and about what sits under a Spring controller. */
    @Param({"20", "80"})
    public int stackDepth;

    private CallSiteResolver resolver;

    @Setup(Level.Trial)
    public void setUp() {
        this.resolver = new CallSiteResolver(BeagleConfig.builder()
                .applicationPackage("com.example")
                .build());
    }

    @Benchmark
    public CallSite stackWalker() {
        return atDepth(stackDepth, () -> resolver.resolve());
    }

    @Benchmark
    public StackTraceElement[] throwable() {
        return atDepth(stackDepth, () -> new Throwable().getStackTrace());
    }

    private <T> T atDepth(int depth, Supplier<T> action) {
        if (depth <= 0) {
            return action.get();
        }
        return atDepth(depth - 1, action);
    }
}
