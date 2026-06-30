package com.matrixcode.persistence.application;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigrationInitializer implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (environment == null) {
            return;
        }
        var mode = environment.getProperty("matrixcode.persistence.mode", "file");
        var migrateOnStartup = environment.getProperty(
                "matrixcode.persistence.jdbc.migrate-on-startup",
                Boolean.class,
                false
        );
        if (!"jdbc".equalsIgnoreCase(mode) || !migrateOnStartup) {
            return;
        }

        DatabaseMigrator.migrate(
                environment.getProperty("matrixcode.persistence.jdbc.url", ""),
                environment.getProperty("matrixcode.persistence.jdbc.username", ""),
                environment.getProperty("matrixcode.persistence.jdbc.password", ""),
                environment.getProperty(
                        "matrixcode.persistence.jdbc.create-database-if-missing",
                        Boolean.class,
                        false
                )
        );
    }
}
