package com.example.benchmark;

import io.github.beagle4j.core.BeagleRuntime;
import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.session.BeagleSession;
import io.github.beagle4j.core.session.SessionContext;
import io.github.beagle4j.jdbc.BeagleDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * Measures what the instrumentation costs on the JDBC path.
 *
 * <h2>Why H2 in memory, when nobody runs H2 in memory</h2>
 * <p>Because it is the <em>conservative</em> choice, not the flattering one. A real
 * database answers over a network in milliseconds, which would bury any overhead Beagle
 * adds and produce a reassuring number that means nothing. H2 in memory answers in
 * microseconds, so the proxy layer is a far larger fraction of each operation than it
 * could ever be in production. Whatever percentage shows up here is an upper bound:
 * against Postgres over a socket it can only be smaller.
 *
 * <h2>The four cases</h2>
 * <ol>
 *   <li>{@code rawJdbc} — the floor. No wrapper at all.</li>
 *   <li>{@code wrappedNoSession} — wrapped, but nothing observing. This is what a
 *       production deployment with Beagle switched off actually pays, and it is the
 *       number that decides whether leaving the dependency in place is acceptable.</li>
 *   <li>{@code wrappedObservedSteadyState} — wrapped and recording, past the stack-capture
 *       cap. The common case inside a real request, once a statement has been seen a few
 *       times.</li>
 *   <li>{@code wrappedObservedAlwaysCapturingStacks} — the deliberate worst case, with the
 *       cap raised so that every single execution walks the stack. Nobody runs this
 *       configuration; it exists to show what the lazy-capture strategy is buying.</li>
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
@org.openjdk.jmh.annotations.Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@State(Scope.Benchmark)
public class JdbcOverheadBenchmark {

    private static final int ROWS = 50;

    private DataSource raw;
    private DataSource wrapped;
    private BeagleRuntime steadyStateRuntime;
    private BeagleRuntime alwaysCapturingRuntime;

    private BeagleSession session;

    @Setup(Level.Trial)
    public void setUpDatabase() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:bench-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        try (Connection connection = h2.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(64))");
            for (int i = 1; i <= ROWS; i++) {
                statement.execute("INSERT INTO item VALUES (" + i + ", 'item-" + i + "')");
            }
        }
        this.raw = h2;
        this.wrapped = BeagleDataSource.wrap(h2);

        this.steadyStateRuntime = new BeagleRuntime(BeagleConfig.builder()
                .applicationPackage("com.example")
                // Effectively unbounded: a benchmark iteration issues far more statements
                // than a request would, and hitting the cap mid-run would silently switch
                // recording off and measure the wrong thing.
                .maxQueriesPerSession(Integer.MAX_VALUE)
                .build());

        this.alwaysCapturingRuntime = new BeagleRuntime(BeagleConfig.builder()
                .applicationPackage("com.example")
                .maxQueriesPerSession(Integer.MAX_VALUE)
                .maxStackCapturesPerTemplate(Integer.MAX_VALUE)
                .build());
    }

    /**
     * A fresh session per iteration. Sessions accumulate one record per statement, so
     * reusing one across the whole trial would measure a list growing into the hundreds of
     * millions rather than the cost of an individual call.
     */
    @Setup(Level.Iteration)
    public void openSession() {
        this.session = null;
    }

    @TearDown(Level.Iteration)
    public void closeSession() {
        // Closed directly rather than through the runtime: running the detectors over a
        // million recorded statements would take far longer than the benchmark itself,
        // and it is not what is being measured.
        if (session != null) {
            session.close();
        }
        SessionContext.clear();
        session = null;
    }

    @Benchmark
    public void rawJdbc(Blackhole blackhole) throws SQLException {
        query(raw, blackhole);
    }

    @Benchmark
    public void wrappedNoSession(Blackhole blackhole) throws SQLException {
        query(wrapped, blackhole);
    }

    @Benchmark
    public void wrappedObservedSteadyState(Blackhole blackhole) throws SQLException {
        ensureSession(steadyStateRuntime);
        query(wrapped, blackhole);
    }

    @Benchmark
    public void wrappedObservedAlwaysCapturingStacks(Blackhole blackhole) throws SQLException {
        ensureSession(alwaysCapturingRuntime);
        query(wrapped, blackhole);
    }

    private void ensureSession(BeagleRuntime runtime) {
        if (session == null || session.isClosed()) {
            session = runtime.openSession("benchmark");
        }
    }

    /** One prepared statement, executed, with every row consumed. */
    private void query(DataSource dataSource, Blackhole blackhole) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("SELECT id, name FROM item WHERE id > ?")) {
            statement.setInt(1, 0);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    blackhole.consume(rows.getInt(1));
                    blackhole.consume(rows.getString(2));
                }
            }
        }
    }
}
