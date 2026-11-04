package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.gestaosla.backend.TestcontainersConfiguration;

/** Schema da tabela usuario depois da V3. O mapeamento da entidade é conferido pelo ddl-auto=validate ao subir. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UsuarioTabelaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldFillInitialGestorWithKeycloakEmailAndNoClient() {
        Map<String, Object> gestor = jdbc.queryForMap("select email, cliente_id, version from usuario where sub = ?::uuid", SUB_GESTOR);

        assertThat(gestor).containsEntry("email", "gestor@flowsla.local").containsEntry("version", 0L);
        assertThat(gestor.get("cliente_id")).isNull();
    }

    @Test
    void shouldRejectSameEmailIgnoringCase() {
        inserir("ana.lima@flowsla.local");

        assertThatThrownBy(() -> inserir("Ana.Lima@FlowSLA.local")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldRequireEmail() {
        assertThatThrownBy(() -> inserir(null)).hasMessageContaining("email");
    }

    private void inserir(String email) {
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo)
                values (?, now(), now(), ?, 'Teste', ?, 'TECNICO', true)""", UUID.randomUUID(), UUID.randomUUID(), email);
    }
}
