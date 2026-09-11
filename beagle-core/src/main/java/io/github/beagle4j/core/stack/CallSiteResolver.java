package io.github.beagle4j.core.stack;

import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.model.CallSite;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks the current stack and picks the frame that a developer would recognise as
 * "the line that ran this query".
 *
 * <p>Uses {@link StackWalker} rather than {@code new Throwable().getStackTrace()}.
 * The difference matters: filling in a throwable materialises the entire stack eagerly,
 * allocating a {@code StackTraceElement} per frame, whereas {@code StackWalker} streams
 * frames lazily and stops as soon as we have what we need. In a Spring application the
 * stack under a JDBC call is routinely 80+ frames deep, almost all of it proxies, so
 * stopping early is most of the saving.
 *
 * <p>Attribution rule: take the <em>deepest</em> frame that is not infrastructure. That
 * is the line in user code that immediately triggered the statement -- with Spring Data
 * the repository itself is a generated proxy, so this naturally lands on the service
 * method that called it, which is where the loop lives.
 */
public final class CallSiteResolver {

    private final StackWalker walker;
    private final BeagleConfig config;
    private final Set<String> excludes;
    private final String applicationPackage;

    public CallSiteResolver(BeagleConfig config) {
        this.config = config;
        this.excludes = config.getStackExcludePrefixes();
        this.applicationPackage = config.getApplicationPackage();
        // The walker is immutable and thread-safe, so one instance serves every caller.
        this.walker = StackWalker.getInstance(Set.of(), config.getMaxStackDepth());
    }

    /** The single frame to blame, or {@link CallSite#UNKNOWN} if the stack was all infrastructure. */
    public CallSite resolve() {
        return walker.walk(frames -> firstUserFrame(frames)
                .map(CallSiteResolver::toCallSite)
                .orElse(CallSite.UNKNOWN));
    }

    /**
     * Up to {@code max} user frames, closest first. Useful in a report: the first frame
     * says which line ran the query, the ones after it say how execution got there.
     */
    public List<CallSite> resolveTrace(int max) {
        if (max <= 0) {
            return List.of();
        }
        return walker.walk(frames -> {
            List<CallSite> trace = new ArrayList<>(max);
            frames.limit(config.getMaxStackDepth())
                    .filter(this::isUserFrame)
                    .limit(max)
                    .forEach(f -> trace.add(toCallSite(f)));
            return List.copyOf(trace);
        });
    }

    private java.util.Optional<StackWalker.StackFrame> firstUserFrame(
            Stream<StackWalker.StackFrame> frames) {
        return frames.limit(config.getMaxStackDepth())
                .filter(this::isUserFrame)
                .findFirst();
    }

    private boolean isUserFrame(StackWalker.StackFrame frame) {
        String cn = frame.getClassName();

        // Generated proxies and lambdas carry no source location worth reporting.
        if (cn.contains("$$SpringCGLIB$$")
                || cn.contains("$$EnhancerBy")
                || cn.contains("$Proxy")
                || cn.contains("$HibernateProxy$")
                || cn.contains("$$Lambda")) {
            return false;
        }

        for (String prefix : excludes) {
            if (cn.startsWith(prefix)) {
                return false;
            }
        }

        // When the application package is known, require the frame to be inside it.
        // Without this, an unlisted third-party library becomes the scapegoat and the
        // report points somewhere the developer cannot fix.
        return applicationPackage == null || cn.startsWith(applicationPackage);
    }

    private static CallSite toCallSite(StackWalker.StackFrame frame) {
        return new CallSite(
                frame.getClassName(),
                frame.getMethodName(),
                frame.getFileName(),
                frame.getLineNumber());
    }
}
