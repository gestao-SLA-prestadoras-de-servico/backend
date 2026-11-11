package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.identidade.api.UsuarioConsulta;
import com.jayway.jsonpath.JsonPath;

/** O banco é compartilhado entre as classes de teste: cada filtro usa empresa própria, ou confere só a presença do que inseriu. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConsultaUsuarioTest {

    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UsuarioConsulta consulta;

    @MockitoBean
    private ChamadosDoTecnico chamados;

    @Test
    void shouldReturnProfileAndCompanyOfCurrentClient() throws Exception {
        UUID empresa = empresa();
        UUID sub = usuario("CLIENTE", true, empresa);

        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token(sub.toString(), "CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(sub.toString()))
                .andExpect(jsonPath("$.nome").value("Usuário " + sub))
                .andExpect(jsonPath("$.perfil").value("CLIENTE"))
                .andExpect(jsonPath("$.clienteId").value(empresa.toString()));
    }

    @Test
    void shouldFilterUsersByCompanyAndActive() throws Exception {
        UUID empresa = empresa();
        UUID ativo = usuario("CLIENTE", true, empresa);
        UUID inativo = usuario("CLIENTE", false, empresa);
        usuario("CLIENTE", true, empresa());

        mvc.perform(get("/api/usuarios").param("clienteId", empresa.toString()).header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sub", containsInAnyOrder(ativo.toString(), inativo.toString())));
        mvc.perform(get("/api/usuarios").param("clienteId", empresa.toString()).param("ativo", "false")
                        .header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.content[*].sub", containsInAnyOrder(inativo.toString())))
                .andExpect(jsonPath("$.content[0].ativo").value(false));
    }

    @Test
    void shouldFilterUsersByProfile() throws Exception {
        UUID empresa = empresa();
        UUID cliente = usuario("CLIENTE", true, empresa);

        mvc.perform(get("/api/usuarios").param("perfil", "CLIENTE").param("clienteId", empresa.toString())
                        .header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.content[*].sub", containsInAnyOrder(cliente.toString())));
        mvc.perform(get("/api/usuarios").param("perfil", "TECNICO").param("clienteId", empresa.toString())
                        .header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void shouldPaginateUsers() throws Exception {
        UUID empresa = empresa();
        usuario("CLIENTE", true, empresa);
        usuario("CLIENTE", true, empresa);
        usuario("CLIENTE", true, empresa);

        mvc.perform(get("/api/usuarios").param("clienteId", empresa.toString()).param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void shouldReturn400ForUnknownProfile() throws Exception {
        mvc.perform(get("/api/usuarios").param("perfil", "ADMIN").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"));
    }

    @Test
    void shouldListActiveTechniciansWithLoadFromTicketsPort() throws Exception {
        UUID ativo = usuario("TECNICO", true, null);
        UUID inativo = usuario("TECNICO", false, null);
        when(chamados.contarNaoFinalizados(ativo)).thenReturn(3L);

        List<Map<String, Object>> ativos = tecnicos("true");
        assertThat(subs(ativos)).contains(ativo.toString()).doesNotContain(inativo.toString(), SUB_GESTOR);
        assertThat(ativos).filteredOn(t -> t.get("sub").equals(ativo.toString()))
                .singleElement().satisfies(t -> assertThat(t.get("chamadosNaoFinalizados")).isEqualTo(3));
        assertThat(subs(tecnicos(null))).contains(ativo.toString()).doesNotContain(inativo.toString());
        assertThat(subs(tecnicos("false"))).contains(inativo.toString()).doesNotContain(ativo.toString());
    }

    // 30 usuários com o mesmo nome, 7 por página: sem desempate pelo id, o banco pode repetir uns e pular outros.
    @Test
    void shouldNotRepeatOrSkipUsersWithSameNameAcrossPages() throws Exception {
        UUID empresa = empresa();
        List<String> inseridos = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            inseridos.add(usuarioComNome("Mesmo Nome", empresa).toString());
        }

        List<String> lidos = new ArrayList<>();
        for (int pagina = 0; pagina < 5; pagina++) {
            String json = mvc.perform(get("/api/usuarios").param("clienteId", empresa.toString()).param("size", "7")
                    .param("page", String.valueOf(pagina)).header(HttpHeaders.AUTHORIZATION, GESTOR)).andReturn().getResponse().getContentAsString();
            lidos.addAll(JsonPath.read(json, "$.content[*].sub"));
        }

        assertThat(lidos).containsExactlyInAnyOrderElementsOf(inseridos);
    }

    // Percorre todas as páginas: o banco é compartilhado e pode ter mais técnicos que uma página.
    private List<Map<String, Object>> tecnicos(String ativos) throws Exception {
        List<Map<String, Object>> todos = new ArrayList<>();
        for (int pagina = 0; ; pagina++) {
            var requisicao = get("/api/tecnicos").param("size", "100").param("page", String.valueOf(pagina))
                    .header(HttpHeaders.AUTHORIZATION, GESTOR);
            if (ativos != null) {
                requisicao.param("ativos", ativos);
            }
            String json = mvc.perform(requisicao).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            todos.addAll(JsonPath.read(json, "$.content"));
            if (pagina + 1 >= JsonPath.<Integer>read(json, "$.page.totalPages")) {
                return todos;
            }
        }
    }

    private static List<Object> subs(List<Map<String, Object>> tecnicos) {
        return tecnicos.stream().map(t -> t.get("sub")).toList();
    }

    @Test
    void shouldListOnlyActiveManagers() {
        UUID ativo = usuario("GESTOR", true, null);
        UUID inativo = usuario("GESTOR", false, null);
        UUID tecnico = usuario("TECNICO", true, null);

        assertThat(consulta.listarGestoresAtivos())
                .contains(UUID.fromString(SUB_GESTOR), ativo)
                .doesNotContain(inativo, tecnico);
    }

    private UUID usuario(String perfil, boolean ativo, UUID empresa) {
        UUID sub = UUID.randomUUID();
        inserir(sub, "Usuário " + sub, perfil, ativo, empresa);
        return sub;
    }

    private UUID usuarioComNome(String nome, UUID empresa) {
        UUID sub = UUID.randomUUID();
        inserir(sub, nome, "CLIENTE", true, empresa);
        return sub;
    }

    private void inserir(UUID sub, String nome, String perfil, boolean ativo, UUID empresa) {
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id)
                values (?, now(), now(), ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), sub, nome, sub + "@x.local", perfil, ativo, empresa);
    }

    private UUID empresa() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (id, created_at, updated_at, razao_social, email_contato, logradouro, numero, bairro,
                                     cidade, uf, cep, ativo, version)
                values (?, now(), now(), 'Empresa', 'e@x.local', 'Rua', '1', 'Centro', 'Fortaleza', 'CE', '60000000', true, 0)""", id);
        return id;
    }
}
