package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.identidade.api.NovoUsuarioKeycloak;
import com.gestaosla.backend.identidade.api.Papel;
import com.gestaosla.backend.identidade.api.UsuarioJaExisteException;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

/**
 * Admin API contra o Keycloak real, com o mesmo realm-flowsla.json do compose e o Mailpit como SMTP.
 * Cada teste cria a própria conta, sem depender da ordem.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class KeycloakAdminClientIntegrationTest {

    private static final String SEGREDO = "s3gredo-teste";
    private static final Network rede = Network.newNetwork();

    @Container
    private static final GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.27")
            .withNetwork(rede)
            .withNetworkAliases("mailpit")
            .withExposedPorts(8025);

    @Container
    private static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.4")
            .withNetwork(rede)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", SEGREDO)
            .withEnv("KEYCLOAK_GESTOR_PASSWORD", "gestor-teste")
            .withEnv("FRONTEND_URL", "http://localhost:5173")
            .withEnv("SMTP_HOST", "mailpit")
            .withEnv("SMTP_PORT", "1025")
            .withFileSystemBind(new File("infra/keycloak/realm-flowsla.json").getAbsolutePath(),
                    "/opt/keycloak/data/import/realm-flowsla.json", BindMode.READ_ONLY)
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/flowsla/.well-known/openid-configuration").forStatusCode(200))
            .dependsOn(mailpit);

    @DynamicPropertySource
    static void apontarParaKeycloak(DynamicPropertyRegistry registro) {
        registro.add("app.keycloak.admin.server-url", KeycloakAdminClientIntegrationTest::urlKeycloak);
        registro.add("app.keycloak.admin.client-secret", () -> SEGREDO);
        registro.add("app.keycloak.admin.read-timeout", () -> "5s");
    }

    @Autowired
    private KeycloakAdminClient keycloakAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldCreateUserAndFindItByUsername() {
        String username = unico();
        UUID sub = keycloakAdmin.criarUsuario(novo(username));

        assertThat(keycloakAdmin.buscarPorUsername(username)).hasValueSatisfying(usuario -> {
            assertThat(usuario.id()).isEqualTo(sub);
            assertThat(usuario.email()).isEqualTo(username + "@flowsla.local");
            assertThat(usuario.enabled()).isTrue();
        });
    }

    // Sobra de cadastro que não terminou (conta sem registro local): reaproveitada com os dados novos.
    @Test
    void shouldReuseOrphanAccountWithSameUsername() {
        String username = unico();
        UUID orfa = keycloakAdmin.criarUsuario(novo(username));
        keycloakAdmin.desabilitar(orfa);

        UUID sub = keycloakAdmin.criarUsuario(new NovoUsuarioKeycloak(username, username + "-novo@flowsla.local", "Outro", "Nome"));

        assertThat(sub).isEqualTo(orfa);
        assertThat(keycloakAdmin.buscarPorUsername(username)).hasValueSatisfying(u -> {
            assertThat(u.email()).isEqualTo(username + "-novo@flowsla.local");
            assertThat(u.firstName()).isEqualTo("Outro");
            assertThat(u.enabled()).isTrue();
        });
    }

    @Test
    void shouldThrowConflictWhenUsernameBelongsToRegisteredUser() {
        String username = unico();
        UUID sub = keycloakAdmin.criarUsuario(novo(username));
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo)
                values (?, now(), now(), ?, 'Teste', ?, 'TECNICO', true)""", UUID.randomUUID(), sub, username + "@flowsla.local");

        assertThatThrownBy(() -> keycloakAdmin.criarUsuario(novo(username))).isInstanceOf(UsuarioJaExisteException.class);
    }

    @Test
    void shouldThrowConflictForEmailOfAnotherAccount() {
        String username = unico();
        keycloakAdmin.criarUsuario(novo(username));

        assertThatThrownBy(() -> keycloakAdmin.criarUsuario(new NovoUsuarioKeycloak(unico(), username + "@flowsla.local", "A", "B")))
                .isInstanceOf(UsuarioJaExisteException.class);
    }

    @Test
    void shouldAssignRealmRoleIdempotently() {
        UUID sub = keycloakAdmin.criarUsuario(novo(unico()));

        keycloakAdmin.atribuirPapel(sub, Papel.TECNICO);
        keycloakAdmin.atribuirPapel(sub, Papel.TECNICO);

        assertThat(admin("/admin/realms/flowsla/users/" + sub + "/role-mappings/realm")).contains("\"name\":\"TECNICO\"");
    }

    @Test
    void shouldSendPasswordEmailThroughKeycloakSmtp() {
        String username = unico();
        UUID sub = keycloakAdmin.criarUsuario(novo(username));

        keycloakAdmin.enviarDefinicaoSenha(sub);

        String caixa = RestClient.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025))
                .get().uri("/api/v1/search?query=to:{email}", username + "@flowsla.local").retrieve().body(String.class);
        assertThat(caixa).contains("\"messages_count\":1");
    }

    @Test
    void shouldDisableAndReenableKeepingAccountData() {
        String username = unico();
        UUID sub = keycloakAdmin.criarUsuario(novo(username));

        keycloakAdmin.desabilitar(sub);
        keycloakAdmin.desabilitar(sub);
        assertThat(keycloakAdmin.buscarPorUsername(username)).hasValueSatisfying(u -> {
            assertThat(u.enabled()).isFalse();
            assertThat(u.firstName()).isEqualTo("Nome");
            assertThat(u.email()).isEqualTo(username + "@flowsla.local");
        });

        keycloakAdmin.reabilitar(sub);
        assertThat(keycloakAdmin.buscarPorUsername(username)).hasValueSatisfying(u -> assertThat(u.enabled()).isTrue());
    }

    @Test
    void shouldRemoveIdempotently() {
        String username = unico();
        UUID sub = keycloakAdmin.criarUsuario(novo(username));

        keycloakAdmin.remover(sub);

        assertThat(keycloakAdmin.buscarPorUsername(username)).isEmpty();
        assertThatCode(() -> keycloakAdmin.remover(sub)).doesNotThrowAnyException();
    }

    @Test
    void shouldReturn404ForUnknownAccount() {
        assertThatThrownBy(() -> keycloakAdmin.desabilitar(UUID.randomUUID())).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    private static NovoUsuarioKeycloak novo(String username) {
        return new NovoUsuarioKeycloak(username, username + "@flowsla.local", "Nome", "Sobrenome");
    }

    private static String unico() {
        return "teste-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String urlKeycloak() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    // Consulta direta à Admin API, com a mesma conta de serviço, para conferir o efeito no Keycloak.
    private static String admin(String caminho) {
        RestClient http = RestClient.create(urlKeycloak());
        String token = http.post().uri("/realms/flowsla/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=app-principal-admin&client_secret=" + SEGREDO)
                .retrieve().body(String.class)
                .replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        return http.get().uri(caminho).header("Authorization", "Bearer " + token).retrieve().body(String.class);
    }
}
