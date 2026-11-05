package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.identidade.api.IdpIndisponivelException;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.identidade.api.NovoUsuarioKeycloak;
import com.jayway.jsonpath.JsonPath;

/** POST /api/usuarios contra o Keycloak real (realm-flowsla.json) e o Mailpit. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CadastroUsuarioTest {

    private static final String SEGREDO = "s3gredo-teste";
    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");
    private static final Network rede = Network.newNetwork();

    @Container
    private static final GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.27")
            .withNetwork(rede).withNetworkAliases("mailpit").withExposedPorts(8025);

    @Container
    private static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.4")
            .withNetwork(rede)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", SEGREDO)
            .withEnv("KEYCLOAK_GESTOR_PASSWORD", "gestor-teste")
            .withEnv("FRONTEND_URL", "http://localhost:5173")
            .withEnv("SWAGGER_URL", "http://localhost:8080")
            .withEnv("SMTP_HOST", "mailpit")
            .withEnv("SMTP_PORT", "1025")
            .withFileSystemBind(new File("infra/keycloak/realm-flowsla.json").getAbsolutePath(),
                    "/opt/keycloak/data/import/realm-flowsla.json", BindMode.READ_ONLY)
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/flowsla/.well-known/openid-configuration").forStatusCode(200))
            .dependsOn(mailpit);

    @DynamicPropertySource
    static void apontarParaKeycloak(DynamicPropertyRegistry registro) {
        registro.add("app.keycloak.admin.server-url", () -> "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080));
        registro.add("app.keycloak.admin.client-secret", () -> SEGREDO);
        registro.add("app.keycloak.admin.read-timeout", () -> "5s");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private AuditoriaService auditoria;

    @MockitoSpyBean
    private KeycloakAdminClient keycloakAdmin;

    @Test
    void shouldCreateTechnicianWithRoleLocalRecordAuditAndPasswordEmail() throws Exception {
        String email = unico() + "@flowsla.local";

        ResultActions resposta = cadastrar("Ana Técnica", email.toUpperCase(), "TECNICO", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.perfil").value("TECNICO"))
                .andExpect(jsonPath("$.emailDefinicaoSenhaEnviado").value(true));
        String sub = campo(resposta, "$.sub");
        String id = campo(resposta, "$.id");

        assertThat(keycloakAdmin.buscarPorUsername(email)).hasValueSatisfying(conta -> assertThat(conta.id().toString()).isEqualTo(sub));
        assertThat(admin("/admin/realms/flowsla/users/" + sub + "/role-mappings/realm")).contains("\"name\":\"TECNICO\"");
        assertThat(jdbc.queryForMap("select nome, perfil, ativo, cliente_id from usuario where sub = ?::uuid", sub))
                .containsEntry("nome", "Ana Técnica").containsEntry("perfil", "TECNICO").containsEntry("ativo", true)
                .containsEntry("cliente_id", null);
        assertThat(jdbc.queryForMap("""
                select ator_sub::text as ator, perfil, operacao, entidade_tipo, dados ->> 'perfil' as perfil_cadastrado,
                       (dados -> 'email') is not null as tem_email from auditoria where entidade_id = ?::uuid""", id))
                .containsEntry("ator", SUB_GESTOR).containsEntry("perfil", "GESTOR").containsEntry("operacao", "CADASTRO_USUARIO")
                .containsEntry("entidade_tipo", "USUARIO").containsEntry("perfil_cadastrado", "TECNICO").containsEntry("tem_email", false);
        assertThat(caixaDeEntrada(email)).contains("\"messages_count\":1");
    }

    @Test
    void shouldLinkClientUserToActiveCompanyAndExposeItAsCurrentUser() throws Exception {
        String empresa = criarCliente();
        String email = unico() + "@cliente.local";

        String sub = campo(cadastrar("Carlos Cliente", email, "CLIENTE", empresa).andExpect(status().isCreated())
                .andExpect(jsonPath("$.clienteId").value(empresa)), "$.sub");

        // UsuarioCorrente.empresaClienteId vem do registro local (o token não traz a empresa).
        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token(sub, "CLIENTE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clienteId").value(empresa));
    }

    @Test
    void shouldRejectInconsistentCompanyWith422BeforeCallingKeycloak() throws Exception {
        String inativa = criarCliente();
        mvc.perform(post("/api/clientes/" + inativa + "/inativacao").header(HttpHeaders.AUTHORIZATION, GESTOR));

        Map<String, String[]> casos = Map.of(
                "CLIENTE_OBRIGATORIO", new String[] {"CLIENTE", null},
                "CLIENTE_INEXISTENTE", new String[] {"CLIENTE", UUID.randomUUID().toString()},
                "CLIENTE_INATIVO", new String[] {"CLIENTE", inativa},
                "CLIENTE_NAO_PERMITIDO", new String[] {"TECNICO", criarCliente()});
        for (var caso : casos.entrySet()) {
            String email = unico() + "@x.local";
            cadastrar("Fulano", email, caso.getValue()[0], caso.getValue()[1])
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.codigo").value(caso.getKey()));
            assertThat(keycloakAdmin.buscarPorUsername(email)).as(caso.getKey()).isEmpty();
        }
    }

    @Test
    void shouldReturn409ForEmailAlreadyRegisteredIgnoringCase() throws Exception {
        String email = unico() + "@flowsla.local";
        cadastrar("Primeiro", email, "TECNICO", null).andExpect(status().isCreated());

        cadastrar("Segundo", email.toUpperCase(), "GESTOR", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("USUARIO_JA_EXISTE"));
    }

    // RN-136: a gravação local falha depois da conta criada → a conta some do Keycloak.
    @Test
    void shouldRemoveKeycloakAccountWhenLocalWriteFails() throws Exception {
        String email = unico() + "@flowsla.local";
        // Stub no objeto por trás do proxy transacional (MANDATORY), senão o próprio stub exige transação.
        doThrow(new DataAccessResourceFailureException("banco fora do ar"))
                .when(AopTestUtils.<AuditoriaService>getUltimateTargetObject(auditoria))
                .registrar(any(), any(), any(), any(), any(), any());

        cadastrar("Falha Local", email, "TECNICO", null).andExpect(status().isInternalServerError());

        assertThat(keycloakAdmin.buscarPorUsername(email)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, email)).isZero();
    }

    @Test
    void shouldKeepRegistrationWhenPasswordEmailFailsAfterCommit() throws Exception {
        String email = unico() + "@flowsla.local";
        doThrow(new IdpIndisponivelException("Keycloak fora do ar", null)).when(keycloakAdmin).enviarDefinicaoSenha(any());

        cadastrar("Sem E-mail", email, "TECNICO", null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.emailDefinicaoSenhaEnviado").value(false));

        assertThat(keycloakAdmin.buscarPorUsername(email)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, email)).isOne();
    }

    // Sobra de cadastro anterior (conta sem registro local) é reaproveitada pelo adaptador.
    @Test
    void shouldReuseOrphanKeycloakAccount() throws Exception {
        String email = unico() + "@flowsla.local";
        UUID orfa = keycloakAdmin.criarUsuario(new NovoUsuarioKeycloak(email, email, "Conta Órfã"));

        cadastrar("Conta Recuperada", email, "TECNICO", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sub").value(orfa.toString()));
    }

    @Test
    void shouldAllowOnlyManager() throws Exception {
        UUID tecnico = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo)
                values (?, now(), now(), ?, 'Técnico', ?, 'TECNICO', true)""", UUID.randomUUID(), tecnico, tecnico + "@x.local");

        mvc.perform(post("/api/usuarios").header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("X", "x@x.local", "TECNICO", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldHaveNoPasswordColumn() {
        List<String> colunas = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'usuario'", String.class);
        assertThat(colunas).noneMatch(c -> c.contains("senha") || c.contains("password"));
    }

    private ResultActions cadastrar(String nome, String email, String perfil, String clienteId) throws Exception {
        return mvc.perform(post("/api/usuarios").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content(json(nome, email, perfil, clienteId)));
    }

    private static String json(String nome, String email, String perfil, String clienteId) {
        return "{\"nome\":\"%s\",\"email\":\"%s\",\"perfil\":\"%s\"%s}".formatted(nome, email, perfil,
                clienteId == null ? "" : ",\"clienteId\":\"" + clienteId + "\"");
    }

    private String criarCliente() throws Exception {
        return campo(mvc.perform(post("/api/clientes").header(HttpHeaders.AUTHORIZATION, GESTOR).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"razaoSocial":"Empresa %s","emailContato":"contato@empresa.local","endereco":{"logradouro":"Rua A",
                         "numero":"1","bairro":"Centro","cidade":"Fortaleza","uf":"CE","cep":"60000000"}}""".formatted(unico())))
                .andExpect(status().isCreated()), "$.id");
    }

    private static String campo(ResultActions resposta, String caminho) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), caminho);
    }

    private static String unico() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String caixaDeEntrada(String email) {
        return RestClient.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025))
                .get().uri("/api/v1/search?query=to:{email}", email).retrieve().body(String.class);
    }

    private static String admin(String caminho) {
        RestClient http = RestClient.create("http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080));
        String tokenAdmin = http.post().uri("/realms/flowsla/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=app-principal-admin&client_secret=" + SEGREDO)
                .retrieve().body(String.class).replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        return http.get().uri(caminho).header("Authorization", "Bearer " + tokenAdmin).retrieve().body(String.class);
    }
}
