package com.gestaosla.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("pgvector/pgvector:pg17");
    }

    // O JWKS do TokenTeste no lugar do Keycloak.
    @Bean
    DynamicPropertyRegistrar jwkSetUri() {
        return registro -> registro.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> TokenTeste.JWK_SET_URI);
    }
}
