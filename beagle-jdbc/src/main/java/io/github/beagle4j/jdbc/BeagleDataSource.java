package io.github.beagle4j.jdbc;

import io.github.beagle4j.jdbc.internal.ConnectionInvocationHandler;
import io.github.beagle4j.jdbc.internal.Proxies;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that watches the traffic passing through it.
 *
 * <p>Wrap an existing data source and hand this one to the application instead:
 *
 * <pre>{@code
 * DataSource observed = BeagleDataSource.wrap(hikariDataSource);
 * }</pre>
 *
 * <p>Everything downstream -- Hibernate, MyBatis, JdbcTemplate, the connection pool --
 * keeps working unchanged, because every object handed out still implements every
 * interface the original did.
 *
 * <p>Instrumentation only engages while a session is open on the calling thread. Outside
 * one, the added cost is a null check per statement, which is why leaving this in place
 * in an environment where Beagle is switched off is not a problem.
 *
 * <p>This is a concrete class rather than another dynamic proxy on purpose: a data source
 * is something a developer names and configures by hand, and a type they can see in
 * their configuration is easier to reason about than a proxy that appears from nowhere.
 * The objects it hands out downstream are proxies, because those are not configured by
 * anyone.
 */
public final class BeagleDataSource implements DataSource {

    private final DataSource delegate;

    private BeagleDataSource(DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Wraps {@code delegate}, or returns it unchanged if it is already wrapped. */
    public static DataSource wrap(DataSource delegate) {
        if (delegate instanceof BeagleDataSource) {
            return delegate;
        }
        return new BeagleDataSource(delegate);
    }

    /** The data source underneath, for callers that need the real thing. */
    public DataSource delegate() {
        return delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return observe(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return observe(delegate.getConnection(username, password));
    }

    private Connection observe(Connection connection) {
        if (connection == null) {
            return null;
        }
        return (Connection) Proxies.wrap(connection, new ConnectionInvocationHandler(connection));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    /**
     * Pools and ORMs unwrap a data source to reach vendor APIs. Answering for ourselves
     * first and then delegating keeps that working; refusing to unwrap would break
     * anything that needs the native type.
     */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    @Override
    public String toString() {
        return "BeagleDataSource[" + delegate + "]";
    }
}
