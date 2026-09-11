package io.github.beagle4j.spring;

import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.model.Severity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything configurable under the {@code beagle.*} prefix.
 *
 * <p>Plain JavaBean accessors rather than constructor binding: the binding annotation for
 * immutable properties moved packages between Spring Boot lines, and this starter is
 * built once to run on several of them.
 */
@ConfigurationProperties(prefix = "beagle")
public class BeagleProperties {

    /**
     * Whether Beagle observes anything at all. When false the auto-configuration backs
     * off entirely -- no data source is wrapped and no filter is registered.
     */
    private boolean enabled = true;

    /**
     * Base package of your application, e.g. {@code com.acme}. Strongly recommended:
     * without it, attribution falls back to "the first frame that is not a known
     * framework", and an unrecognised library can end up taking the blame for a query
     * your own code issued.
     */
    private String applicationPackage;

    /** Executions of one statement, from one call site, before it is considered suspicious. */
    private int nPlusOneThreshold = 3;

    /** How loosely the repeat count must match the parent's row count. */
    private double rowCorrelationTolerance = 0.2d;

    /** Identical executions -- same statement, same arguments -- before reporting waste. */
    private int repeatedQueryThreshold = 2;

    /** Statements slower than this are reported. Set to zero to disable. */
    private Duration slowQueryThreshold = Duration.ofMillis(200);

    private int maxStackDepth = 48;

    /** Stack samples per statement template before falling back to plain counting. */
    private int maxStackCapturesPerTemplate = 20;

    /** Memory guard: a single unit of work stops recording beyond this many statements. */
    private int maxQueriesPerSession = 5_000;

    /**
     * Bean names of data sources to leave alone.
     *
     * <p>The escape hatch for the one way instrumentation can break an application:
     * Beagle replaces a {@code DataSource} bean with a wrapper, so code that injects a
     * <em>concrete</em> type ({@code HikariDataSource} rather than {@code DataSource})
     * will no longer match. Naming the bean here opts it out.
     */
    private List<String> excludedDataSources = new ArrayList<>();

    private final Report report = new Report();

    private final Web web = new Web();

    public BeagleConfig toCoreConfig() {
        return BeagleConfig.builder()
                .enabled(enabled)
                .applicationPackage(applicationPackage)
                .nPlusOneThreshold(nPlusOneThreshold)
                .rowCorrelationTolerance(rowCorrelationTolerance)
                .repeatedIdenticalQueryThreshold(repeatedQueryThreshold)
                .slowQueryThresholdMillis(slowQueryThreshold == null ? 0L : slowQueryThreshold.toMillis())
                .maxStackDepth(maxStackDepth)
                .maxStackCapturesPerTemplate(maxStackCapturesPerTemplate)
                .maxQueriesPerSession(maxQueriesPerSession)
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApplicationPackage() {
        return applicationPackage;
    }

    public void setApplicationPackage(String applicationPackage) {
        this.applicationPackage = applicationPackage;
    }

    public int getNPlusOneThreshold() {
        return nPlusOneThreshold;
    }

    public void setNPlusOneThreshold(int nPlusOneThreshold) {
        this.nPlusOneThreshold = nPlusOneThreshold;
    }

    public double getRowCorrelationTolerance() {
        return rowCorrelationTolerance;
    }

    public void setRowCorrelationTolerance(double rowCorrelationTolerance) {
        this.rowCorrelationTolerance = rowCorrelationTolerance;
    }

    public int getRepeatedQueryThreshold() {
        return repeatedQueryThreshold;
    }

    public void setRepeatedQueryThreshold(int repeatedQueryThreshold) {
        this.repeatedQueryThreshold = repeatedQueryThreshold;
    }

    public Duration getSlowQueryThreshold() {
        return slowQueryThreshold;
    }

    public void setSlowQueryThreshold(Duration slowQueryThreshold) {
        this.slowQueryThreshold = slowQueryThreshold;
    }

    public int getMaxStackDepth() {
        return maxStackDepth;
    }

    public void setMaxStackDepth(int maxStackDepth) {
        this.maxStackDepth = maxStackDepth;
    }

    public int getMaxStackCapturesPerTemplate() {
        return maxStackCapturesPerTemplate;
    }

    public void setMaxStackCapturesPerTemplate(int maxStackCapturesPerTemplate) {
        this.maxStackCapturesPerTemplate = maxStackCapturesPerTemplate;
    }

    public int getMaxQueriesPerSession() {
        return maxQueriesPerSession;
    }

    public void setMaxQueriesPerSession(int maxQueriesPerSession) {
        this.maxQueriesPerSession = maxQueriesPerSession;
    }

    public List<String> getExcludedDataSources() {
        return excludedDataSources;
    }

    public void setExcludedDataSources(List<String> excludedDataSources) {
        this.excludedDataSources = excludedDataSources == null ? new ArrayList<>() : excludedDataSources;
    }

    public Report getReport() {
        return report;
    }

    public Web getWeb() {
        return web;
    }

    /** How findings are surfaced. */
    public static class Report {

        /** Write findings to the application log. */
        private boolean logging = true;

        /**
         * Emit ANSI colour. Off by default: most log appenders write to a file, where
         * escape codes are unreadable noise rather than emphasis.
         */
        private boolean color = false;

        private int width = 88;

        /** Stay quiet about units of work that produced nothing worth saying. */
        private boolean onlyWhenFindings = true;

        /** Findings below this severity are not logged. */
        private Severity minSeverity = Severity.INFO;

        public boolean isLogging() {
            return logging;
        }

        public void setLogging(boolean logging) {
            this.logging = logging;
        }

        public boolean isColor() {
            return color;
        }

        public void setColor(boolean color) {
            this.color = color;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public boolean isOnlyWhenFindings() {
            return onlyWhenFindings;
        }

        public void setOnlyWhenFindings(boolean onlyWhenFindings) {
            this.onlyWhenFindings = onlyWhenFindings;
        }

        public Severity getMinSeverity() {
            return minSeverity;
        }

        public void setMinSeverity(Severity minSeverity) {
            this.minSeverity = minSeverity;
        }
    }

    /** Which HTTP requests become observed units of work. */
    public static class Web {

        private boolean enabled = true;

        /**
         * Request paths that never open a session. Static assets and health checks issue
         * no interesting queries, and observing them buries the real reports.
         */
        private List<String> excludedPaths = new ArrayList<>(List.of(
                "/actuator/**", "/favicon.ico", "/webjars/**",
                "/css/**", "/js/**", "/images/**", "/static/**"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getExcludedPaths() {
            return excludedPaths;
        }

        public void setExcludedPaths(List<String> excludedPaths) {
            this.excludedPaths = excludedPaths == null ? new ArrayList<>() : excludedPaths;
        }
    }
}
