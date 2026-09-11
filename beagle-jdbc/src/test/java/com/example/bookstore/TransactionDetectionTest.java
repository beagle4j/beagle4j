package com.example.bookstore;

import io.github.beagle4j.core.BeagleRuntime;
import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.detect.DetectionReport;
import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.FindingType;
import io.github.beagle4j.core.model.Severity;
import io.github.beagle4j.core.report.ConsoleReporter;
import io.github.beagle4j.jdbc.BeagleDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the transaction rule against a real driver, where autocommit means what the
 * database says it means rather than what a linter guessed.
 *
 * <p>This is the point of detecting the symptom instead of the annotation: none of these
 * tests mention {@code @Transactional}, and the rule would catch every way of failing to
 * open a transaction equally well — including ones nobody has thought of yet.
 */
class TransactionDetectionTest {

    private BeagleRuntime beagle;
    private BookstoreService bookstore;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:tx-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        try (Connection connection = h2.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE author (id INT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE book (id INT PRIMARY KEY, author_id INT, title VARCHAR(200))");
            statement.execute("INSERT INTO author VALUES (1, 'Author 1')");
        }
        beagle = new BeagleRuntime(BeagleConfig.builder()
                .applicationPackage("com.example")
                .build());
        bookstore = new BookstoreService(BeagleDataSource.wrap(h2));
    }

    @Test
    @DisplayName("catches two related writes that were never made atomic")
    void catchesWritesThatWereNotAtomic() {
        DetectionReport report = beagle.observe("POST /books",
                () -> bookstore.addBookAndTouchAuthor_noTransaction(1, 100));

        assertThat(report.ofType(FindingType.WRITE_OUTSIDE_TRANSACTION)).hasSize(1);
        Finding finding = report.ofType(FindingType.WRITE_OUTSIDE_TRANSACTION).get(0);

        assertThat(finding.occurrences()).isEqualTo(2);
        assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
        // Observed, not inferred: autocommit was demonstrably on when these ran.
        assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(finding.sampleStatements())
                .anySatisfy(s -> assertThat(s).startsWith("insert into book"))
                .anySatisfy(s -> assertThat(s).startsWith("update author"));

        System.out.println();
        System.out.println(new ConsoleReporter(88, false, false).render(report));
    }

    @Test
    @DisplayName("says nothing when the transaction is real")
    void staysSilentWhenTheTransactionIsReal() {
        DetectionReport report = beagle.observe("POST /books",
                () -> bookstore.addBookAndTouchAuthor_inTransaction(1, 101));

        assertThat(report.queryCount())
                .as("the same two writes still happened")
                .isEqualTo(2);
        assertThat(report.ofType(FindingType.WRITE_OUTSIDE_TRANSACTION))
                .as("correct code must produce a clean report")
                .isEmpty();
    }

    @Test
    @DisplayName("does not mistake a batch loop for a broken transaction")
    void staysSilentOnARepeatedWrite() {
        DetectionReport report = beagle.observe("POST /import",
                () -> bookstore.insertAuthorsInLoop(8));

        // Eight writes, autocommit on, and deliberately so. One statement repeated is a
        // batching question, not a lost unit of work -- reporting it here would make the
        // rule useless for the case it exists to catch.
        assertThat(report.queryCount()).isEqualTo(8);
        assertThat(report.ofType(FindingType.WRITE_OUTSIDE_TRANSACTION)).isEmpty();
    }

    @Test
    @DisplayName("transaction state is read when the statement runs, not when it is prepared")
    void readsTransactionStateAtExecutionTime() throws SQLException {
        // Spring opens its transaction around the service method while the ORM prepares
        // statements inside it, so preparation routinely happens on the other side of the
        // boundary from execution. Sampling the flag too early puts writes on the wrong
        // side and the rule silently stops working.
        DataSource observed = BeagleDataSource.wrap(rawDataSource());

        DetectionReport report = beagle.observe("POST /books", () -> {
            try (Connection connection = observed.getConnection()) {
                var insert = connection.prepareStatement(
                        "INSERT INTO book (id, author_id, title) VALUES (?, ?, ?)");
                var update = connection.prepareStatement(
                        "UPDATE author SET name = ? WHERE id = ?");

                // Both statements were prepared with autocommit on; the transaction
                // begins only now.
                connection.setAutoCommit(false);
                insert.setInt(1, 200);
                insert.setInt(2, 1);
                insert.setString(3, "Prepared early");
                insert.executeUpdate();
                update.setString(1, "Renamed");
                update.setInt(2, 1);
                update.executeUpdate();
                connection.commit();
                connection.setAutoCommit(true);

                insert.close();
                update.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(report.ofType(FindingType.WRITE_OUTSIDE_TRANSACTION))
                .as("both writes ran inside the transaction, however early they were prepared")
                .isEmpty();
    }

    private DataSource rawDataSource() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:txlate-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        try (Connection connection = h2.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE author (id INT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE book (id INT PRIMARY KEY, author_id INT, title VARCHAR(200))");
            statement.execute("INSERT INTO author VALUES (1, 'Author 1')");
        }
        return h2;
    }
}
