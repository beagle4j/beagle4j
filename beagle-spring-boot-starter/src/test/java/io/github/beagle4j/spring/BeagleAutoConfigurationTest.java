package io.github.beagle4j.spring;

import io.github.beagle4j.core.BeagleRuntime;
import io.github.beagle4j.jdbc.BeagleDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class BeagleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BeagleAutoConfiguration.class))
            .withUserConfiguration(DataSourceConfiguration.class);

    @Test
    @DisplayName("instruments every data source with no configuration at all")
    void instrumentsDataSourceOutOfTheBox() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BeagleRuntime.class);
            assertThat(context).hasSingleBean(ReportHandler.class);
            assertThat(context.getBean(DataSource.class)).isInstanceOf(BeagleDataSource.class);
        });
    }

    @Test
    @DisplayName("backs off entirely when disabled")
    void backsOffWhenDisabled() {
        runner.withPropertyValues("beagle.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(BeagleRuntime.class);
            assertThat(context.getBean(DataSource.class))
                    .as("a disabled tool must not leave a wrapper behind")
                    .isNotInstanceOf(BeagleDataSource.class);
        });
    }

    @Test
    @DisplayName("leaves a named data source alone when it is excluded")
    void honoursExcludedDataSources() {
        runner.withPropertyValues("beagle.excluded-data-sources=dataSource").run(context ->
                assertThat(context.getBean(DataSource.class))
                        .isNotInstanceOf(BeagleDataSource.class));
    }

    @Test
    @DisplayName("binds every documented property, including the awkwardly named one")
    void bindsProperties() {
        runner.withPropertyValues(
                "beagle.application-package=com.acme",
                // Leading-acronym property names are a classic relaxed-binding trap:
                // the JavaBeans name for getNPlusOneThreshold() does not decapitalise.
                // Asserted explicitly so a rename cannot break it silently.
                "beagle.n-plus-one-threshold=7",
                "beagle.repeated-query-threshold=5",
                "beagle.row-correlation-tolerance=0.4",
                "beagle.slow-query-threshold=1500ms",
                "beagle.max-queries-per-session=99"
        ).run(context -> {
            BeagleProperties properties = context.getBean(BeagleProperties.class);
            assertThat(properties.getApplicationPackage()).isEqualTo("com.acme");
            assertThat(properties.getNPlusOneThreshold()).isEqualTo(7);
            assertThat(properties.getRepeatedQueryThreshold()).isEqualTo(5);
            assertThat(properties.getRowCorrelationTolerance()).isEqualTo(0.4d);
            assertThat(properties.getSlowQueryThreshold().toMillis()).isEqualTo(1500L);

            // and that they actually reach the engine, not just the properties object
            BeagleRuntime runtime = context.getBean(BeagleRuntime.class);
            assertThat(runtime.config().getNPlusOneThreshold()).isEqualTo(7);
            assertThat(runtime.config().getApplicationPackage()).isEqualTo("com.acme");
            assertThat(runtime.config().getMaxQueriesPerSession()).isEqualTo(99);
        });
    }

    @Test
    @DisplayName("a user-supplied report handler replaces the default")
    void allowsCustomReportHandler() {
        runner.withUserConfiguration(CustomHandlerConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(ReportHandler.class);
            assertThat(context.getBean(ReportHandler.class))
                    .isSameAs(CustomHandlerConfiguration.HANDLER);
        });
    }

    @Test
    @DisplayName("does not register a servlet filter outside a web application")
    void noFilterWithoutAServletStack() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BeagleServletFilter.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:starter-" + UUID.randomUUID());
            h2.setUser("sa");
            return h2;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfiguration {
        static final ReportHandler HANDLER = report -> {
        };

        @Bean
        ReportHandler beagleReportHandler() {
            return HANDLER;
        }
    }
}
