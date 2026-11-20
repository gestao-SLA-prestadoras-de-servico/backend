package com.gestaosla.backend.integration;

import static com.gestaosla.backend.retencao.internal.RotinaRetencaoTestHelper.executar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.outbox.api.LimpezaOutbox;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ExpurgoAuditoriaTest {

    @Autowired
    private ApplicationContext contexto;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RelogioMutavel relogio;
    @Autowired
    private MeterRegistry metricas;
    @MockitoBean
    private LimpezaOutbox outbox;

    @Test
    void shouldPurgeExpiredAuditRecordsInBatchesEvenWhenOutboxFails() {
        relogio.definir(Instant.parse("2025-01-02T12:00:00Z"));
        UUID entidade = UUID.randomUUID();
        inserir(entidade, "2020-01-01T12:00:00Z", 101);
        inserir(entidade, "2020-01-02T12:00:00Z", 1);
        inserir(entidade, "2020-01-03T12:00:00Z", 1);
        when(outbox.removerLote(any(), anyInt())).thenThrow(new IllegalStateException());
        var itens = metricas.counter("retencao.itens", "passo", "auditoria");
        var falhas = metricas.counter("retencao.falhas", "passo", "outbox");
        double itensAntes = itens.count();
        double falhasAntes = falhas.count();

        executar(contexto);

        assertThat(jdbc.queryForList("""
                select ocorrido_em from auditoria where entidade_id = ? order by ocorrido_em""",
                java.sql.Timestamp.class, entidade)).extracting(java.sql.Timestamp::toInstant)
                .containsExactly(Instant.parse("2020-01-02T12:00:00Z"), Instant.parse("2020-01-03T12:00:00Z"));
        assertThat(itens.count() - itensAntes).isEqualTo(101);
        assertThat(falhas.count() - falhasAntes).isEqualTo(1);
    }

    @Test
    void shouldRejectDirectDeletionOfAuditRecordStillUnderRetention() {
        UUID entidade = UUID.randomUUID();
        jdbc.update("""
                insert into auditoria (id, ocorrido_em, ator_tipo, operacao, entidade_tipo, entidade_id)
                values (gen_random_uuid(), now() - interval '5 years' + interval '1 day',
                        'SISTEMA', 'EXPURGO_EVIDENCIA', 'EVIDENCIA', ?)""", entidade);

        assertThatThrownBy(() -> jdbc.update("delete from auditoria where entidade_id = ?", entidade))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("RN-124");
    }

    private void inserir(UUID entidade, String data, int quantidade) {
        jdbc.update("""
                insert into auditoria (id, ocorrido_em, ator_tipo, operacao, entidade_tipo, entidade_id)
                select gen_random_uuid(), ?, 'SISTEMA', 'EXPURGO_EVIDENCIA', 'EVIDENCIA', ?
                from generate_series(1, ?)""", OffsetDateTime.parse(data), entidade, quantidade);
    }
}
