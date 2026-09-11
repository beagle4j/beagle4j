package io.github.beagle4j.core.session;

import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.model.CallSite;
import io.github.beagle4j.core.model.QueryExecution;
import io.github.beagle4j.core.sql.SqlNormalizer;
import io.github.beagle4j.core.stack.CallSiteResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One unit of work under observation -- normally an HTTP request, a message consumed
 * from a queue, or a single test method.
 *
 * <p>The session is where the cost/accuracy trade-off is actually made, in
 * {@link #recordQueryStart(String)}. Capturing a stack on every query would make Beagle
 * far too expensive to leave switched on; capturing none would make its reports
 * useless. The rule below captures stacks only where they can change a conclusion:
 *
 * <ol>
 *   <li>the <em>first</em> time a statement template is seen -- there are only ever as
 *       many of these as the application has distinct statements, a few dozen, so the
 *       cost is bounded and paid once;</li>
 *   <li>on subsequent repeats, up to
 *       {@link BeagleConfig#getMaxStackCapturesPerTemplate()} samples -- repeats are
 *       exactly the thing being hunted, so they are worth paying for;</li>
 *   <li>never after that. A batch job running one statement 100k times pays for 20
 *       stack walks, not 100k, and the later executions inherit the call site already
 *       established for that template.</li>
 * </ol>
 *
 * <p>The inherited call site in step 3 is an inference, not an observation. It is sound
 * in the case that matters -- repeats of one statement from one loop -- and the sampling
 * count is retained so a report can be honest about it.
 */
public final class BeagleSession {

    private final String id;
    private final String label;
    private final BeagleConfig config;
    private final SqlNormalizer normalizer;
    private final CallSiteResolver resolver;
    private final long startedAtNanos;

    private final AtomicLong sequence = new AtomicLong();
    private final Object executionsLock = new Object();
    private final List<QueryExecution> executions = new ArrayList<>();

    private final Map<String, AtomicInteger> templateCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> stackCaptures = new ConcurrentHashMap<>();
    private final Map<String, CallSite> lastSiteByTemplate = new ConcurrentHashMap<>();

    private volatile boolean overflowed;
    private volatile boolean closed;

    public BeagleSession(String id, String label, BeagleConfig config,
                         SqlNormalizer normalizer, CallSiteResolver resolver) {
        this.id = id;
        this.label = label;
        this.config = config;
        this.normalizer = normalizer;
        this.resolver = resolver;
        this.startedAtNanos = System.nanoTime();
    }

    public String id() {
        return id;
    }

    /** Human-readable name for the unit of work, e.g. {@code GET /orders/42}. */
    public String label() {
        return label;
    }

    public BeagleConfig config() {
        return config;
    }

    public boolean isClosed() {
        return closed;
    }

    /** True if the session hit {@link BeagleConfig#getMaxQueriesPerSession()} and stopped recording. */
    public boolean hasOverflowed() {
        return overflowed;
    }

    public long durationNanos() {
        return System.nanoTime() - startedAtNanos;
    }

    /**
     * Called as a statement begins executing. Returns the record to be completed by
     * {@link #recordQueryEnd}, or {@code null} if this session has stopped recording.
     */
    public QueryExecution recordQueryStart(String sql) {
        if (closed || sql == null || sql.isEmpty()) {
            return null;
        }

        String template = normalizer.normalize(sql);
        int seen = templateCounts
                .computeIfAbsent(template, k -> new AtomicInteger())
                .incrementAndGet();

        CallSite site = resolveCallSite(template, seen);

        QueryExecution execution = new QueryExecution(
                sequence.getAndIncrement(), sql, template, System.nanoTime(), site);

        synchronized (executionsLock) {
            if (executions.size() >= config.getMaxQueriesPerSession()) {
                overflowed = true;
                // Still returned, so timing and row counting stay consistent for the
                // caller; it simply is not retained for analysis.
                return execution;
            }
            executions.add(execution);
        }
        return execution;
    }

    private CallSite resolveCallSite(String template, int seenCount) {
        if (seenCount == 1) {
            CallSite site = resolver.resolve();
            lastSiteByTemplate.put(template, site);
            stackCaptures.computeIfAbsent(template, k -> new AtomicInteger()).incrementAndGet();
            return site;
        }

        AtomicInteger captured = stackCaptures.computeIfAbsent(template, k -> new AtomicInteger());
        if (captured.get() < config.getMaxStackCapturesPerTemplate()) {
            captured.incrementAndGet();
            CallSite site = resolver.resolve();
            lastSiteByTemplate.put(template, site);
            return site;
        }

        return lastSiteByTemplate.getOrDefault(template, CallSite.UNKNOWN);
    }

    /** Completes a record once the caller has finished with the result set. */
    public void recordQueryEnd(QueryExecution execution, long durationNanos, int rowsRead) {
        if (execution == null) {
            return;
        }
        execution.durationNanos(durationNanos);
        if (rowsRead >= 0) {
            execution.rowsRead(rowsRead);
        }
    }

    /** How many stacks were actually walked for a template. Lets a report qualify its evidence. */
    public int stackSamplesFor(String template) {
        AtomicInteger n = stackCaptures.get(template);
        return n == null ? 0 : n.get();
    }

    /** Ordered snapshot of everything recorded. Order is the basis of parent/child correlation. */
    public List<QueryExecution> executions() {
        synchronized (executionsLock) {
            return List.copyOf(executions);
        }
    }

    public int queryCount() {
        synchronized (executionsLock) {
            return executions.size();
        }
    }

    public void close() {
        this.closed = true;
    }

    @Override
    public String toString() {
        return "BeagleSession[" + label + ", queries=" + queryCount()
                + (overflowed ? ", OVERFLOWED" : "") + "]";
    }
}
