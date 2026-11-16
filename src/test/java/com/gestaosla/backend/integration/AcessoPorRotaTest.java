package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.TestcontainersConfiguration;

/**
 * As regras de papel por rota (RegrasAcesso de cada módulo) respondem antes da validação do corpo: quem não é gestor
 * recebe 403 mesmo com corpo vazio ou malformado, sem descobrir o formato do request (D1 do teste manual).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AcessoPorRotaTest {

    private static final String ID = UUID.randomUUID().toString();
    private static final List<Rota> SO_GESTOR = List.of(
            new Rota(HttpMethod.POST, "/api/usuarios"),
            new Rota(HttpMethod.GET, "/api/usuarios"),
            new Rota(HttpMethod.POST, "/api/usuarios/" + ID + "/inativacao"),
            new Rota(HttpMethod.GET, "/api/tecnicos"),
            new Rota(HttpMethod.POST, "/api/clientes"),
            new Rota(HttpMethod.PUT, "/api/clientes/" + ID),
            new Rota(HttpMethod.GET, "/api/clientes"),
            new Rota(HttpMethod.POST, "/api/clientes/" + ID + "/inativacao"),
            new Rota(HttpMethod.POST, "/api/contratos"),
            new Rota(HttpMethod.GET, "/api/contratos"),
            new Rota(HttpMethod.PUT, "/api/contratos/" + ID + "/politica"),
            new Rota(HttpMethod.POST, "/api/politicas"),
            new Rota(HttpMethod.GET, "/api/politicas"),
            new Rota(HttpMethod.POST, "/api/politicas/" + ID + "/versoes"),
            new Rota(HttpMethod.GET, "/api/politicas/" + ID + "/versoes"),
            new Rota(HttpMethod.PUT, "/api/chamados/" + ID + "/prioridade"),
            new Rota(HttpMethod.POST, "/api/chamados/" + ID + "/posicionamento"),
            new Rota(HttpMethod.POST, "/api/chamados/" + ID + "/atribuicao"));

    record Rota(HttpMethod metodo, String caminho) {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"TECNICO", "CLIENTE"})
    void shouldDenyManagerRoutesBeforeValidatingBody(String papel) throws Exception {
        String tokenSemPapel = token(usuario(papel), papel);
        for (Rota rota : SO_GESTOR) {
            for (String corpo : new String[] {"{}", "{"}) {
                mvc.perform(request(rota.metodo(), rota.caminho()).header(HttpHeaders.AUTHORIZATION, tokenSemPapel)
                                .contentType(MediaType.APPLICATION_JSON).content(corpo))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
            }
        }
    }

    @Test
    void shouldStillValidateBodyForManager() throws Exception {
        mvc.perform(request(HttpMethod.POST, "/api/usuarios").header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"));
    }

    // As consultas de chamado aceitam os três perfis; quem não tem nenhum deles recebe 403.
    @Test
    void shouldDenyChamadoQueriesToTokenWithoutKnownRole() throws Exception {
        for (String caminho : List.of("/api/chamados", "/api/chamados/contagem", "/api/chamados/" + ID,
                "/api/chamados/" + ID + "/historico", "/api/chamados/" + ID + "/sla")) {
            mvc.perform(get(caminho).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR))).andExpect(status().isForbidden());
        }
    }

    // Abrir chamado é só do cliente: gestor e técnico recebem 403 antes de o corpo ser validado.
    @ParameterizedTest
    @ValueSource(strings = {"GESTOR", "TECNICO"})
    void shouldDenyOpeningToNonClientsBeforeValidatingBody(String papel) throws Exception {
        String quem = papel.equals("GESTOR") ? token(SUB_GESTOR, "GESTOR") : token(usuario(papel), papel);
        for (String corpo : new String[] {"{}", "{"}) {
            mvc.perform(request(HttpMethod.POST, "/api/chamados").header(HttpHeaders.AUTHORIZATION, quem)
                            .contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
        }
    }

    // A regra do identidade é só para o caminho exato: /me continua aberto a qualquer perfil autenticado.
    @Test
    void shouldKeepCurrentUserOpenToAnyProfile() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token(usuario("TECNICO"), "TECNICO")))
                .andExpect(status().isOk());
    }

    private String usuario(String papel) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo)
                values (?, now(), now(), ?, 'Teste', ?, ?, true)""", UUID.randomUUID(), sub, sub + "@x.local", papel);
        return sub.toString();
    }
}
