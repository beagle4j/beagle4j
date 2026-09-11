package io.github.beagle4j.core.detect;

import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.FindingType;
import io.github.beagle4j.core.model.QueryExecution;
import io.github.beagle4j.core.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the classic N+1: a query returning N rows, followed by N executions of some
 * other statement, one per row.
 *
 * <h2>Why counting is not enough</h2>
 * <p>The obvious implementation -- "the same statement ran more than N times, report it"
 * -- is what most tools do, and it is why most tools get muted. A batch importer
 * legitimately runs one INSERT ten thousand times. A scheduled job legitimately polls
 * the same SELECT in a loop. Reporting those as bugs trains developers to ignore the
 * report, and a report nobody reads catches nothing.
 *
 * <h2>Row correlation</h2>
 * <p>What actually distinguishes an N+1 from a legitimate loop is not the repetition,
 * it is <em>where the repetition count came from</em>. In a true N+1 the number of child
 * queries is dictated by the size of a result set that was just read: fifty orders
 * produce exactly fifty follow-up queries. So this detector looks backwards from the
 * first repeat for the query that preceded the burst, and checks whether that query's
 * row count matches the number of repeats. When it does, the diagnosis is not a
 * heuristic -- the shape of the data caused the shape of the traffic, which is the
 * definition of the bug. Those are reported at {@link Confidence#HIGH}.
 *
 * <p>When no such parent can be found the repetition is still reported, at
 * {@link Confidence#MEDIUM}, because it is frequently a real N+1 whose parent data came
 * from a cache or an earlier request. Publishing the distinction, rather than hiding it,
 * is what lets a team gate their build on the high-confidence findings alone.
 *
 * <h2>Picking the right parent</h2>
 * <p>The search deliberately steps over executions that are themselves part of a
 * repeated group. When a loop body issues two different queries per row, the statement
 * immediately preceding the second one is the <em>first child</em>, not the parent;
 * treating it as the parent would compare 50 repeats against a 3-row sibling and
 * downgrade a genuine finding. Skipping repeats walks back past the siblings to the
 * query that actually drove the loop.
 */
public final class NPlusOneDetector implements Detector {

    public static final String NAME = "n-plus-one";

    private static final int MAX_SAMPLES = 3;

    /** Above this many repeats the problem scales with the data set, not with the code. */
    private static final int CRITICAL_OCCURRENCES = 10;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Finding> detect(DetectionContext ctx) {
        int threshold = ctx.config().getNPlusOneThreshold();
        Map<String, List<QueryExecution>> groups = ctx.byGroupKey();

        Set<String> repeatedGroupKeys = new HashSet<>();
        groups.forEach((key, group) -> {
            if (group.size() >= threshold) {
                repeatedGroupKeys.add(key);
            }
        });

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<QueryExecution>> entry : groups.entrySet()) {
            List<QueryExecution> group = entry.getValue();
            if (group.size() < threshold) {
                continue;
            }
            // An N+1 is a reading problem: it is defined by fetching rows one at a time
            // that could have been fetched together. A loop of INSERTs is also worth
            // batching, but it is a different bug with a different fix, and filing it
            // under this name would be both wrong and -- since batch loops are
            // deliberate far more often than not -- the single largest source of false
            // positives this detector could have.
            if (!isRead(group.get(0).sqlTemplate())) {
                continue;
            }
            // A burst where every execution carried the same arguments is wasted work,
            // not an N+1. RepeatedQueryDetector owns that case; skipping it here keeps
            // one problem from being reported twice under two different names.
            if (allArgumentsIdentical(group)) {
                continue;
            }
            findings.add(buildFinding(ctx, group, repeatedGroupKeys));
        }
        return findings;
    }

    private Finding buildFinding(DetectionContext ctx,
                                 List<QueryExecution> group,
                                 Set<String> repeatedGroupKeys) {
        QueryExecution first = group.get(0);
        String template = first.sqlTemplate();
        int occurrences = group.size();

        Optional<QueryExecution> parent = ctx.findMostRecentBefore(
                first.sequence(),
                e -> !e.sqlTemplate().equals(template)
                        && e.rowsRead() > 0
                        && !repeatedGroupKeys.contains(e.groupKey()));

        int parentRows = parent.map(QueryExecution::rowsRead).orElse(-1);
        boolean correlated = correlates(parentRows, occurrences,
                ctx.config().getRowCorrelationTolerance());

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

        Confidence confidence = correlated ? Confidence.HIGH : Confidence.MEDIUM;
        Severity severity = severityFor(correlated, occurrences);

        Finding.Builder builder = Finding.builder(FindingType.N_PLUS_ONE)
                .confidence(confidence)
                .severity(severity)
                .callSite(first.callSite())
                .sqlTemplate(template)
                .occurrences(occurrences)
                .totalDurationMillis(totalMillis)
                .sampleStatements(samples)
                .detail(detailFor(correlated, occurrences, parentRows,
                        ctx.session().stackSamplesFor(template)));

        if (correlated) {
            QueryExecution p = parent.orElseThrow();
            builder.parent(p.callSite(), p.sqlTemplate(), parentRows);
        }
        return builder.build();
    }

    /**
     * True when the number of child executions can be explained by the parent's row
     * count. The tolerance absorbs the ordinary noise -- a null association, a row
     * served from the persistence context -- without being loose enough to match by
     * coincidence.
     */
    static boolean correlates(int parentRows, int childCount, double tolerance) {
        if (parentRows <= 0) {
            return false;
        }
        int delta = Math.abs(childCount - parentRows);
        double allowed = Math.max(1.0d, parentRows * tolerance);
        return delta <= allowed;
    }

    private static Severity severityFor(boolean correlated, int occurrences) {
        if (correlated) {
            return occurrences >= CRITICAL_OCCURRENCES ? Severity.CRITICAL : Severity.WARNING;
        }
        return occurrences >= CRITICAL_OCCURRENCES ? Severity.WARNING : Severity.INFO;
    }

    private static String detailFor(boolean correlated, int occurrences,
                                    int parentRows, int stackSamples) {
        StringBuilder sb = new StringBuilder();
        if (correlated) {
            sb.append("The preceding query returned ").append(parentRows)
                    .append(" rows and this statement then ran ").append(occurrences)
                    .append(" times -- one execution per row. The query count grows with the ")
                    .append("size of the result set, so this gets worse as the table fills up.");
        } else {
            sb.append("This statement ran ").append(occurrences)
                    .append(" times from one call site with differing arguments. No preceding ")
                    .append("query was found whose row count explains the repetition, so this ")
                    .append("may be a deliberate batch loop rather than an N+1.");
        }
        if (stackSamples > 0 && stackSamples < occurrences) {
            sb.append(" (Call site confirmed by ").append(stackSamples)
                    .append(" sampled stacks; the remaining executions were counted only.)");
        }
        return sb.toString();
    }

    private static boolean isRead(String template) {
        return template.startsWith("select") || template.startsWith("with");
    }

    /**
     * Whether every execution in the group carried identical bind parameters. Returns
     * false when no parameters were captured at all, since "all equally empty" is
     * absence of evidence, not evidence of sameness.
     */
    private static boolean allArgumentsIdentical(List<QueryExecution> group) {
        boolean anyCaptured = false;
        for (QueryExecution e : group) {
            if (!e.parameters().isEmpty()) {
                anyCaptured = true;
                break;
            }
        }
        if (!anyCaptured) {
            return false;
        }
        List<String> firstArgs = group.get(0).parameters();
        for (QueryExecution e : group) {
            if (!firstArgs.equals(e.parameters())) {
                return false;
            }
        }
        return true;
    }
}
