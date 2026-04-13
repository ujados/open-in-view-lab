package com.example.osivlab;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton Testcontainer shared across ALL test classes.
 * Avoids stale port references when Spring caches contexts.
 */
public final class SharedPostgresContainer {

    private static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osivlab")
                    .withUsername("test")
                    .withPassword("test");

    static {
        INSTANCE.start();
    }

    private SharedPostgresContainer() {}

    public static PostgreSQLContainer<?> getInstance() {
        return INSTANCE;
    }
}
