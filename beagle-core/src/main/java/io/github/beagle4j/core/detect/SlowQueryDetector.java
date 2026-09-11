package io.github.beagle4j.core.detect;

import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.FindingType;
import io.github.beagle4j.core.model.QueryExecution;
import io.github.beagle4j.core.model.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports statements that individually exceeded the latency budget.
 *
 * <p>No inference involved -- the duration was measured. It is reported at
 * {@link Confidence#HIGH} for that reason, while the <em>severity</em> stays at
 * {@code WARNING}: a query being slow on a developer laptop with ten rows of test data
 * means something quite different from the same query being slow in production, and the
 * tool is not in a position to tell which it is looking at.
 */
public final class SlowQueryDetector implements Detector {

    public static final String NAME = "slow-query";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Finding> detect(DetectionContext ctx) {
        long budget = ctx.config().getSlowQueryThresholdMillis();
        if (budget <= 0) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        for (QueryExecution e : ctx.executions()) {
            long millis = e.durationMillis();
            if (millis < budget) {
                continue;
            }
            findings.add(Finding.builder(FindingType.SLOW_QUERY)
                    .confidence(Confidence.HIGH)
                    .severity(Severity.WARNING)
                    .callSite(e.callSite())
                    .sqlTemplate(e.sqlTemplate())
                    .occurrences(1)
                    .totalDurationMillis(millis)
                    .sampleStatements(List.of(e.renderedSql()))
                    .detail("Took " + millis + "ms against a budget of " + budget + "ms"
                            + (e.rowsRead() >= 0 ? ", returning " + e.rowsRead() + " rows." : "."))
                    .build());
        }
        return findings;
    }
}
