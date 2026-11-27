package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.CHAVE;
import static com.gestaosla.backend.TokenTeste.ISSUER;
import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.novoPar;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.nimbusds.jwt.JWTClaimsSet;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PrometheusSegurancaTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldRejectAnonymousScrapeWithProblemDetails() throws Exception {
        naoAutenticado(mvc.perform(get("/actuator/prometheus")));
    }

    @Test
    void shouldAllowMetricsClientWithoutLocalUser() throws Exception {
        JWTClaimsSet claims = metricas().build();
        assertThat(jdbc.queryForObject("select count(*) from usuario where sub = ?", Long.class,
                UUID.fromString(claims.getSubject()))).isZero();

        coletar(token(CHAVE, claims))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("outbox_eventos_pendentes")))
                .andExpect(content().string(containsString("outbox_pendente_mais_antigo_idade_seconds")))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @ParameterizedTest
    @MethodSource("permissoesInvalidas")
    void shouldRejectMissingMalformedOrWrongClientRole(Object recurso) throws Exception {
        coletar(token(CHAVE, metricas().claim("resource_access", recurso).build()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
    }

    static Stream<Object> permissoesInvalidas() {
        return Stream.of(null, "invalido", Map.of(),
                Map.of("outro-client", Map.of("roles", List.of("metrics.read"))),
                Map.of("flowsla-metrics", "invalido"),
                Map.of("flowsla-metrics", Map.of("roles", "metrics.read")),
                Map.of("flowsla-metrics", Map.of("roles", List.of("outra-permissao"))));
    }

    @Test
    void shouldRejectBusinessAndRealmMetricsRoles() throws Exception {
        coletar(token(SUB_GESTOR, "GESTOR", "metrics.read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
    }

    @Test
    void shouldRejectExpiredMetricsToken() throws Exception {
        Instant expiracao = Instant.now().minusSeconds(120);
        naoAutenticado(coletar(token(CHAVE, metricas()
                .issueTime(Date.from(expiracao.minusSeconds(300)))
                .expirationTime(Date.from(expiracao)).build())));
    }

    @Test
    void shouldRejectMetricsTokenFromAnotherIssuer() throws Exception {
        naoAutenticado(coletar(token(CHAVE, metricas().issuer("http://localhost/realms/outro").build())));
    }

    @Test
    void shouldRejectMetricsTokenWithWrongAudience() throws Exception {
        naoAutenticado(coletar(token(CHAVE, metricas().audience("outro-app").build())));
    }

    @Test
    void shouldRejectMetricsTokenSignedWithAnotherKey() throws Exception {
        naoAutenticado(coletar(token((RSAPrivateKey) novoPar().getPrivate(), metricas().build())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/usuarios/me", "/api/clientes", "/actuator/prometheus/outro"})
    void shouldRejectMetricsTokenOutsideScrapeEndpoint(String caminho) throws Exception {
        naoAutenticado(mvc.perform(get(caminho).header(HttpHeaders.AUTHORIZATION, token(CHAVE, metricas().build()))));
    }

    private ResultActions coletar(String token) throws Exception {
        return mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, token));
    }

    private static void naoAutenticado(ResultActions resposta) throws Exception {
        resposta.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
    }

    private static JWTClaimsSet.Builder metricas() {
        Instant agora = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("app-principal")
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(agora))
                .expirationTime(Date.from(agora.plusSeconds(300)))
                .claim("resource_access", Map.of("flowsla-metrics", Map.of("roles", List.of("metrics.read"))));
    }
}
