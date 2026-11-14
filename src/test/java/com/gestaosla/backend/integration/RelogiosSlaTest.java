package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.sla.api.EstadoRelogio;
import com.gestaosla.backend.sla.api.RelogioView;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.ResultadoCumprimento;
import com.gestaosla.backend.sla.api.TipoRelogio;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RelogiosSlaTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    // Versão 1 da política "Padrão" (seed da V8): alta 120/540/1080, normal 540/1620/2700.
    private static final UUID VERSAO_PADRAO = UUID.fromString("0199a000-0000-7000-8000-00000000c002");
    // Sexta-feira, 16:30.
    private static final Instant ABERTURA = em("2026-11-13T16:30");

    @Autowired
    private RelogiosSla relogios;

    @Autowired
    private ContratoConsulta contratos;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcTemplate jdbc;

    // RF-30, RN-28, RN-64: os três prazos saem da versão e da prioridade, em minutos de janela, e são gravados.
    @Test
    void shouldStartThreeClocksWithDeadlinesFromPolicyVersionAndPriority() {
        UUID alta = UUID.randomUUID();
        UUID normal = UUID.randomUUID();

        emTransacao(() -> relogios.iniciarRelogios(alta, versaoPadrao(), Prioridade.ALTA, ABERTURA));
        emTransacao(() -> relogios.iniciarRelogios(normal, versaoPadrao(), Prioridade.NORMAL, ABERTURA));

        assertThat(relogios.relogiosAtivos(alta)).extracting(RelogioView::tipo, RelogioView::prazoMinutos, RelogioView::venceEm)
                .containsExactly(
                        tuple(TipoRelogio.RESPOSTA, 120, em("2026-11-16T09:30")),
                        tuple(TipoRelogio.CAMPO, 540, em("2026-11-16T16:30")),
                        tuple(TipoRelogio.RESOLUCAO, 1080, em("2026-11-17T16:30")));
        assertThat(relogios.relogiosAtivos(normal)).extracting(RelogioView::tipo, RelogioView::prazoMinutos, RelogioView::venceEm)
                .containsExactly(
                        tuple(TipoRelogio.RESPOSTA, 540, em("2026-11-16T16:30")),
                        tuple(TipoRelogio.CAMPO, 1620, em("2026-11-18T16:30")),
                        tuple(TipoRelogio.RESOLUCAO, 2700, em("2026-11-20T16:30")));
        assertThat(relogios.relogiosAtivos(alta)).allSatisfy(r -> {
            assertThat(r.ciclo()).isEqualTo(1);
            assertThat(r.ativo()).isTrue();
            assertThat(r.marcoInicial()).isEqualTo(ABERTURA);
            assertThat(r.estado()).isEqualTo(EstadoRelogio.CORRENDO);
        });
    }

    // RN-27: cada relógio viola sem afetar os outros.
    @Test
    void shouldViolateEachClockIndependently() {
        UUID chamado = abrir(Prioridade.ALTA);

        ResultadoCumprimento resposta = emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESPOSTA, em("2026-11-16T10:00")));
        ResultadoCumprimento campo = emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.CAMPO, em("2026-11-16T16:00")));

        assertThat(resposta.violacao()).hasValueSatisfying(v -> {
            assertThat(v.tipo()).isEqualTo(TipoRelogio.RESPOSTA);
            assertThat(v.violadaEm()).isEqualTo(em("2026-11-16T09:30"));
            assertThat(v.marcoInicial()).isEqualTo(ABERTURA);
        });
        assertThat(campo.violacao()).isEmpty();
        assertThat(relogios.relogiosAtivos(chamado)).extracting(RelogioView::estado)
                .containsExactly(EstadoRelogio.VIOLADO, EstadoRelogio.CUMPRIDO, EstadoRelogio.CORRENDO);
        assertThat(jdbc.queryForObject("select violado_em from relogio_sla where chamado_id = ? and tipo = 'RESPOSTA'",
                Instant.class, chamado)).isEqualTo(em("2026-11-16T09:30"));
    }

    @Test
    void shouldKeepFirstFulfillmentWhenCalledAgain() {
        UUID chamado = abrir(Prioridade.NORMAL);

        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESOLUCAO, em("2026-11-17T10:00")));
        ResultadoCumprimento segundo = emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESOLUCAO, em("2026-11-27T10:00")));

        assertThat(segundo.cumpriuAgora()).isFalse();
        assertThat(relogios.relogiosAtivos(chamado).get(2).cumpridoEm()).isEqualTo(em("2026-11-17T10:00"));
    }

    @Test
    void shouldRejectFulfillingClockThatDoesNotExist() {
        assertThatThrownBy(() -> emTransacao(() -> relogios.cumprir(UUID.randomUUID(), TipoRelogio.CAMPO, ABERTURA)))
                .isInstanceOf(IllegalStateException.class);
    }

    // A escrita roda na transação do chamado; sozinha, não grava nada.
    @Test
    void shouldRequireCallerTransaction() {
        UUID chamado = UUID.randomUUID();

        assertThatThrownBy(() -> relogios.iniciarRelogios(chamado, versaoPadrao(), Prioridade.ALTA, ABERTURA))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(relogios.relogiosAtivos(chamado)).isEmpty();
    }

    @Test
    void shouldAllowOnlyOneActiveClockPerTypeAndCycle() {
        UUID chamado = abrir(Prioridade.ALTA);

        assertThatThrownBy(() -> emTransacao(() -> relogios.iniciarRelogios(chamado, versaoPadrao(), Prioridade.ALTA, ABERTURA)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into relogio_sla (id, created_at, updated_at, chamado_id, tipo, ciclo, ativo, marco_inicial,
                                         prazo_minutos, vence_em, version)
                values (?, now(), now(), ?, 'CAMPO', 2, true, now(), 60, now(), 0)""", UUID.randomUUID(), chamado))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("relogio_sla_ativo_uk");
    }

    private UUID abrir(Prioridade prioridade) {
        UUID chamado = UUID.randomUUID();
        emTransacao(() -> relogios.iniciarRelogios(chamado, versaoPadrao(), prioridade, ABERTURA));
        return chamado;
    }

    private PoliticaDoContrato versaoPadrao() {
        return contratos.versaoDaPolitica(VERSAO_PADRAO);
    }

    private <T> T emTransacao(Supplier<T> acao) {
        return transacao.execute(status -> acao.get());
    }

    private static org.assertj.core.groups.Tuple tuple(Object... valores) {
        return org.assertj.core.groups.Tuple.tuple(valores);
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
