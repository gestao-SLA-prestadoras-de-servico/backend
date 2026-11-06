package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.Papel;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class AuditoriaTest {

    private static final UUID GESTOR = UUID.fromString("0199a000-0000-7000-8000-000000000001");

    @Autowired
    private AuditoriaService auditoria;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Test
    void shouldFailOutsideTransaction() {
        assertThatThrownBy(() -> registrar(UUID.randomUUID())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void shouldStoreWhoWhatWhenAndWhichEntityWithinCallerTransaction() {
        Instant agora = Instant.parse("2026-10-29T15:00:00Z");
        relogio.definir(agora);
        UUID entidade = UUID.randomUUID();
        UUID chamado = UUID.randomUUID();

        transacao.executeWithoutResult(t -> auditoria.registrar(Ator.usuario(GESTOR, Papel.GESTOR),
                OperacaoAuditada.REABERTURA, EntidadeAuditada.CHAMADO, entidade, chamado,
                Map.of("de", "RESOLVIDO", "para", "ATRIBUIDO", "justificativa", "Voltou a falhar")));

        Map<String, Object> linha = jdbc.queryForMap("""
                select ocorrido_em, ator_tipo, ator_sub, perfil, operacao, entidade_tipo, chamado_id,
                       dados ->> 'para' as para, dados ->> 'justificativa' as justificativa
                from auditoria where entidade_id = ?""", entidade);
        assertThat(((java.sql.Timestamp) linha.get("ocorrido_em")).toInstant()).isEqualTo(agora);
        assertThat(linha).containsEntry("ator_tipo", "USUARIO").containsEntry("ator_sub", GESTOR)
                .containsEntry("perfil", "GESTOR").containsEntry("operacao", "REABERTURA")
                .containsEntry("entidade_tipo", "CHAMADO").containsEntry("chamado_id", chamado)
                .containsEntry("para", "ATRIBUIDO").containsEntry("justificativa", "Voltou a falhar");
    }

    @Test
    void shouldStoreSystemActorWithoutSubOrProfile() {
        UUID entidade = UUID.randomUUID();

        transacao.executeWithoutResult(t -> auditoria.registrar(Ator.SISTEMA, OperacaoAuditada.VALIDACAO_AUTOMATICA,
                EntidadeAuditada.CHAMADO, entidade, entidade, null));

        assertThat(jdbc.queryForMap("select ator_tipo, ator_sub, perfil, dados::text as dados from auditoria where entidade_id = ?", entidade))
                .containsEntry("ator_tipo", "SISTEMA").containsEntry("ator_sub", null).containsEntry("perfil", null)
                .containsEntry("dados", "{}");
    }

    @Test
    void shouldLeaveNoRecordWhenBusinessOperationRollsBack() {
        UUID entidade = UUID.randomUUID();

        assertThatThrownBy(() -> transacao.executeWithoutResult(t -> {
            registrar(entidade);
            throw new IllegalStateException("falha na operação de negócio");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(contar(entidade)).isZero();
    }

    @Test
    void shouldRejectUpdateThroughSql() {
        UUID entidade = UUID.randomUUID();
        transacao.executeWithoutResult(t -> registrar(entidade));

        assertThatThrownBy(() -> jdbc.update("update auditoria set operacao = 'CANCELAMENTO' where entidade_id = ?", entidade))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("não pode ser alterado");
    }

    @Test
    void shouldRejectDeleteWithinFiveYearsAndAllowAfter() {
        UUID recente = inserirDireto("now() - interval '4 years 11 months'");
        UUID vencido = inserirDireto("now() - interval '5 years 1 day'");

        assertThatThrownBy(() -> jdbc.update("delete from auditoria where entidade_id = ?", recente))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("período de retenção");
        assertThat(jdbc.update("delete from auditoria where entidade_id = ?", vencido)).isEqualTo(1);
    }

    @Test
    void shouldRejectTruncate() {
        assertThatThrownBy(() -> jdbc.execute("truncate auditoria"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("não pode ser esvaziada");
    }

    @Test
    void shouldRejectUserActorWithoutSubInDatabase() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into auditoria (id, ocorrido_em, ator_tipo, operacao, entidade_tipo, entidade_id)
                values (?, now(), 'USUARIO', 'CADASTRO_USUARIO', 'USUARIO', ?)""", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("auditoria_ator_ck");
    }

    private void registrar(UUID entidade) {
        auditoria.registrar(Ator.usuario(GESTOR, Papel.GESTOR), OperacaoAuditada.CADASTRO_USUARIO,
                EntidadeAuditada.USUARIO, entidade, null, Map.of());
    }

    private UUID inserirDireto(String ocorridoEm) {
        UUID entidade = UUID.randomUUID();
        jdbc.update("insert into auditoria (id, ocorrido_em, ator_tipo, operacao, entidade_tipo, entidade_id) values (?, "
                + ocorridoEm + ", 'SISTEMA', 'EXPURGO_EVIDENCIA', 'EVIDENCIA', ?)", UUID.randomUUID(), entidade);
        return entidade;
    }

    private int contar(UUID entidade) {
        return jdbc.queryForObject("select count(*) from auditoria where entidade_id = ?", Integer.class, entidade);
    }
}
