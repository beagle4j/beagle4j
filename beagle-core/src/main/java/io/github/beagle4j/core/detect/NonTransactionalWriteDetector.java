package io.github.beagle4j.core.detect;

import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.FindingType;
import io.github.beagle4j.core.model.QueryExecution;
import io.github.beagle4j.core.model.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds units of work whose writes were not atomic.
 *
 * <h2>Why this rule exists, and why it is not called "self-invocation detection"</h2>
 * <p>Spring's declarative transactions fail silently in several well-known ways:
 * {@code @Transactional} on a method called from inside the same class bypasses the proxy;
 * so does the annotation on a {@code private} or {@code final} method; so does a class that
 * was never a Spring bean. Static analysis chases each of these separately and gets the
 * answer wrong in both directions — it cannot see a self-call routed deliberately through
 * {@code AopContext.currentProxy()}, and it cannot see a bean that is proxied at runtime
 * by something it does not model.
 *
 * <p>This detector does not look at the annotation at all. It looks at whether a
 * transaction <em>actually happened</em>. Every one of those failure modes produces the
 * same observable symptom — statements executing with autocommit on — and the symptom is
 * the thing that matters, because it is the symptom that loses data. Catching the symptom
 * catches every cause at once, including the ones nobody has written a lint rule for yet.
 *
 * <h2>What is reported</h2>
 * <p>Two or more writes using <em>different</em> statement templates, all outside a
 * transaction, within one unit of work.
 *
 * <p>The distinct-template requirement is what separates a broken unit of work from a
 * deliberate one. A loop inserting a thousand rows with autocommit on is one template
 * repeated: that is a batching question, and it belongs to a different rule. A service
 * method that inserts an order and then updates an inventory count is two templates, and
 * if a failure lands between them the order exists and the stock does not.
 */
public final class NonTransactionalWriteDetector implements Detector {

    public static final String NAME = "write-outside-transaction";

    private static final int MAX_SAMPLES = 4;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Finding> detect(DetectionContext ctx) {
        List<QueryExecution> looseWrites = new ArrayList<>();
        Set<String> templates = new LinkedHashSet<>();

        for (QueryExecution execution : ctx.executions()) {
            if (execution.insideTransaction() || !isWrite(execution.sqlTemplate())) {
                continue;
            }
            looseWrites.add(execution);
            templates.add(execution.sqlTemplate());
        }

        // One template repeated is a batch loop, not a broken transaction.
        if (templates.size() < 2) {
            return List.of();
        }

        List<String> samples = new ArrayList<>(MAX_SAMPLES);
        for (String template : templates) {
            if (samples.size() >= MAX_SAMPLES) {
                break;
            }
            samples.add(template);
        }

        long totalMillis = 0L;
        for (QueryExecution execution : looseWrites) {
            if (execution.durationMillis() > 0) {
                totalMillis += execution.durationMillis();
            }
        }

        QueryExecution first = looseWrites.get(0);
        return List.of(Finding.builder(FindingType.WRITE_OUTSIDE_TRANSACTION)
                // Observed, not inferred: autocommit was on when these statements ran.
                .confidence(Confidence.HIGH)
                .severity(Severity.CRITICAL)
                .callSite(first.callSite())
                .sqlTemplate(first.sqlTemplate())
                .occurrences(looseWrites.size())
                .totalDurationMillis(totalMillis)
                .sampleStatements(samples)
                .detail(looseWrites.size() + " writes across " + templates.size()
                        + " different statements ran with autocommit on, so nothing tied them "
                        + "together. A failure partway through this unit of work would leave "
                        + "the earlier writes committed and the later ones not.")
                .build());
    }

    private static boolean isWrite(String template) {
        return template.startsWith("insert")
                || template.startsWith("update")
                || template.startsWith("delete")
                || template.startsWith("merge")
                || template.startsWith("replace");
    }
}
