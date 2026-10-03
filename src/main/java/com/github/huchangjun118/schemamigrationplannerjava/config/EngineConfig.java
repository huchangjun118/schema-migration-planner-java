package com.github.huchangjun118.schemamigrationplannerjava.config;

import com.github.huchangjun118.schemamigrationplannerjava.store.MetaStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the migration engine: binds configuration properties and initializes
 * the embedded metastore at startup.
 */
@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
public class EngineConfig implements ApplicationRunner {
    private final MetaStore metaStore;

    public EngineConfig(MetaStore metaStore) {
        this.metaStore = metaStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        metaStore.initialize();
    }
}
