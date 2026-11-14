package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.chamado.api.AtendimentoConsulta;
import com.gestaosla.backend.chamado.api.AtendimentoCorrente;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AtendimentoChamadoTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private AtendimentoConsulta consulta;

    private ChamadoTestHelper chamados;

    @BeforeEach
    void preparar() {
        chamados = new ChamadoTestHelper(contexto);
    }

    // No máximo um atendimento PENDENTE ou EM_ANDAMENTO por chamado: o índice único parcial barra o segundo.
    @Test
    void shouldAllowAtMostOneCurrentAttendancePerChamado() {
        UUID chamado = ChamadoTeste.chamado(jdbc);
        chamados.criarAtendimentoPendente(chamado, UUID.randomUUID());

        assertThatThrownBy(() -> chamados.criarAtendimentoPendente(chamado, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version)
                values (?, now(), now(), ?, ?, 'EM_ANDAMENTO', 0)""", UUID.randomUUID(), chamado, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(chamados.estadosDosAtendimentos(chamado)).containsExactly(EstadoAtendimento.PENDENTE);
    }

    // RN-70, RN-95: cada reabertura gera um atendimento novo; os anteriores ficam. Reaberto duas vezes = três.
    @Test
    void shouldKeepEveryAttendanceOfReopenedChamado() {
        UUID chamado = ChamadoTeste.chamado(jdbc);
        UUID tecnico = UUID.randomUUID();
        chamados.criarAtendimentoPendente(chamado, tecnico);
        chamados.interromperCorrente(chamado, AGORA);
        chamados.criarAtendimentoPendente(chamado, tecnico); // 1ª reabertura
        chamados.interromperCorrente(chamado, AGORA.plusSeconds(60));
        UUID terceiro = chamados.criarAtendimentoPendente(chamado, tecnico); // 2ª reabertura

        assertThat(chamados.estadosDosAtendimentos(chamado)).containsExactly(
                EstadoAtendimento.INTERROMPIDO, EstadoAtendimento.INTERROMPIDO, EstadoAtendimento.PENDENTE);
        assertThat(consulta.correnteDe(chamado)).contains(
                new AtendimentoCorrente(terceiro, chamado, tecnico, EstadoAtendimento.PENDENTE));
    }

    // RN-21: interromper preserva o atendimento (e, com ele, as evidências que apontam para ele).
    @Test
    void shouldPreserveInterruptedAttendance() {
        UUID chamado = ChamadoTeste.chamado(jdbc);
        UUID atendimento = chamados.criarAtendimentoPendente(chamado, UUID.randomUUID());

        chamados.interromperCorrente(chamado, AGORA);

        assertThat(jdbc.queryForObject("select estado from atendimento where id = ?", String.class, atendimento))
                .isEqualTo("INTERROMPIDO");
        assertThat(jdbc.queryForObject("select encerrado_em from atendimento where id = ?", Instant.class, atendimento))
                .isEqualTo(AGORA);
        assertThat(consulta.correnteDe(chamado)).isEmpty();
    }

    @Test
    void shouldIgnoreInterruptionWithoutCurrentAttendance() {
        UUID chamado = ChamadoTeste.chamado(jdbc);

        chamados.interromperCorrente(chamado, AGORA);

        assertThat(chamados.estadosDosAtendimentos(chamado)).isEmpty();
    }

    // A escrita roda na transação da operação do chamado; sozinha, falha.
    @Test
    void shouldRequireCallerTransaction() {
        UUID chamado = ChamadoTeste.chamado(jdbc);

        assertThatThrownBy(() -> chamados.criarPendenteSemTransacao(chamado, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
