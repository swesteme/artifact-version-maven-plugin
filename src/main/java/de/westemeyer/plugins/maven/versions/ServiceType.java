package de.westemeyer.plugins.maven.versions;

/**
 * The kind of service to generate.
 */
public enum ServiceType {
    /**
     * Generate a service class for a plain Java {@link java.util.ServiceLoader}.
     */
    NATIVE,

    /**
     * Generate a Spring Boot service.
     */
    SPRING_BOOT
}
