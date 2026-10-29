package com.lab.dbscale.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Default profile  → Spring Boot auto-configures a single Hikari pool
 *                    from spring.datasource.* (no bean needed here).
 *
 * "replica" profile → this class creates primary + replica pools and a
 *                    routing DataSource that sends @Transactional(readOnly=true)
 *                    traffic to the replica automatically.
 *
 * Lab #7: Start the app with -Dspring.profiles.active=replica to see routing.
 */
@Configuration
@Profile("replica")
public class DataSourceConfig {

    // ── Primary (write) ──────────────────────────────────────────────────────
    @Bean
    @ConfigurationProperties("spring.datasource.primary")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "primaryDataSource")
    @ConfigurationProperties("spring.datasource.primary.hikari")
    public HikariDataSource primaryDataSource() {
        return primaryDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    // ── Replica (read) ───────────────────────────────────────────────────────
    @Bean
    @ConfigurationProperties("spring.datasource.replica")
    public DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "replicaDataSource")
    @ConfigurationProperties("spring.datasource.replica.hikari")
    public HikariDataSource replicaDataSource() {
        return replicaDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    // ── Routing datasource ───────────────────────────────────────────────────
    @Primary
    @Bean
    public DataSource routingDataSource(
            @Qualifier("primaryDataSource") DataSource primary,
            @Qualifier("replicaDataSource") DataSource replica) {

        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                // readOnly=true  → route to REPLICA pool
                // readOnly=false → route to PRIMARY pool
                return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        ? "REPLICA" : "PRIMARY";
            }
        };

        Map<Object, Object> sources = new HashMap<>();
        sources.put("PRIMARY", primary);
        sources.put("REPLICA", replica);
        routing.setTargetDataSources(sources);
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();
        return routing;
    }
}
