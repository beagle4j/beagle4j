package io.github.beagle4j.spring;

import io.github.beagle4j.core.BeagleRuntime;
import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.HashSet;
import java.util.List;
import javax.sql.DataSource;

/**
 * Wires Beagle into a Spring Boot application.
 *
 * <p>Adding the dependency is the whole installation. Every {@code DataSource} in the
 * context is instrumented and, on a servlet stack, every request becomes an observed unit
 * of work.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}, so any piece can be replaced by
 * declaring your own -- a {@link ReportHandler} that fails the build instead of logging,
 * a {@link BeagleRuntime} with a custom detector set.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "beagle", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BeagleProperties.class)
public class BeagleAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BeagleAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public BeagleRuntime beagleRuntime(BeagleProperties properties) {
        if (properties.getApplicationPackage() == null) {
            // Worth saying out loud. Without it, attribution falls back to "the first
            // frame that is not a known framework", and an unrecognised library can end
            // up blamed for a query the application issued -- which looks like a bug in
            // Beagle rather than a missing setting.
            log.warn("beagle.application-package is not set. Findings will still be "
                    + "reported, but attribution to your own code will be less reliable. "
                    + "Set it to your base package, e.g. beagle.application-package=com.acme");
        }
        return new BeagleRuntime(properties.toCoreConfig());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportHandler beagleReportHandler(BeagleProperties properties) {
        return new LoggingReportHandler(properties.getReport());
    }

    /**
     * Static, and bound from the {@link Environment} rather than from the properties bean.
     *
     * <p>A {@code BeanPostProcessor} is instantiated before the regular beans it is meant
     * to post-process. Injecting {@code BeagleProperties} here would drag the properties
     * bean into that early phase, and everything it touches with it -- which is how a
     * starter ends up printing "is not eligible for getting processed by all
     * BeanPostProcessors" warnings and silently disabling other people's post-processing.
     */
    @Bean
    public static DataSourceInstrumentationPostProcessor beagleDataSourceInstrumentation(
            Environment environment) {
        List<String> excluded = Binder.get(environment)
                .bind("beagle.excluded-data-sources", Bindable.listOf(String.class))
                .orElseGet(List::of);
        return new DataSourceInstrumentationPostProcessor(new HashSet<>(excluded));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "beagle.web", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class ServletConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public FilterRegistrationBean<BeagleServletFilter> beagleServletFilter(
                BeagleRuntime runtime, ReportHandler reportHandler, BeagleProperties properties) {

            BeagleServletFilter filter = new BeagleServletFilter(
                    runtime, reportHandler, properties.getWeb().getExcludedPaths());

            FilterRegistrationBean<BeagleServletFilter> registration =
                    new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/*");
            // Early, so the session spans as much of the request as possible -- but not
            // first, leaving room for the filters that set up encoding and security
            // context before anything worth observing happens.
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
            registration.setName("beagleServletFilter");
            return registration;
        }
    }
}
