package io.github.beagle4j.core.detect;

import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.model.QueryExecution;
import io.github.beagle4j.core.session.BeagleSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The immutable view of a finished session that detectors run against.
 *
 * <p>The groupings are computed once here rather than inside each detector. Beyond
 * saving repeated passes, it means every detector agrees on what "the same statement"
 * means, so two detectors can never disagree about whether two executions were related.
 */
public final class DetectionContext {

    private final BeagleSession session;
    private final BeagleConfig config;
    private final List<QueryExecution> executions;
    private final Map<String, List<QueryExecution>> byGroupKey;
    private final Map<String, List<QueryExecution>> byIdentityKey;

    public DetectionContext(BeagleSession session) {
        this.session = session;
        this.config = session.config();
        this.executions = session.executions();

        Map<String, List<QueryExecution>> groups = new LinkedHashMap<>();
        Map<String, List<QueryExecution>> identities = new LinkedHashMap<>();
        for (QueryExecution e : executions) {
            groups.computeIfAbsent(e.groupKey(), k -> new ArrayList<>()).add(e);
            identities.computeIfAbsent(e.identityKey(), k -> new ArrayList<>()).add(e);
        }
        this.byGroupKey = unmodifiableDeep(groups);
        this.byIdentityKey = unmodifiableDeep(identities);
    }

    private static Map<String, List<QueryExecution>> unmodifiableDeep(
            Map<String, List<QueryExecution>> src) {
        Map<String, List<QueryExecution>> copy = new LinkedHashMap<>(src.size());
        src.forEach((k, v) -> copy.put(k, Collections.unmodifiableList(v)));
        return Collections.unmodifiableMap(copy);
    }

    public BeagleSession session() {
        return session;
    }

    public BeagleConfig config() {
        return config;
    }

    /** Every execution, in the order it happened. */
    public List<QueryExecution> executions() {
        return executions;
    }

    /** Executions keyed by statement template <em>and</em> originating call site. */
    public Map<String, List<QueryExecution>> byGroupKey() {
        return byGroupKey;
    }

    /** Executions keyed by statement template <em>and</em> bound parameters. */
    public Map<String, List<QueryExecution>> byIdentityKey() {
        return byIdentityKey;
    }

    /**
     * Walks backwards from {@code beforeSequence} and returns the most recent execution
     * satisfying {@code predicate}.
     *
     * <p>This is how a repeated query finds the query that caused it. Searching backwards
     * from the <em>first</em> repeat, rather than forwards from the start, is what makes
     * the match meaningful: the statement that immediately preceded the burst is the one
     * whose result set was being iterated.
     */
    public Optional<QueryExecution> findMostRecentBefore(long beforeSequence,
                                                         Predicate<QueryExecution> predicate) {
        QueryExecution best = null;
        for (QueryExecution e : executions) {
            if (e.sequence() >= beforeSequence) {
                break;
            }
            if (predicate.test(e)) {
                best = e;
            }
        }
        return Optional.ofNullable(best);
    }
}
