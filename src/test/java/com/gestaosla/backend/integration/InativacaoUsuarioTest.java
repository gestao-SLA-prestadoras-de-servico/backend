package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.identidade.api.NovoUsuarioKeycloak;
import com.gestaosla.backend.identidade.api.UsuarioKeycloak;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.Papel;
import com.jayway.jsonpath.JsonPath;

/** POST /api/usuarios/{id}/inativacao contra o Keycloak real. A porta dos chamados é simulada até a [chamado - 13]. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InativacaoUsuarioTest {

    private static final String SEGREDO = "s3gredo-teste";
    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");

    @Container
    private static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.4")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", SEGREDO)
            .withEnv("KEYCLOAK_GESTOR_PASSWORD", "gestor-teste")
            .withEnv("FRONTEND_URL", "http://localhost:5173")
            .withEnv("SWAGGER_URL", "http://localhost:8080")
            .withEnv("SMTP_HOST", "localhost")
            .withEnv("SMTP_PORT", "1025")
            .withFileSystemBind(new File("infra/keycloak/realm-flowsla.json").getAbsolutePath(),
                    "/opt/keycloak/data/import/realm-flowsla.json", BindMode.READ_ONLY)
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/flowsla/.well-known/openid-configuration").forStatusCode(200));

    @DynamicPropertySource
    static void apontarParaKeycloak(DynamicPropertyRegistry registro) {
        registro.add("app.keycloak.admin.server-url", () -> "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080));
        registro.add("app.keycloak.admin.client-secret", () -> SEGREDO);
        registro.add("app.keycloak.admin.read-timeout", () -> "5s");
    }

    record Conta(String id, UUID sub, String email) {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private KeycloakAdminClient keycloakAdmin;

    @MockitoBean
    private ChamadosDoTecnico chamados;

    @Test
    void shouldDisableAccountMarkInactiveAuditAndBlockValidToken() throws Exception {
        Conta tecnico = conta("TECNICO", null);
        String tokenAindaValido = token(tecnico.sub().toString(), "TECNICO");
        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, tokenAindaValido)).andExpect(status().isOk());

        inativar(tecnico.id(), null).andExpect(status().isNoContent());

        assertThat(habilitada(tecnico)).isFalse();
        assertThat(ativo(tecnico)).isFalse();
        assertThat(jdbc.queryForMap("""
                select ator_sub::text as ator, perfil, operacao, entidade_tipo, dados ->> 'perfil' as perfil_inativado
                from auditoria where entidade_id = ?::uuid""", tecnico.id()))
                .containsEntry("ator", SUB_GESTOR).containsEntry("perfil", "GESTOR").containsEntry("operacao", "INATIVACAO_USUARIO")
                .containsEntry("entidade_tipo", "USUARIO").containsEntry("perfil_inativado", "TECNICO");
        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, tokenAindaValido))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
    }

    @Test
    void shouldBeIdempotent() throws Exception {
        Conta tecnico = conta("TECNICO", null);

        inativar(tecnico.id(), null).andExpect(status().isNoContent());
        inativar(tecnico.id(), null).andExpect(status().isNoContent());

        verify(keycloakAdmin, times(1)).desabilitar(tecnico.sub());
        assertThat(auditorias(tecnico)).isEqualTo(1);
    }

    @Test
    void shouldRejectTechnicianWithOpenTicketsWithoutSubstituteBeforeKeycloak() throws Exception {
        Conta tecnico = conta("TECNICO", null);
        when(chamados.contarNaoFinalizados(tecnico.sub())).thenReturn(2L);

        inativar(tecnico.id(), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("SUBSTITUTO_OBRIGATORIO"));

        verify(keycloakAdmin, never()).desabilitar(any());
        assertThat(ativo(tecnico)).isTrue();
    }

    @Test
    void shouldRejectInvalidSubstitutes() throws Exception {
        Conta tecnico = conta("TECNICO", null);
        Conta inativo = conta("TECNICO", null);
        jdbc.update("update usuario set ativo = false where id = ?::uuid", inativo.id());
        Conta gestor = conta("GESTOR", null);

        for (String substituto : new String[] {inativo.id(), tecnico.id(), gestor.id(), UUID.randomUUID().toString()}) {
            inativar(tecnico.id(), substituto)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.codigo").value("SUBSTITUTO_INVALIDO"));
        }
        verify(keycloakAdmin, never()).desabilitar(any());
    }

    @Test
    void shouldRejectManagerInactivatingThemselves() throws Exception {
        String idGestor = jdbc.queryForObject("select id::text from usuario where sub = ?::uuid", String.class, SUB_GESTOR);

        inativar(idGestor, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("AUTOINATIVACAO_NAO_PERMITIDA"));
    }

    @Test
    void shouldTransferTicketsToSubstitute() throws Exception {
        Conta tecnico = conta("TECNICO", null);
        Conta substituto = conta("TECNICO", null);
        when(chamados.contarNaoFinalizados(tecnico.sub())).thenReturn(2L);

        inativar(tecnico.id(), substituto.id()).andExpect(status().isNoContent());

        verify(chamados).transferir(eq(tecnico.sub()), eq(substituto.sub()), eq(Ator.usuario(UUID.fromString(SUB_GESTOR), Papel.GESTOR)));
        assertThat(ativo(tecnico)).isFalse();
        assertThat(ativo(substituto)).isTrue();
    }

    @Test
    void shouldReenableAccountWhenLocalTransactionFails() throws Exception {
        Conta tecnico = conta("TECNICO", null);
        Conta substituto = conta("TECNICO", null);
        when(chamados.contarNaoFinalizados(tecnico.sub())).thenReturn(1L);
        doThrow(new IllegalStateException("falha simulada")).when(chamados).transferir(any(), any(), any());

        inativar(tecnico.id(), substituto.id()).andExpect(status().isInternalServerError());

        verify(keycloakAdmin).desabilitar(tecnico.sub());
        assertThat(habilitada(tecnico)).isTrue();
        assertThat(ativo(tecnico)).isTrue();
        assertThat(auditorias(tecnico)).isZero();
    }

    // Os dois passam na validação inicial e só então seguem: sem revalidar depois da trava, os dois técnicos ficariam
    // inativos, cada chamado com um substituto também inativo. A conta de quem perde a corrida volta a ficar habilitada.
    @Test
    void shouldNotInactivateTechniciansThatAreSubstitutesOfEachOtherConcurrently() throws Exception {
        Conta primeiro = conta("TECNICO", null);
        Conta segundo = conta("TECNICO", null);
        CountDownLatch ambosValidados = new CountDownLatch(2);
        doAnswer(chamada -> {
            ambosValidados.countDown();
            assertThat(ambosValidados.await(10, TimeUnit.SECONDS)).isTrue();
            return chamada.callRealMethod();
        }).when(keycloakAdmin).desabilitar(any());

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Integer> um = threads.submit(() -> inativar(primeiro.id(), segundo.id()).andReturn().getResponse().getStatus());
            Future<Integer> outro = threads.submit(() -> inativar(segundo.id(), primeiro.id()).andReturn().getResponse().getStatus());
            assertThat(List.of(um.get(), outro.get())).containsExactlyInAnyOrder(204, 422);
        }

        assertThat(List.of(ativo(primeiro), ativo(segundo))).containsExactlyInAnyOrder(true, false);
        assertThat(habilitada(primeiro)).isEqualTo(ativo(primeiro));
        assertThat(habilitada(segundo)).isEqualTo(ativo(segundo));
    }

    @Test
    void shouldReturn404ForUnknownUser() throws Exception {
        inativar(UUID.randomUUID().toString(), null).andExpect(status().isNotFound());
    }

    @Test
    void shouldInactivateCompanyUsersWhenClientIsInactivated() throws Exception {
        String empresa = JsonPath.read(mvc.perform(post("/api/clientes").header(HttpHeaders.AUTHORIZATION, GESTOR)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"razaoSocial":"Empresa","emailContato":"contato@empresa.local","endereco":{"logradouro":"Rua A",
                                 "numero":"1","bairro":"Centro","cidade":"Fortaleza","uf":"CE","cep":"60000000"}}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        Conta primeira = conta("CLIENTE", empresa);
        Conta segunda = conta("CLIENTE", empresa);

        mvc.perform(post("/api/clientes/" + empresa + "/inativacao").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isNoContent());

        for (Conta conta : new Conta[] {primeira, segunda}) {
            assertThat(habilitada(conta)).isFalse();
            assertThat(ativo(conta)).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade_id = ?::uuid and ator_sub is null",
                    Long.class, conta.id())).isEqualTo(1);
        }
    }

    private ResultActions inativar(String id, String substitutoId) throws Exception {
        var requisicao = post("/api/usuarios/" + id + "/inativacao").header(HttpHeaders.AUTHORIZATION, GESTOR);
        if (substitutoId != null) {
            requisicao.contentType(MediaType.APPLICATION_JSON).content("{\"substitutoId\":\"" + substitutoId + "\"}");
        }
        return mvc.perform(requisicao);
    }

    private Conta conta(String perfil, String clienteId) {
        String email = "u" + UUID.randomUUID().toString().substring(0, 8) + "@flowsla.local";
        UUID sub = keycloakAdmin.criarUsuario(new NovoUsuarioKeycloak(email, email, "Usuário Teste"));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id)
                values (?, now(), now(), ?, 'Usuário Teste', ?, ?, true, ?::uuid)""", id, sub, email, perfil, clienteId);
        return new Conta(id.toString(), sub, email);
    }

    private boolean habilitada(Conta conta) {
        return keycloakAdmin.buscarPorUsername(conta.email()).map(UsuarioKeycloak::enabled).orElseThrow();
    }

    private boolean ativo(Conta conta) {
        return jdbc.queryForObject("select ativo from usuario where id = ?::uuid", Boolean.class, conta.id());
    }

    private long auditorias(Conta conta) {
        return jdbc.queryForObject("select count(*) from auditoria where entidade_id = ?::uuid", Long.class, conta.id());
    }
}
