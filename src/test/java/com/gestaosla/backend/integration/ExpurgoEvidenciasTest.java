package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.gestaosla.backend.EvidenciaRetencaoTeste;
import com.gestaosla.backend.EvidenciaRetencaoTeste.Registro;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.evidencia.api.ExpurgoEvidencias;
import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ExpurgoEvidenciasTest {

    private static final Instant AGORA = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant LIMITE = AGORA.minus(180, ChronoUnit.DAYS);
    private static final Instant ENVIO = LIMITE.minus(400, ChronoUnit.DAYS);

    @Autowired
    private ApplicationContext contexto;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RelogioMutavel relogio;
    @Autowired
    private ExpurgoEvidencias expurgo;

    @Test
    void shouldPurgeOnlyOldFinalTicketsAndKeepMetadataAndAudit() {
        relogio.definir(AGORA);
        var encerrada = criar("ENCERRADO", LIMITE.minusSeconds(1), "PROCESSANDO");
        var cancelada = criar("CANCELADO", LIMITE.minusSeconds(1), "DISPONIVEL");
        var limite = criar("CANCELADO", LIMITE, "DISPONIVEL");
        var resolvida = criar("RESOLVIDO", AGORA.minus(365, ChronoUnit.DAYS), "DISPONIVEL");
        var total = new AtomicInteger();

        expurgo.executar(AGORA, 1, total::addAndGet);
        expurgo.executar(AGORA, 1, total::addAndGet);

        assertThat(total.get()).isEqualTo(2);
        for (var registro : List.of(encerrada, cancelada)) {
            assertThat(storage().listar(registro.prefixo())).isEmpty();
            assertThat(jdbc.queryForMap("""
                    select estado, atendimento_id, chamado_id, enviada_em, expurgada_em from evidencia where id = ?
                    """, registro.id())).containsEntry("estado", "EXPURGADA")
                    .containsEntry("atendimento_id", registro.atendimento())
                    .containsEntry("chamado_id", registro.chamado())
                    .containsEntry("enviada_em", Timestamp.from(ENVIO))
                    .containsEntry("expurgada_em", Timestamp.from(AGORA));
            assertThat(jdbc.queryForMap("""
                    select ator_tipo, chamado_id, ocorrido_em, dados ->> 'motivo' as motivo from auditoria
                    where operacao = 'EXPURGO_EVIDENCIA' and entidade_id = ?""", registro.id()))
                    .containsEntry("ator_tipo", "SISTEMA").containsEntry("chamado_id", registro.chamado())
                    .containsEntry("ocorrido_em", Timestamp.from(AGORA)).containsEntry("motivo", "EXPIRACAO_RETENCAO");
        }
        for (var registro : List.of(limite, resolvida)) {
            assertThat(estado(registro)).isEqualTo("DISPONIVEL");
            assertThat(storage().listar(registro.prefixo())).hasSize(4);
        }
    }

    @Test
    void shouldRetryAfterStorageDeletionAndDatabaseRollback() {
        relogio.definir(AGORA);
        var registro = criar("CANCELADO", LIMITE.minusSeconds(1), "DISPONIVEL");
        jdbc.execute("alter table auditoria add constraint teste_expurgo_bloqueado check (entidade_id <> '"
                + registro.id() + "'::uuid)");
        try {
            assertThatThrownBy(() -> expurgo.executar(AGORA, 10, i -> { }))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("alter table auditoria drop constraint teste_expurgo_bloqueado");
        }

        assertThat(storage().listar(registro.prefixo())).isEmpty();
        assertThat(estado(registro)).isEqualTo("DISPONIVEL");

        expurgo.executar(AGORA, 10, i -> { });

        assertThat(estado(registro)).isEqualTo("EXPURGADA");
        assertThat(storage().listar(registro.prefixo())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from auditoria where entidade_id = ?", Integer.class, registro.id()))
                .isEqualTo(1);
    }

    private Registro criar(String chamado, Instant dataFinal, String estado) {
        var registro = EvidenciaRetencaoTeste.criar(jdbc, chamado, dataFinal, estado, ENVIO, true);
        for (String chave : List.of(registro.original(), registro.miniatura(), registro.visualizacao(), registro.prefixo() + "extra")) {
            storage().gravar(chave, new byte[] {1}, "application/octet-stream");
        }
        return registro;
    }

    private String estado(Registro registro) {
        return jdbc.queryForObject("select estado from evidencia where id = ?", String.class, registro.id());
    }

    private ArmazenamentoTestHelper storage() {
        return new ArmazenamentoTestHelper(contexto);
    }
}
