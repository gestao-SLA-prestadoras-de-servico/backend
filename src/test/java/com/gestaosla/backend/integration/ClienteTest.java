package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.cadastro.api.ClienteConsulta;
import com.gestaosla.backend.cadastro.api.ClienteInativado;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class ClienteTest {

    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClienteConsulta consulta;

    @Autowired
    private ApplicationEvents eventos;

    @Test
    void shouldCreateActiveClient() throws Exception {
        criar("Refrigeração Norte Ltda", "contato@norte.com.br")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/clientes/")))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.endereco.uf").value("CE"));
    }

    @Test
    void shouldReturn400ForInvalidContactEmail() throws Exception {
        criar("Cliente", "email-sem-arroba")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"))
                .andExpect(jsonPath("$.erros[0].campo").value("emailContato"));
    }

    @Test
    void shouldAllowOnlyManager() throws Exception {
        String tecnico = token(usuarioTecnico(), "TECNICO");

        mvc.perform(post("/api/clientes").header(HttpHeaders.AUTHORIZATION, tecnico)
                        .contentType(MediaType.APPLICATION_JSON).content(json("X", "x@x.com")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/clientes").header(HttpHeaders.AUTHORIZATION, tecnico)).andExpect(status().isForbidden());
    }

    @Test
    void shouldListPaginatedFilteringByNameAndActive() throws Exception {
        String prefixo = "Lista " + UUID.randomUUID().toString().substring(0, 6);
        criar(prefixo + " A", "a@x.com");
        criar(prefixo + " B", "b@x.com");
        String c = id(criar(prefixo + " C", "c@x.com"));
        inativar(c).andExpect(status().isNoContent());

        mvc.perform(get("/api/clientes").param("razaoSocial", prefixo.toUpperCase()).param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].razaoSocial").value(prefixo + " A"))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        mvc.perform(get("/api/clientes").param("razaoSocial", prefixo).param("ativo", "false")
                        .header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(c))
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    void shouldEditWithCurrentVersionAndRejectStaleVersion() throws Exception {
        String id = id(criar("Antes Ltda", "antes@x.com"));

        editar(id, 0, "Depois Ltda").andExpect(status().isOk())
                .andExpect(jsonPath("$.razaoSocial").value("Depois Ltda"))
                .andExpect(jsonPath("$.version").value(1));
        editar(id, 0, "Sobrescrita Ltda").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONFLITO_VERSAO"));
    }

    // RN-07: inativo continua listado (histórico e relatórios) e a API do módulo responde por ele.
    @Test
    void shouldInactivateKeepingClientAndPublishEventOnce() throws Exception {
        String id = id(criar("Inativado Ltda", "fim@x.com"));

        inativar(id).andExpect(status().isNoContent());
        inativar(id).andExpect(status().isNoContent());

        assertThat(consulta.estaAtivo(UUID.fromString(id))).isFalse();
        assertThat(consulta.emailContato(UUID.fromString(id))).isEqualTo("fim@x.com");
        assertThat(eventos.stream(ClienteInativado.class).filter(e -> e.clienteId().toString().equals(id))).hasSize(1);
        mvc.perform(get("/api/clientes").param("razaoSocial", "Inativado Ltda").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.content[0].ativo").value(false));
    }

    @Test
    void shouldReturn404ForUnknownClient() throws Exception {
        inativar(UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectUserLinkedToUnknownClient() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id)
                values (?, now(), now(), ?, 'Cliente', ?, 'CLIENTE', true, ?)""",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() + "@x.com", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("usuario_cliente_fk");
    }

    @Test
    void shouldReturn400ForUnknownSortProperty() throws Exception {
        mvc.perform(get("/api/clientes").param("sort", "senha").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isBadRequest());
    }

    private ResultActions criar(String razaoSocial, String email) throws Exception {
        return mvc.perform(post("/api/clientes").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content(json(razaoSocial, email)));
    }

    private ResultActions editar(String id, long version, String razaoSocial) throws Exception {
        String corpo = json(razaoSocial, "depois@x.com").replaceFirst("\\{", "{\"version\":" + version + ",");
        return mvc.perform(put("/api/clientes/" + id).header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private ResultActions inativar(String id) throws Exception {
        return mvc.perform(post("/api/clientes/" + id + "/inativacao").header(HttpHeaders.AUTHORIZATION, GESTOR));
    }

    private static String id(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String json(String razaoSocial, String email) {
        return """
                {"razaoSocial":"%s","emailContato":"%s","endereco":{"logradouro":"Av. Santos Dumont","numero":"1500",
                 "bairro":"Aldeota","cidade":"Fortaleza","uf":"CE","cep":"60150160"}}""".formatted(razaoSocial, email);
    }

    private String usuarioTecnico() {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo)
                values (?, now(), now(), ?, 'Técnico', ?, 'TECNICO', true)""", UUID.randomUUID(), sub, sub + "@x.com");
        return sub.toString();
    }
}
