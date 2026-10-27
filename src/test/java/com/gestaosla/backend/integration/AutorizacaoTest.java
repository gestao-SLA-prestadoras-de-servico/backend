package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.CHAVE;
import static com.gestaosla.backend.TokenTeste.ISSUER;
import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.novoPar;
import static com.gestaosla.backend.TokenTeste.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.interfaces.RSAPrivateKey;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.identidade.api.Papel;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AutorizacaoTest.EndpointsPorPapel.class})
class AutorizacaoTest {

    private static final String FRONTEND = "http://localhost:5173";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        naoAutenticado(mvc.perform(get("/api/usuarios/me")));
    }

    @Test
    void shouldReturn401ForTokenFromAnotherIssuer() throws Exception {
        naoAutenticado(comToken(token(CHAVE, "http://localhost/realms/outro", "app-principal", SUB_GESTOR, "GESTOR")));
    }

    @Test
    void shouldReturn401ForTokenWithWrongAudience() throws Exception {
        String semAppPrincipal = token(CHAVE, ISSUER, "outro-app", SUB_GESTOR, "GESTOR");
        naoAutenticado(comToken(semAppPrincipal));
    }

    @Test
    void shouldReturn401ForTokenSignedWithAnotherKey() throws Exception {
        naoAutenticado(comToken(token(outraChave(), ISSUER, "app-principal", SUB_GESTOR, "GESTOR")));
    }

    @Test
    void shouldReturn401ForInactiveUserWithValidToken() throws Exception {
        String sub = usuario(Papel.TECNICO, false);
        naoAutenticado(comToken(token(sub, "TECNICO")));
    }

    @Test
    void shouldReturn401ForSubWithoutLocalRecord() throws Exception {
        naoAutenticado(comToken(token(UUID.randomUUID().toString(), "GESTOR")));
    }

    // Cada perfil acessa o próprio endpoint e recebe 403 nos dos outros dois.
    @ParameterizedTest
    @EnumSource(Papel.class)
    void shouldAllowOnlyTheTokenRole(Papel papel) throws Exception {
        String token = token(usuario(papel, true), papel.name());
        for (Papel endpoint : Papel.values()) {
            ResultActions resposta = mvc.perform(get("/teste/" + endpoint).header(HttpHeaders.AUTHORIZATION, token));
            if (endpoint == papel) {
                resposta.andExpect(status().isOk()).andExpect(content().string(papel.name()));
            } else {
                resposta.andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
            }
        }
    }

    // RN-130: a cópia local diz CLIENTE, o token diz GESTOR; vale o token.
    @Test
    void shouldAuthorizeByTokenRoleNotLocalProfile() throws Exception {
        String token = token(usuario(Papel.CLIENTE, true), "GESTOR");

        mvc.perform(get("/teste/GESTOR").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        mvc.perform(get("/teste/CLIENTE").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
    }

    @Test
    void shouldIgnoreKeycloakDefaultRoles() throws Exception {
        String token = token(usuario(Papel.GESTOR, true), "offline_access", "default-roles-flowsla");

        mvc.perform(get("/teste/GESTOR").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowCorsOnlyFromFrontendOrigin() throws Exception {
        mvc.perform(preflight(FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
        mvc.perform(preflight("http://malicioso.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private ResultActions comToken(String token) throws Exception {
        return mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token));
    }

    private static void naoAutenticado(ResultActions resposta) throws Exception {
        resposta.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
    }

    private static RequestBuilder preflight(String origem) {
        return options("/api/usuarios/me")
                .header(HttpHeaders.ORIGIN, origem)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization");
    }

    private String usuario(Papel perfilLocal, boolean ativo) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, perfil, ativo)
                values (?, now(), now(), ?, 'Teste', ?, ?)""", UUID.randomUUID(), sub, perfilLocal.name(), ativo);
        return sub.toString();
    }

    private static RSAPrivateKey outraChave() {
        return (RSAPrivateKey) novoPar().getPrivate();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class EndpointsPorPapel {

        @GetMapping("/teste/CLIENTE")
        @PreAuthorize("hasRole('CLIENTE')")
        String cliente(@AuthenticationPrincipal UsuarioCorrente u) {
            return u.papel().name();
        }

        @GetMapping("/teste/TECNICO")
        @PreAuthorize("hasRole('TECNICO')")
        String tecnico(@AuthenticationPrincipal UsuarioCorrente u) {
            return u.papel().name();
        }

        @GetMapping("/teste/GESTOR")
        @PreAuthorize("hasRole('GESTOR')")
        String gestor(@AuthenticationPrincipal UsuarioCorrente u) {
            return u.papel().name();
        }
    }
}
