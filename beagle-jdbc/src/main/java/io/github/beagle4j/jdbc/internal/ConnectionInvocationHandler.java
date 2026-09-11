package io.github.beagle4j.jdbc.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;

/**
 * Observes one borrowed {@link Connection}: hands out instrumented statements and keeps
 * track of whether a transaction is open.
 *
 * <p>Transaction state is tracked here rather than asked for on demand, because
 * {@code getAutoCommit()} is a driver call and would be one extra round trip per
 * statement on some drivers. The state changes only through {@code setAutoCommit},
 * {@code commit} and {@code rollback}, all of which pass through this handler, so
 * shadowing it is both cheap and accurate.
 */
public final class ConnectionInvocationHandler implements InvocationHandler {

    private final Connection delegate;
    private Boolean autoCommit;

    public ConnectionInvocationHandler(Connection delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object objectMethodResult =
                Proxies.handleObjectMethod(proxy, method, args, delegate, "BeagleConnection");
        if (objectMethodResult != null) {
            return objectMethodResult;
        }

        String name = method.getName();

        if ("setAutoCommit".equals(name) && args != null && args.length == 1
                && args[0] instanceof Boolean flag) {
            autoCommit = flag;
            return Proxies.invoke(delegate, method, args);
        }

        if ("commit".equals(name) || "rollback".equals(name)) {
            return Proxies.invoke(delegate, method, args);
        }

        if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
            String sql = (args != null && args.length > 0 && args[0] instanceof String s) ? s : null;
            Object statement = Proxies.invoke(delegate, method, args);
            return wrapStatement(statement, sql);
        }

        if ("createStatement".equals(name)) {
            Object statement = Proxies.invoke(delegate, method, args);
            return wrapStatement(statement, null);
        }

        return Proxies.invoke(delegate, method, args);
    }

    private Object wrapStatement(Object statement, String sql) {
        if (statement == null) {
            return null;
        }
        return Proxies.wrap(statement,
                new StatementInvocationHandler(statement, sql, isInsideTransaction()));
    }

    private boolean isInsideTransaction() {
        if (autoCommit == null) {
            try {
                autoCommit = delegate.getAutoCommit();
            } catch (Exception e) {
                // Unknowable; assume no transaction rather than invent one.
                autoCommit = Boolean.TRUE;
            }
        }
        return !autoCommit;
    }
}
