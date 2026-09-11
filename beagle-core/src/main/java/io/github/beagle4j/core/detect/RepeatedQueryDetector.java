package io.github.beagle4j.core.detect;

import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.FindingType;
import io.github.beagle4j.core.model.QueryExecution;
import io.github.beagle4j.core.model.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds the same read, with the same arguments, issued more than once in one unit of
 * work.
 *
 * <p>Unlike an N+1 this needs no correlation and no judgement call: within a single
 * request the answer to an identical query cannot have changed, so every execution after
 * the first is work the application paid for and threw away. That makes this the one
 * detector that can be trusted absolutely, and it is restricted to reads precisely to
 * keep it that way -- a repeated INSERT into an audit table is not a bug, and reporting
 * it would cost the rule its zero-false-positive property.
 *
 * <p>In practice this catches a very common shape: a helper that re-fetches the current
 * user, or a lookup table read once per loop iteration instead of once per request.
 */
public final class RepeatedQueryDetector implements Detector {

    public static final String NAME = "repeated-query";

    private static final int MAX_SAMPLES = 2;
    private static final int WARNING_OCCURRENCES = 3;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Finding> detect(DetectionContext ctx) {
        int threshold = ctx.config().getRepeatedIdenticalQueryThreshold();
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, List<QueryExecution>> entry : ctx.byIdentityKey().entrySet()) {
            List<QueryExecution> group = entry.getValue();
            if (group.size() < threshold) {
                continue;
            }
            QueryExecution first = group.get(0);

            // Without captured parameters, "identical" cannot be established: every
            // execution of `where id = ?` looks alike. Stay silent rather than guess.
            if (first.parameters().isEmpty()) {
                continue;
            }
            if (!isRead(first.sqlTemplate())) {
                continue;
            }

            long totalMillis = 0L;
            for (QueryExecution e : group) {
                if (e.durationMillis() > 0) {
                    totalMillis += e.durationMillis();
                }
            }

            List<String> samples = new ArrayList<>(MAX_SAMPLES);
            for (int i = 0; i < Math.min(MAX_SAMPLES, group.size()); i++) {
                samples.add(group.get(i).renderedSql());
            }

            int occurrences = group.size();
            findings.add(Finding.builder(FindingType.REPEATED_IDENTICAL_QUERY)
                    .confidence(Confidence.HIGH)
                    .severity(occurrences >= WARNING_OCCURRENCES ? Severity.WARNING : Severity.INFO)
                    .callSite(first.callSite())
                    .sqlTemplate(first.sqlTemplate())
                    .occurrences(occurrences)
                    .totalDurationMillis(totalMillis)
                    .sampleStatements(samples)
                    .detail("Ran " + occurrences + " times with identical arguments "
                            + first.parameters() + ". Every execution after the first returned "
                            + "a result the application already had.")
                    .build());
        }
        return findings;
    }

    private static boolean isRead(String template) {
        return template.startsWith("select") || template.startsWith("with");
    }
}
