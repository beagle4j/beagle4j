package io.github.beagle4j.spring;

import io.github.beagle4j.jdbc.BeagleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Set;
import javax.sql.DataSource;

/**
 * Wraps every {@link DataSource} in the context so that instrumentation needs no change
 * to application code.
 *
 * <p>A {@link BeanPostProcessor} rather than a replacement {@code @Bean}, because it works
 * regardless of how the data source was declared -- auto-configured, hand-written,
 * multiple of them, or contributed by another starter.
 *
 * <h2>The one way this can break an application</h2>
 * <p>The bean handed back is a {@code BeagleDataSource}, not the original type. Code that
 * injects the <em>interface</em> is unaffected, which is the overwhelming majority; code
 * that injects a <em>concrete</em> type -- {@code HikariDataSource} to reach its pool
 * statistics, say -- will no longer find a matching bean.
 *
 * <p>That is a real cost and it is stated rather than hidden: {@code unwrap()} still
 * reaches the original, and {@code beagle.excluded-data-sources} opts a named bean out
 * entirely. The alternative designs are worse. Generating a subclass proxy would drag in
 * a bytecode library and still fail on final classes; leaving the data source alone would
 * mean every user wiring up instrumentation by hand, which is the difference between a
 * tool people try and a tool people read about.
 */
public class DataSourceInstrumentationPostProcessor implements BeanPostProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(DataSourceInstrumentationPostProcessor.class);

    private final Set<String> excluded;

    public DataSourceInstrumentationPostProcessor(Set<String> excluded) {
        this.excluded = Set.copyOf(excluded);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DataSource dataSource) || bean instanceof BeagleDataSource) {
            return bean;
        }
        if (excluded.contains(beanName)) {
            log.debug("Beagle is leaving data source '{}' alone (excluded by configuration)", beanName);
            return bean;
        }
        log.info("Beagle is watching data source '{}' ({})",
                beanName, bean.getClass().getSimpleName());
        return BeagleDataSource.wrap(dataSource);
    }
}
