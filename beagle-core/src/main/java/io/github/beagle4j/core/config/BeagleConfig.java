package io.github.beagle4j.core.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable tuning knobs for the detection engine.
 *
 * <p>Every default here is a trade-off between <em>catching real problems</em> and
 * <em>not crying wolf</em>. A performance tool that produces false positives gets
 * switched off within a day, so the defaults lean conservative: it is better to miss
 * a marginal N+1 than to report one that is not there.
 */
public final class BeagleConfig {

    /**
     * Frames belonging to the JDK, the JDBC drivers, the persistence frameworks and
     * Beagle itself. These are skipped when attributing a query back to user code --
     * we want to point at {@code OrderService#loadItems:42}, not at
     * {@code HikariProxyPreparedStatement#executeQuery}.
     */
    private static final List<String> DEFAULT_STACK_EXCLUDES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "io.github.beagle4j.",
            "org.springframework.",
            "org.hibernate.",
            "jakarta.persistence.",
            "org.apache.ibatis.",
            "com.baomidou.mybatisplus.",
            "com.zaxxer.hikari.",
            "com.alibaba.druid.",
            "org.apache.commons.dbcp",
            "oracle.jdbc.", "com.mysql.", "org.postgresql.", "org.h2.",
            "org.apache.tomcat.jdbc.",
            "net.sf.cglib.", "org.aopalliance.", "org.jboss.",
            "kotlin.", "kotlinx.");

    private final boolean enabled;
    private final int nPlusOneThreshold;
    private final double rowCorrelationTolerance;
    private final int repeatedIdenticalQueryThreshold;
    private final long slowQueryThresholdMillis;
    private final int maxStackDepth;
    private final int maxStackCapturesPerTemplate;
    private final int maxQueriesPerSession;
    private final Set<String> stackExcludePrefixes;
    private final String applicationPackage;

    private BeagleConfig(Builder b) {
        this.enabled = b.enabled;
        this.nPlusOneThreshold = b.nPlusOneThreshold;
        this.rowCorrelationTolerance = b.rowCorrelationTolerance;
        this.repeatedIdenticalQueryThreshold = b.repeatedIdenticalQueryThreshold;
        this.slowQueryThresholdMillis = b.slowQueryThresholdMillis;
        this.maxStackDepth = b.maxStackDepth;
        this.maxStackCapturesPerTemplate = b.maxStackCapturesPerTemplate;
        this.maxQueriesPerSession = b.maxQueriesPerSession;
        this.applicationPackage = b.applicationPackage;
        Set<String> excludes = new LinkedHashSet<>(DEFAULT_STACK_EXCLUDES);
        excludes.addAll(b.extraStackExcludes);
        this.stackExcludePrefixes = Set.copyOf(excludes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static BeagleConfig defaults() {
        return builder().build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * How many executions of the same statement, from the same call site, before we
     * consider it a candidate. Three is the smallest number that can distinguish
     * "a loop" from "two unrelated lookups that happen to share a template".
     */
    public int getNPlusOneThreshold() {
        return nPlusOneThreshold;
    }

    /**
     * How loosely the child-execution count has to match the parent row count for the
     * two to be considered correlated. 0.2 means a 20-row parent matches 16..24 children,
     * which absorbs the usual noise (nulls in the association, a cache hit or two).
     */
    public double getRowCorrelationTolerance() {
        return rowCorrelationTolerance;
    }

    public int getRepeatedIdenticalQueryThreshold() {
        return repeatedIdenticalQueryThreshold;
    }

    public long getSlowQueryThresholdMillis() {
        return slowQueryThresholdMillis;
    }

    public int getMaxStackDepth() {
        return maxStackDepth;
    }

    /**
     * Hard ceiling on stack captures per statement template. A batch job running the
     * same statement 100k times must not pay for 100k stack walks -- after this many
     * samples we have more than enough evidence and fall back to plain counting.
     */
    public int getMaxStackCapturesPerTemplate() {
        return maxStackCapturesPerTemplate;
    }

    /** Memory guard: a runaway session stops recording rather than filling the heap. */
    public int getMaxQueriesPerSession() {
        return maxQueriesPerSession;
    }

    public Set<String> getStackExcludePrefixes() {
        return stackExcludePrefixes;
    }

    /**
     * Optional base package of the application under analysis. When set, attribution
     * prefers the outermost frame inside this package, which lands the report on the
     * service method a developer recognises rather than on a repository interface.
     */
    public String getApplicationPackage() {
        return applicationPackage;
    }

    public static final class Builder {
        private boolean enabled = true;
        private int nPlusOneThreshold = 3;
        private double rowCorrelationTolerance = 0.2d;
        private int repeatedIdenticalQueryThreshold = 2;
        private long slowQueryThresholdMillis = 200L;
        private int maxStackDepth = 48;
        private int maxStackCapturesPerTemplate = 20;
        private int maxQueriesPerSession = 5_000;
        private String applicationPackage;
        private Set<String> extraStackExcludes = Set.of();

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder nPlusOneThreshold(int v) {
            if (v < 2) {
                throw new IllegalArgumentException("nPlusOneThreshold must be >= 2, got " + v);
            }
            this.nPlusOneThreshold = v;
            return this;
        }

        public Builder rowCorrelationTolerance(double v) {
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException("rowCorrelationTolerance must be in [0,1], got " + v);
            }
            this.rowCorrelationTolerance = v;
            return this;
        }

        public Builder repeatedIdenticalQueryThreshold(int v) {
            if (v < 2) {
                throw new IllegalArgumentException("repeatedIdenticalQueryThreshold must be >= 2, got " + v);
            }
            this.repeatedIdenticalQueryThreshold = v;
            return this;
        }

        public Builder slowQueryThresholdMillis(long v) {
            this.slowQueryThresholdMillis = v;
            return this;
        }

        public Builder maxStackDepth(int v) {
            this.maxStackDepth = v;
            return this;
        }

        public Builder maxStackCapturesPerTemplate(int v) {
            this.maxStackCapturesPerTemplate = v;
            return this;
        }

        public Builder maxQueriesPerSession(int v) {
            this.maxQueriesPerSession = v;
            return this;
        }

        public Builder applicationPackage(String v) {
            this.applicationPackage = (v == null || v.isBlank()) ? null : v;
            return this;
        }

        public Builder extraStackExcludes(Set<String> v) {
            this.extraStackExcludes = v == null ? Set.of() : Set.copyOf(v);
            return this;
        }

        public BeagleConfig build() {
            return new BeagleConfig(this);
        }
    }
}
