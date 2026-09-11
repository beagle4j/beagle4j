package io.github.beagle4j.core;

import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.detect.DetectionContext;
import io.github.beagle4j.core.detect.DetectionReport;
import io.github.beagle4j.core.detect.Detector;
import io.github.beagle4j.core.detect.NPlusOneDetector;
import io.github.beagle4j.core.detect.NonTransactionalWriteDetector;
import io.github.beagle4j.core.detect.RepeatedQueryDetector;
import io.github.beagle4j.core.detect.SlowQueryDetector;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.session.BeagleSession;
import io.github.beagle4j.core.session.SessionContext;
import io.github.beagle4j.core.sql.SqlNormalizer;
import io.github.beagle4j.core.stack.CallSiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The engine: owns the configuration and the detector set, opens sessions, and turns a
 * finished session into a report.
 *
 * <p>An instance, not a pile of statics. Statics would be marginally more convenient to
 * call from a JDBC proxy, at the cost of making the whole thing untestable in parallel
 * and impossible to configure twice in one JVM -- which is exactly what its own test
 * suite needs to do. The static reach-through that the instrumentation layer wants lives
 * in {@link SessionContext} and is scoped to one thing: finding the current session.
 *
 * <p>Thread-safe and intended to be a singleton in an application.
 */
public final class BeagleRuntime {

    private static final Logger log = LoggerFactory.getLogger(BeagleRuntime.class);

    private final BeagleConfig config;
    private final SqlNormalizer normalizer;
    private final CallSiteResolver resolver;
    private final List<Detector> detectors;
    private final AtomicLong sessionCounter = new AtomicLong();

    public BeagleRuntime(BeagleConfig config) {
        this(config, defaultDetectors());
    }

    public BeagleRuntime(BeagleConfig config, List<Detector> detectors) {
        this.config = config;
        this.normalizer = new SqlNormalizer();
        this.resolver = new CallSiteResolver(config);
        this.detectors = List.copyOf(detectors);
    }

    public static BeagleRuntime withDefaults() {
        return new BeagleRuntime(BeagleConfig.defaults());
    }

    public static List<Detector> defaultDetectors() {
        return List.of(
                new NPlusOneDetector(),
                new RepeatedQueryDetector(),
                new NonTransactionalWriteDetector(),
                new SlowQueryDetector());
    }

    public BeagleConfig config() {
        return config;
    }

    public List<Detector> detectors() {
        return detectors;
    }

    /**
     * Opens a session and binds it to the calling thread. The caller owns the session
     * from here and must pair this with {@link #closeCurrentSession()}, normally in a
     * {@code finally} block.
     */
    public BeagleSession openSession(String label) {
        String id = "s" + sessionCounter.incrementAndGet();
        BeagleSession session = new BeagleSession(id, label, config, normalizer, resolver);
        if (!config.isEnabled()) {
            // A closed session records nothing, which turns every instrumentation hook
            // into a null check. Cheaper and far less error-prone than threading an
            // "enabled" flag through every call path.
            session.close();
            return session;
        }
        SessionContext.bind(session);
        return session;
    }

    /** Closes the thread's session, runs the detectors, and unbinds. Never throws. */
    public DetectionReport closeCurrentSession() {
        BeagleSession session = SessionContext.current();
        if (session == null) {
            return DetectionReport.empty("<none>");
        }
        try {
            return close(session);
        } finally {
            SessionContext.clear();
        }
    }

    public DetectionReport close(BeagleSession session) {
        long durationMillis = session.durationNanos() / 1_000_000L;
        session.close();
        List<Finding> findings = runDetectors(session);
        return new DetectionReport(session.label(), session.queryCount(),
                durationMillis, session.hasOverflowed(), findings);
    }

    private List<Finding> runDetectors(BeagleSession session) {
        if (session.queryCount() == 0) {
            return List.of();
        }
        DetectionContext context = new DetectionContext(session);
        List<Finding> all = new ArrayList<>();
        for (Detector detector : detectors) {
            try {
                all.addAll(detector.detect(context));
            } catch (RuntimeException ex) {
                // A broken rule must never break the application it is observing.
                // Diagnostics are a nice-to-have; the request is not.
                log.warn("Beagle detector '{}' failed and was skipped", detector.name(), ex);
            }
        }
        return all;
    }

    /**
     * Runs {@code work} inside a fresh session and returns what was found. The
     * convenience form used by tests and by anything with a clear begin and end.
     *
     * <p>Named differently from {@link #observeResult(String, Supplier)} rather than
     * overloaded: a lambda whose body is an expression returning a value is compatible
     * with both {@code Runnable} and {@code Supplier}, so overloading the name would
     * make {@code observe("x", () -> repo.findAll())} an ambiguity error at every call
     * site that happens to call something non-void.
     */
    public DetectionReport observe(String label, Runnable work) {
        return observeResult(label, () -> {
            work.run();
            return null;
        }).report();
    }

    public <T> Observation<T> observeResult(String label, Supplier<T> work) {
        BeagleSession session = openSession(label);
        T result;
        try {
            result = work.get();
        } finally {
            SessionContext.clear();
        }
        return new Observation<>(result, close(session));
    }

    /** A value produced under observation, paired with what was observed while producing it. */
    public record Observation<T>(T value, DetectionReport report) {
    }
}
