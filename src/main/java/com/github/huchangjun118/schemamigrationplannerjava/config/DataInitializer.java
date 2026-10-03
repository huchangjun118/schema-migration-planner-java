package com.github.huchangjun118.schemamigrationplannerjava.config;

import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 初始化种子操作者（仅在不存在时写入，不覆盖已有角色）。
 */
@Component
public class DataInitializer {

    private final MetadataStore store;

    public DataInitializer(MetadataStore store) {
        this.store = store;
    }

    @PostConstruct
    public void init() {
        seed("admin", "ADMIN");
        seed("applier", "APPLIER");
        seed("viewer", "VIEWER");
    }

    private void seed(String operator, String role) {
        if (store.getRole(operator).isEmpty()) {
            store.setRole(operator, role);
        }
    }
}
