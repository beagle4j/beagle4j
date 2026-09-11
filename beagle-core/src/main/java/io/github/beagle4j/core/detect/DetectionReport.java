package io.github.beagle4j.core.detect;

import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.FindingType;
import io.github.beagle4j.core.model.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Everything Beagle concluded about one finished unit of work. */
public final class DetectionReport {

    /**
     * Worst first. Sorting here rather than at each call site means the console output,
     * the HTML report and a test failure message all agree on which problem matters
     * most -- and the first line a developer reads is the one worth reading.
     */
    private static final Comparator<Finding> WORST_FIRST =
            Comparator.comparing(Finding::severity, Comparator.reverseOrder())
                    .thenComparing(Finding::confidence)
                    .thenComparing(Comparator.comparingInt(Finding::occurrences).reversed());

    private final String sessionLabel;
    private final int queryCount;
    private final long durationMillis;
    private final boolean truncated;
    private final List<Finding> findings;

    public DetectionReport(String sessionLabel, int queryCount, long durationMillis,
                           boolean truncated, List<Finding> findings) {
        this.sessionLabel = sessionLabel;
        this.queryCount = queryCount;
        this.durationMillis = durationMillis;
        this.truncated = truncated;
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(WORST_FIRST);
        this.findings = List.copyOf(sorted);
    }

    public static DetectionReport empty(String label) {
        return new DetectionReport(label, 0, 0L, false, List.of());
    }

    public String sessionLabel() {
        return sessionLabel;
    }

    public int queryCount() {
        return queryCount;
    }

    public long durationMillis() {
        return durationMillis;
    }

    /** True if the session exceeded its recording cap, so the counts are a lower bound. */
    public boolean isTruncated() {
        return truncated;
    }

    public List<Finding> findings() {
        return findings;
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    public List<Finding> ofType(FindingType type) {
        return findings.stream().filter(f -> f.type() == type).toList();
    }

    public List<Finding> atLeast(Severity severity) {
        return findings.stream().filter(f -> f.severity().compareTo(severity) >= 0).toList();
    }

    /**
     * Findings solid enough to fail a build on. Deliberately narrow: gating CI on
     * anything less certain turns a useful tool into a flaky one, and a flaky gate gets
     * disabled within the week.
     */
    public List<Finding> actionable() {
        return findings.stream()
                .filter(f -> f.confidence() == Confidence.HIGH)
                .filter(f -> f.severity() != Severity.INFO)
                .toList();
    }

    @Override
    public String toString() {
        return "DetectionReport[" + sessionLabel + ": " + queryCount + " queries, "
                + findings.size() + " findings]";
    }
}
