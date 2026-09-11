package io.github.beagle4j.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallSiteTest {

    @Test
    @DisplayName("an ordinary method name is left alone")
    void keepsOrdinaryMethodNames() {
        CallSite site = new CallSite("com.acme.OrderService", "loadItems", "OrderService.java", 42);
        assertThat(site.displayMethodName()).isEqualTo("loadItems");
        assertThat(site.toString()).isEqualTo("com.acme.OrderService#loadItems(OrderService.java:42)");
    }

    @Test
    @DisplayName("a lambda is reported as the method that encloses it")
    void unwrapsLambdaNames() {
        // What actually appears on the stack when work happens inside a stream lambda.
        // Reporting it verbatim makes a reader stop and decode a compiler artifact, when
        // the line number already says exactly where they are.
        CallSite site = new CallSite(
                "com.example.bookshop.AuthorController", "lambda$slow$0",
                "AuthorController.java", 36);
        assertThat(site.displayMethodName()).isEqualTo("slow");
        assertThat(site.toString())
                .isEqualTo("com.example.bookshop.AuthorController#slow(AuthorController.java:36)");
    }

    @Test
    @DisplayName("the raw name stays available for anyone who needs the truth")
    void preservesRawMethodName() {
        CallSite site = new CallSite("c.A", "lambda$slow$0", "A.java", 1);
        assertThat(site.methodName()).isEqualTo("lambda$slow$0");
    }

    @Test
    @DisplayName("a lambda nested in a lambda is not renamed to \"null\"")
    void leavesUndecodableSyntheticNamesAlone() {
        // javac names these lambda$null$1. Unwrapping naively yields "null", which is
        // worse than the synthetic name: it looks like a bug in the report.
        CallSite site = new CallSite("c.A", "lambda$null$1", "A.java", 1);
        assertThat(site.displayMethodName()).isEqualTo("lambda$null$1");
    }

    @Test
    @DisplayName("malformed synthetic names do not throw")
    void handlesMalformedSyntheticNames() {
        assertThat(new CallSite("c.A", "lambda$", "A.java", 1).displayMethodName())
                .isEqualTo("lambda$");
        assertThat(new CallSite("c.A", "lambda$$0", "A.java", 1).displayMethodName())
                .isEqualTo("lambda$$0");
    }

    @Test
    @DisplayName("an unresolved call site identifies itself as such")
    void reportsUnknownSites() {
        assertThat(CallSite.UNKNOWN.isKnown()).isFalse();
        assertThat(new CallSite("c.A", "m", "A.java", 1).isKnown()).isTrue();
    }
}
