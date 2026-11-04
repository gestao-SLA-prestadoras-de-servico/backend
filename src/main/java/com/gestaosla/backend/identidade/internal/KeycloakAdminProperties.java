package com.gestaosla.backend.identidade.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.keycloak.admin.*} do application.properties; o segredo vem de KEYCLOAK_ADMIN_CLIENT_SECRET (RN-137). */
@ConfigurationProperties(prefix = "app.keycloak.admin")
record KeycloakAdminProperties(
        String serverUrl,
        String realm,
        String clientId,
        String clientSecret,
        Duration connectTimeout,
        Duration readTimeout) {
}
