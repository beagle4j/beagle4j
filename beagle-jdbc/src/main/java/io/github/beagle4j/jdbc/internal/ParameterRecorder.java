package io.github.beagle4j.jdbc.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects the values bound to a {@code PreparedStatement} between executions.
 *
 * <p>Values are rendered to short strings at the moment they are bound rather than being
 * retained as objects. Holding the objects would be cheaper up front and much more
 * expensive later: a bound parameter can be a detached entity, a large byte array, or
 * anything else with a long tail of references behind it, and keeping one alive for the
 * duration of a session would turn a diagnostic tool into a memory leak.
 */
public final class ParameterRecorder {

    private static final int MAX_VALUE_LENGTH = 64;
    private static final int MAX_PARAMETERS = 64;
    private static final String NULL = "NULL";

    private final Map<Integer, String> values = new TreeMap<>();

    /**
     * Records a {@code setXxx(int, ...)} call. Named-parameter forms on
     * {@code CallableStatement} take a String first argument and are ignored -- they are
     * rare, and guessing at them would be worse than reporting nothing.
     */
    public void record(String methodName, Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Integer index)) {
            return;
        }
        if (values.size() >= MAX_PARAMETERS && !values.containsKey(index)) {
            return;
        }
        if ("setNull".equals(methodName)) {
            values.put(index, NULL);
            return;
        }
        Object value = args.length > 1 ? args[1] : null;
        values.put(index, render(value));
    }

    public void clear() {
        values.clear();
    }

    /** The bound values in parameter order. */
    public List<String> snapshot() {
        if (values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(values.values()));
    }

    static String render(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof CharSequence cs) {
            return "'" + truncate(cs.toString()) + "'";
        }
        if (value instanceof byte[] bytes) {
            return "<binary:" + bytes.length + "B>";
        }
        if (value instanceof java.sql.Blob || value instanceof java.sql.Clob) {
            return "<lob>";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof java.util.Date
                || value instanceof java.time.temporal.Temporal) {
            return "'" + truncate(String.valueOf(value)) + "'";
        }
        return truncate(String.valueOf(value));
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_VALUE_LENGTH) {
            return s;
        }
        return s.substring(0, MAX_VALUE_LENGTH) + "...";
    }
}
