package io.github.beagle4j.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One problem Beagle is prepared to stand behind, together with the evidence for it.
 *
 * <p>A finding is deliberately self-contained: it names the call site, quotes the
 * statement, states how many times it ran, and -- for a correlated N+1 -- points at the
 * parent query that triggered it. Everything a developer needs to confirm or dismiss
 * the report is in the object, so no one has to go spelunking in logs.
 */
public final class Finding {

    private final FindingType type;
    private final Severity severity;
    private final Confidence confidence;
    private final CallSite callSite;
    private final String sqlTemplate;
    private final int occurrences;
    private final long totalDurationMillis;
    private final CallSite parentCallSite;
    private final String parentSqlTemplate;
    private final int parentRowCount;
    private final List<String> sampleStatements;
    private final String detail;

    private Finding(Builder b) {
        this.type = Objects.requireNonNull(b.type, "type");
        this.severity = Objects.requireNonNull(b.severity, "severity");
        this.confidence = Objects.requireNonNull(b.confidence, "confidence");
        this.callSite = b.callSite == null ? CallSite.UNKNOWN : b.callSite;
        this.sqlTemplate = Objects.requireNonNull(b.sqlTemplate, "sqlTemplate");
        this.occurrences = b.occurrences;
        this.totalDurationMillis = b.totalDurationMillis;
        this.parentCallSite = b.parentCallSite;
        this.parentSqlTemplate = b.parentSqlTemplate;
        this.parentRowCount = b.parentRowCount;
        this.sampleStatements = List.copyOf(b.sampleStatements);
        this.detail = b.detail;
    }

    public static Builder builder(FindingType type) {
        return new Builder(type);
    }

    public FindingType type() {
        return type;
    }

    public Severity severity() {
        return severity;
    }

    public Confidence confidence() {
        return confidence;
    }

    public CallSite callSite() {
        return callSite;
    }

    public String sqlTemplate() {
        return sqlTemplate;
    }

    public int occurrences() {
        return occurrences;
    }

    public long totalDurationMillis() {
        return totalDurationMillis;
    }

    /** The query whose result set was being iterated. Null unless this is a correlated N+1. */
    public CallSite parentCallSite() {
        return parentCallSite;
    }

    public String parentSqlTemplate() {
        return parentSqlTemplate;
    }

    /** Rows returned by the parent query, or -1 when there is no correlated parent. */
    public int parentRowCount() {
        return parentRowCount;
    }

    /** A few of the actual statements, parameters included, for eyeballing. */
    public List<String> sampleStatements() {
        return sampleStatements;
    }

    public String detail() {
        return detail;
    }

    public String remediation() {
        return type.remediation();
    }

    /** One-line summary, suitable for a log line or a test failure message. */
    public String summary() {
        return switch (type) {
            case N_PLUS_ONE -> occurrences + " repeats of the same query at " + callSite
                    + (parentRowCount > 0 ? " (parent returned " + parentRowCount + " rows)" : "");
            case REPEATED_IDENTICAL_QUERY -> "identical query ran " + occurrences
                    + " times at " + callSite;
            case SLOW_QUERY -> "query took " + totalDurationMillis + "ms at " + callSite;
        };
    }

    @Override
    public String toString() {
        return "[" + severity + "/" + confidence + "] " + type.label() + ": " + summary();
    }

    public static final class Builder {
        private final FindingType type;
        private Severity severity = Severity.WARNING;
        private Confidence confidence = Confidence.MEDIUM;
        private CallSite callSite;
        private String sqlTemplate;
        private int occurrences;
        private long totalDurationMillis;
        private CallSite parentCallSite;
        private String parentSqlTemplate;
        private int parentRowCount = -1;
        private List<String> sampleStatements = List.of();
        private String detail;

        private Builder(FindingType type) {
            this.type = type;
        }

        public Builder severity(Severity v) {
            this.severity = v;
            return this;
        }

        public Builder confidence(Confidence v) {
            this.confidence = v;
            return this;
        }

        public Builder callSite(CallSite v) {
            this.callSite = v;
            return this;
        }

        public Builder sqlTemplate(String v) {
            this.sqlTemplate = v;
            return this;
        }

        public Builder occurrences(int v) {
            this.occurrences = v;
            return this;
        }

        public Builder totalDurationMillis(long v) {
            this.totalDurationMillis = v;
            return this;
        }

        public Builder parent(CallSite site, String template, int rowCount) {
            this.parentCallSite = site;
            this.parentSqlTemplate = template;
            this.parentRowCount = rowCount;
            return this;
        }

        public Builder sampleStatements(List<String> v) {
            this.sampleStatements = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public Builder detail(String v) {
            this.detail = v;
            return this;
        }

        public Finding build() {
            return new Finding(this);
        }
    }
}
