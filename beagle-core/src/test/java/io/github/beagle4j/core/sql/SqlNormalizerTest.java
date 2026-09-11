package io.github.beagle4j.core.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNormalizerTest {

    private final SqlNormalizer normalizer = new SqlNormalizer();

    @Test
    @DisplayName("replaces numeric and string literals with placeholders")
    void replacesLiterals() {
        assertThat(normalizer.normalize("SELECT * FROM orders WHERE id = 42"))
                .isEqualTo("select * from orders where id = ?");
        assertThat(normalizer.normalize("SELECT * FROM users WHERE name = 'bob'"))
                .isEqualTo("select * from users where name = ?");
        assertThat(normalizer.normalize("SELECT * FROM t WHERE price = 19.99"))
                .isEqualTo("select * from t where price = ?");
        assertThat(normalizer.normalize("SELECT * FROM t WHERE ratio = 1.5e-9"))
                .isEqualTo("select * from t where ratio = ?");
    }

    @Test
    @DisplayName("statements differing only in their literals share a template")
    void literalsDoNotSplitTemplates() {
        String a = normalizer.normalize("SELECT * FROM book WHERE author_id = 1");
        String b = normalizer.normalize("SELECT * FROM book WHERE author_id = 2");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("a doubled quote does not end a string literal")
    void handlesDoubledQuoteEscape() {
        // The naive regex approach ends the literal at the second quote and leaks
        // `s fine` into the template, so two statements with different notes would be
        // grouped apart -- and an N+1 would go unreported.
        assertThat(normalizer.normalize("SELECT * FROM t WHERE note = 'it''s fine' AND id = 1"))
                .isEqualTo("select * from t where note = ? and id = ?");
    }

    @Test
    @DisplayName("a backslash escape does not end a string literal")
    void handlesBackslashEscape() {
        assertThat(normalizer.normalize("SELECT * FROM t WHERE p = 'a\\'b' AND id = 7"))
                .isEqualTo("select * from t where p = ? and id = ?");
    }

    @Test
    @DisplayName("comment markers inside a literal are not treated as comments")
    void doesNotTreatLiteralContentAsComment() {
        assertThat(normalizer.normalize("SELECT * FROM t WHERE note = 'x -- y' AND id = 3"))
                .isEqualTo("select * from t where note = ? and id = ?");
    }

    @Test
    @DisplayName("digits that are part of an identifier are left alone")
    void preservesIdentifiersContainingDigits() {
        assertThat(normalizer.normalize("SELECT t2.column1 FROM table3 t2 WHERE t2.id = 9"))
                .isEqualTo("select t2.column1 from table3 t2 where t2.id = ?");
    }

    @Test
    @DisplayName("IN lists of different lengths collapse to one template")
    void collapsesInLists() {
        String three = normalizer.normalize("SELECT * FROM t WHERE id IN (1, 2, 3)");
        String seven = normalizer.normalize("SELECT * FROM t WHERE id IN (4,5,6,7,8,9,10)");
        assertThat(three).isEqualTo("select * from t where id in (?)");
        assertThat(three).isEqualTo(seven);
    }

    @Test
    @DisplayName("IN lists of bind placeholders also collapse")
    void collapsesInListsOfPlaceholders() {
        assertThat(normalizer.normalize("SELECT * FROM t WHERE id IN (?, ?, ?)"))
                .isEqualTo("select * from t where id in (?)");
    }

    @Test
    @DisplayName("multi-row VALUES clauses collapse to one template")
    void collapsesValuesLists() {
        assertThat(normalizer.normalize("INSERT INTO t (a,b) VALUES (1,2),(3,4),(5,6)"))
                .isEqualTo("insert into t (a,b) values (?)");
    }

    @Test
    @DisplayName("comments are removed and whitespace collapsed")
    void stripsCommentsAndWhitespace() {
        String sql = """
                SELECT id,        name
                  -- pick everyone
                  FROM   users /* inline */ WHERE active = 1
                """;
        assertThat(normalizer.normalize(sql))
                .isEqualTo("select id, name from users where active = ?");
    }

    @Test
    @DisplayName("quoted identifiers are kept, not replaced")
    void keepsQuotedIdentifiers() {
        assertThat(normalizer.normalize("SELECT \"Order\" FROM t WHERE id = 1"))
                .isEqualTo("select \"order\" from t where id = ?");
    }

    @Test
    @DisplayName("hex literals are replaced")
    void replacesHexLiterals() {
        assertThat(normalizer.normalize("SELECT * FROM t WHERE b = 0xFF"))
                .isEqualTo("select * from t where b = ?");
    }

    @Test
    @DisplayName("trailing semicolons do not split templates")
    void ignoresTrailingSemicolon() {
        assertThat(normalizer.normalize("SELECT 1;"))
                .isEqualTo(normalizer.normalize("SELECT 1"));
    }

    @Test
    @DisplayName("repeated normalisation of the same statement is memoised")
    void cachesResults() {
        String sql = "SELECT * FROM t WHERE id = 1";
        String first = normalizer.normalize(sql);
        String second = normalizer.normalize(sql);
        assertThat(first).isSameAs(second);
        assertThat(normalizer.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("null and empty input are handled")
    void handlesEmptyInput() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("")).isEmpty();
    }
}
