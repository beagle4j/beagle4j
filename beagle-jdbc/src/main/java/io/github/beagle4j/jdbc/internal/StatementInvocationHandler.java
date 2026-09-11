package io.github.beagle4j.jdbc.internal;

import io.github.beagle4j.core.model.QueryExecution;
import io.github.beagle4j.core.session.BeagleSession;
import io.github.beagle4j.core.session.SessionContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Observes one {@code Statement}, {@code PreparedStatement} or {@code CallableStatement}.
 *
 * <p>Handles all three because the differences between them are small and local: a
 * prepared statement knows its SQL from construction, a plain one is handed SQL at
 * execution time, and only the prepared forms bind parameters. Three near-identical
 * handlers would be three places to fix every future bug.
 *
 * <p>Every path through {@link #invoke} ends in a delegation to the real statement, and
 * the bookkeeping around it is written so that a failure in Beagle cannot stop that from
 * happening. Observability that can break the thing it observes is not worth having.
 */
public final class StatementInvocationHandler implements InvocationHandler {

    private final Object delegate;
    private final String preparedSql;
    private final ParameterRecorder parameters = new ParameterRecorder();
    private final BooleanSupplier transactionState;

    private QueryExecution lastExecution;

    /**
     * @param transactionState evaluated at execution time, not here. A statement is
     *                         frequently prepared before the transaction that will run it
     *                         has begun -- Spring opens the transaction around the service
     *                         method while the ORM prepares statements inside it, and a
     *                         cached statement can outlive several transactions. Sampling
     *                         the flag at construction would attribute writes to the wrong
     *                         side of the boundary, which is exactly the distinction the
     *                         transaction detectors depend on.
     */
    public StatementInvocationHandler(Object delegate, String preparedSql,
                                      BooleanSupplier transactionState) {
        this.delegate = delegate;
        this.preparedSql = preparedSql;
        this.transactionState = transactionState;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object objectMethodResult =
                Proxies.handleObjectMethod(proxy, method, args, delegate, "BeagleStatement");
        if (objectMethodResult != null) {
            return objectMethodResult;
        }

        String name = method.getName();

        if (name.startsWith("set") && args != null && args.length > 0 && args[0] instanceof Integer) {
            parameters.record(name, args);
            return Proxies.invoke(delegate, method, args);
        }

        if ("clearParameters".equals(name)) {
            parameters.clear();
            return Proxies.invoke(delegate, method, args);
        }

        if (isExecute(name)) {
            return executeObserved(method, args, name);
        }

        // getResultSet() returns the result of the execute() that preceded it, so the
        // rows read through it belong to that execution.
        if ("getResultSet".equals(name)) {
            Object result = Proxies.invoke(delegate, method, args);
            return maybeWrapResultSet(result, lastExecution);
        }

        return Proxies.invoke(delegate, method, args);
    }

    private Object executeObserved(Method method, Object[] args, String name) throws Throwable {
        BeagleSession session = SessionContext.current();
        String sql = resolveSql(args);

        if (session == null || sql == null) {
            // Nothing is watching. This is the overwhelmingly common case in production
            // and it costs one null check.
            return Proxies.invoke(delegate, method, args);
        }

        QueryExecution execution = null;
        try {
            execution = session.recordQueryStart(sql);
            if (execution != null) {
                execution.insideTransaction(transactionState.getAsBoolean());
                List<String> bound = parameters.snapshot();
                if (!bound.isEmpty()) {
                    execution.parameters(bound);
                }
            }
        } catch (RuntimeException ignored) {
            // Recording failed; the query must still run.
            execution = null;
        }

        long startNanos = System.nanoTime();
        Object result;
        try {
            result = Proxies.invoke(delegate, method, args);
        } catch (Throwable t) {
            finish(session, execution, startNanos);
            throw t;
        }
        finish(session, execution, startNanos);

        lastExecution = execution;

        if ("executeQuery".equals(name) || result instanceof ResultSet) {
            return maybeWrapResultSet(result, execution);
        }
        return result;
    }

    private void finish(BeagleSession session, QueryExecution execution, long startNanos) {
        if (execution == null) {
            return;
        }
        try {
            session.recordQueryEnd(execution, System.nanoTime() - startNanos, -1);
        } catch (RuntimeException ignored) {
            // Best effort only.
        }
    }

    private Object maybeWrapResultSet(Object result, QueryExecution execution) {
        if (!(result instanceof ResultSet rs) || execution == null) {
            return result;
        }
        // Rows are counted as the caller walks the cursor, not here: the driver has not
        // been asked for any yet. Seeding the count at zero is what distinguishes
        // "returned nothing" from "we never found out".
        execution.rowsRead(0);
        return Proxies.wrap(rs, new ResultSetInvocationHandler(rs, execution));
    }

    /** Prepared statements carry their SQL; plain ones receive it as the first argument. */
    private String resolveSql(Object[] args) {
        if (preparedSql != null) {
            return preparedSql;
        }
        if (args != null && args.length > 0 && args[0] instanceof String sql) {
            return sql;
        }
        return null;
    }

    private static boolean isExecute(String name) {
        return switch (name) {
            case "execute", "executeQuery", "executeUpdate",
                 "executeLargeUpdate", "executeBatch", "executeLargeBatch" -> true;
            default -> false;
        };
    }
}
