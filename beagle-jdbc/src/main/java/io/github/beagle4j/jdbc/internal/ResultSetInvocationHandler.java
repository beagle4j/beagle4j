package io.github.beagle4j.jdbc.internal;

import io.github.beagle4j.core.model.QueryExecution;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Counts the rows a caller actually consumes.
 *
 * <p>This count is what makes high-confidence N+1 detection possible: correlating a
 * burst of repeated queries against the size of the result set that preceded them needs
 * that size, and JDBC never reports it. The driver knows how many rows it has buffered,
 * the {@code ResultSet} will not say, and for a forward-only cursor the number genuinely
 * is not known until the cursor is exhausted. Counting successful {@code next()} calls
 * is the only portable way to find out.
 *
 * <p>It also measures the right thing. What drives an N+1 is how many rows the
 * application <em>iterated</em>, not how many the query could have returned, and those
 * differ whenever a loop breaks early.
 *
 * <p>Absolute cursor movement ({@code absolute}, {@code relative}, {@code first}) is not
 * counted. Scrollable result sets are rare in the frameworks this targets, and a
 * cursor that can revisit rows cannot be turned into a row count by counting moves.
 */
public final class ResultSetInvocationHandler implements InvocationHandler {

    private final Object delegate;
    private final QueryExecution execution;
    private int rows;

    public ResultSetInvocationHandler(Object delegate, QueryExecution execution) {
        this.delegate = delegate;
        this.execution = execution;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object objectMethodResult =
                Proxies.handleObjectMethod(proxy, method, args, delegate, "BeagleResultSet");
        if (objectMethodResult != null) {
            return objectMethodResult;
        }

        Object result = Proxies.invoke(delegate, method, args);

        if ("next".equals(method.getName()) && Boolean.TRUE.equals(result)) {
            rows++;
            // Published on every row rather than at close(), because application code
            // frequently never closes a result set explicitly -- it lets the statement
            // do it. Updating as we go means the count is correct whether or not the
            // close ever arrives.
            execution.rowsRead(rows);
        }
        return result;
    }
}
