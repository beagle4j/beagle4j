package io.github.beagle4j.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One statement execution, as observed at the JDBC layer.
 *
 * <p>Mutable on purpose: {@code rowsRead} and {@code durationNanos} are only known once
 * the caller has finished walking the {@link java.sql.ResultSet}, which happens long
 * after the execution itself is recorded. Instances are confined to a single
 * {@code BeagleSession} and published through it, so the mutation is safe.
 */
public final class QueryExecution {

    private final long sequence;
    private final String sql;
    private final String sqlTemplate;
    private final long startedAtNanos;
    private final CallSite callSite;

    private long durationNanos = -1L;
    private int rowsRead = -1;
    private boolean insideTransaction;
    private List<String> parameters = List.of();

    public QueryExecution(long sequence, String sql, String sqlTemplate,
                          long startedAtNanos, CallSite callSite) {
        this.sequence = sequence;
        this.sql = Objects.requireNonNull(sql, "sql");
        this.sqlTemplate = Objects.requireNonNull(sqlTemplate, "sqlTemplate");
        this.startedAtNanos = startedAtNanos;
        this.callSite = callSite == null ? CallSite.UNKNOWN : callSite;
    }

    /**
     * Monotonic position within the session. Ordering is what makes parent/child
     * correlation possible, so it must never be derived from a clock.
     */
    public long sequence() {
        return sequence;
    }

    public String sql() {
        return sql;
    }

    /** Literal-stripped form, e.g. {@code select * from orders where id = ?}. */
    public String sqlTemplate() {
        return sqlTemplate;
    }

    public long startedAtNanos() {
        return startedAtNanos;
    }

    public CallSite callSite() {
        return callSite;
    }

    public long durationNanos() {
        return durationNanos;
    }

    public void durationNanos(long v) {
        this.durationNanos = v;
    }

    public long durationMillis() {
        return durationNanos < 0 ? -1L : durationNanos / 1_000_000L;
    }

    /** Rows actually consumed by the caller, or -1 if unknown (updates, or an unread result set). */
    public int rowsRead() {
        return rowsRead;
    }

    public void rowsRead(int v) {
        this.rowsRead = v;
    }

    public boolean insideTransaction() {
        return insideTransaction;
    }

    public void insideTransaction(boolean v) {
        this.insideTransaction = v;
    }

    /**
     * Bind parameters, already rendered to strings.
     *
     * <p>Captured because a {@code PreparedStatement} keeps its parameters out of the SQL
     * text: every execution of {@code where id = ?} carries the identical string, so
     * without the bound values there is no way to tell an N+1 (same statement, fifty
     * different ids) from genuinely wasted work (same statement, the same id fifty
     * times). They are rendered eagerly rather than retained as objects, so that Beagle
     * never pins an entity graph in memory for the life of a session.
     */
    public List<String> parameters() {
        return parameters;
    }

    public void parameters(List<String> v) {
        this.parameters = v == null ? List.of() : List.copyOf(v);
    }

    /** Grouping key for "the same statement issued from the same place". */
    public String groupKey() {
        return sqlTemplate + "@@" + callSite.id();
    }

    /**
     * Grouping key for "the same statement with the same arguments" -- i.e. a call whose
     * answer provably cannot have changed between executions.
     */
    public String identityKey() {
        return sqlTemplate + "##" + parameters;
    }

    /** The statement with its bound values substituted back in, for display only. */
    public String renderedSql() {
        if (parameters.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length() + parameters.size() * 8);
        int paramIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && paramIndex < parameters.size()) {
                sb.append(parameters.get(paramIndex++));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "#" + sequence + " [" + durationMillis() + "ms, rows=" + rowsRead + "] "
                + sqlTemplate + " <- " + callSite;
    }
}
