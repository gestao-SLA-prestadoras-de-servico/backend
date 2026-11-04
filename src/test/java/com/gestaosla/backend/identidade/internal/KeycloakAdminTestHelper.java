package com.gestaosla.backend.identidade.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;

/**
 * Contexto mínimo do adaptador para testes unitários: AOP + Resilience4j reais (o {@code @Retry} só funciona via
 * proxy) e as mesmas propriedades de retry da produção, apontando para um servidor simulado. {@code comRegistroLocal}
 * são os {@code sub} que a tabela usuario "tem".
 */
public final class KeycloakAdminTestHelper {

    private KeycloakAdminTestHelper() {
    }

    public static ApplicationContextRunner contexto(String serverUrl, Duration timeout, Set<UUID> comRegistroLocal) {
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        when(usuarios.existsBySub(any())).thenAnswer(chamada -> comRegistroLocal.contains(chamada.<UUID>getArgument(0)));
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, RestClientAutoConfiguration.class,
                        RetryAutoConfiguration.class))
                .withUserConfiguration(KeycloakAdminConfig.class, KeycloakAdminAdapter.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(UsuarioRepository.class, () -> usuarios)
                .withPropertyValues(
                        "app.keycloak.admin.server-url=" + serverUrl,
                        "app.keycloak.admin.realm=flowsla",
                        "app.keycloak.admin.client-id=app-principal-admin",
                        "app.keycloak.admin.client-secret=s3gredo",
                        "app.keycloak.admin.connect-timeout=" + timeout.toMillis() + "ms",
                        "app.keycloak.admin.read-timeout=" + timeout.toMillis() + "ms",
                        "resilience4j.retry.instances.keycloakAdmin.max-attempts=3",
                        "resilience4j.retry.instances.keycloakAdmin.wait-duration=20ms",
                        "resilience4j.retry.instances.keycloakAdmin.enable-exponential-backoff=true",
                        "resilience4j.retry.instances.keycloakAdmin.exponential-backoff-multiplier=2",
                        "resilience4j.retry.instances.keycloakAdmin.retry-exceptions="
                                + "com.gestaosla.backend.identidade.api.IdpIndisponivelException");
    }
}
