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

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prazos;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.sla.api.EstadoRelogio;
import com.gestaosla.backend.sla.api.RelogioView;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.ResultadoCumprimento;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
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

    @Autowired
    private RelogioMutavel relogio;

    // RF-30, RN-28, RN-64: os três prazos saem da versão e da prioridade, em minutos de janela, e são gravados.
    @Test
    void shouldStartThreeClocksWithDeadlinesFromPolicyVersionAndPriority() {
        UUID alta = ChamadoTeste.chamado(jdbc);
        UUID normal = ChamadoTeste.chamado(jdbc);

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
        UUID chamado = ChamadoTeste.chamado(jdbc);

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

    // ---- [sla - 03] Recálculo por prioridade ----

    // RF-31, RN-17, RN-18: só os que correm mudam, contando do próprio marco; o cumprido fica como estava.
    @Test
    void shouldRecalculateOnlyRunningClocksFromTheirStartMark() {
        UUID chamado = abrir(Prioridade.NORMAL);
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESPOSTA, em("2026-11-16T10:00")));
        relogio.definir(em("2026-11-16T11:00"));

        List<Violacao> violacoes = emTransacao(() -> relogios.recalcularPorPrioridade(chamado, versaoPadrao(), Prioridade.ALTA));

        assertThat(violacoes).isEmpty();
        assertThat(relogios.relogiosAtivos(chamado)).extracting(RelogioView::tipo, RelogioView::prazoMinutos, RelogioView::venceEm)
                .containsExactly(
                        tuple(TipoRelogio.RESPOSTA, 540, em("2026-11-16T16:30")),   // cumprido: não muda
                        tuple(TipoRelogio.CAMPO, 540, em("2026-11-16T16:30")),      // era 1620 (quarta)
                        tuple(TipoRelogio.RESOLUCAO, 1080, em("2026-11-17T16:30"))); // era 2700 (sexta seguinte)
    }

    // Prazo novo que já passou: violado na hora, com o vencimento novo, e devolvido ao chamador.
    @Test
    void shouldViolateRightAwayWhenNewDeadlineIsAlreadyPast() {
        UUID chamado = abrir(Prioridade.NORMAL);
        relogio.definir(em("2026-11-17T10:00"));

        List<Violacao> violacoes = emTransacao(() -> relogios.recalcularPorPrioridade(chamado, versaoPadrao(), Prioridade.ALTA));

        assertThat(violacoes).extracting(Violacao::tipo, Violacao::violadaEm).containsExactlyInAnyOrder(
                tuple(TipoRelogio.RESPOSTA, em("2026-11-16T09:30")),
                tuple(TipoRelogio.CAMPO, em("2026-11-16T16:30")));
        assertThat(relogios.relogiosAtivos(chamado)).extracting(RelogioView::estado)
                .containsExactly(EstadoRelogio.VIOLADO, EstadoRelogio.VIOLADO, EstadoRelogio.CORRENDO);
    }

    @Test
    void shouldNotRecalculateViolatedClock() {
        UUID chamado = abrir(Prioridade.ALTA);
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESPOSTA, em("2026-11-16T10:00")));
        relogio.definir(em("2026-11-16T11:00"));

        emTransacao(() -> relogios.recalcularPorPrioridade(chamado, versaoPadrao(), Prioridade.NORMAL));

        assertThat(relogios.relogiosAtivos(chamado).get(0)).satisfies(r -> {
            assertThat(r.prazoMinutos()).isEqualTo(120);
            assertThat(r.violadoEm()).isEqualTo(em("2026-11-16T09:30"));
        });
    }

    // RN-05: o sla usa a versão que o chamador passa (a guardada no chamado); não busca a vigente.
    @Test
    void shouldRecalculateWithVersionGivenByCaller() {
        UUID chamado = abrir(Prioridade.NORMAL);
        PoliticaDoContrato guardada = new PoliticaDoContrato(UUID.randomUUID(), UUID.randomUUID(), 1, Prioridade.NORMAL,
                new Prazos(60, 120, 180), new Prazos(600, 1200, 1800));
        relogio.definir(ABERTURA);

        emTransacao(() -> relogios.recalcularPorPrioridade(chamado, guardada, Prioridade.ALTA));

        assertThat(relogios.relogiosAtivos(chamado)).extracting(RelogioView::prazoMinutos).containsExactly(60, 120, 180);
    }

    // ---- [sla - 04] Reabertura ----

    // RF-32, RN-96, RN-97: CAMPO e RESOLUCAO recomeçam no ciclo 2 com o marco da reabertura; RESPOSTA não muda.
    @Test
    void shouldRestartFieldAndResolutionClocksOnReopening() {
        UUID chamado = abrir(Prioridade.ALTA);
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESPOSTA, em("2026-11-16T09:00")));
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.CAMPO, em("2026-11-16T15:00")));
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESOLUCAO, em("2026-11-17T10:00")));
        Instant reabertura = em("2026-11-18T10:00");

        List<Violacao> violacoes = emTransacao(() -> relogios.reiniciarParaReabertura(chamado, versaoPadrao(), Prioridade.ALTA, reabertura));

        assertThat(violacoes).isEmpty();
        assertThat(relogios.relogiosAtivos(chamado))
                .extracting(RelogioView::tipo, RelogioView::ciclo, RelogioView::marcoInicial, RelogioView::venceEm, RelogioView::estado)
                .containsExactly(
                        tuple(TipoRelogio.RESPOSTA, 1, ABERTURA, em("2026-11-16T09:30"), EstadoRelogio.CUMPRIDO),
                        tuple(TipoRelogio.CAMPO, 2, reabertura, em("2026-11-19T10:00"), EstadoRelogio.CORRENDO),
                        tuple(TipoRelogio.RESOLUCAO, 2, reabertura, em("2026-11-20T10:00"), EstadoRelogio.CORRENDO));
        assertThat(relogios.historico(chamado)).extracting(RelogioView::tipo, RelogioView::ciclo, RelogioView::ativo)
                .containsExactly(
                        tuple(TipoRelogio.RESPOSTA, 1, true),
                        tuple(TipoRelogio.CAMPO, 1, false),
                        tuple(TipoRelogio.CAMPO, 2, true),
                        tuple(TipoRelogio.RESOLUCAO, 1, false),
                        tuple(TipoRelogio.RESOLUCAO, 2, true));
        assertThat(relogios.historico(chamado).get(1).cumpridoEm()).isEqualTo(em("2026-11-16T15:00"));
    }

    // RN-54: violação do campo no ciclo 1 e no ciclo 2 têm marcos diferentes, então são avisos diferentes.
    @Test
    void shouldTreatViolationsOfDifferentCyclesAsDifferentEvents() {
        UUID chamado = abrir(Prioridade.ALTA);
        Violacao ciclo1 = emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.CAMPO, em("2026-11-17T09:00"))).violacao().orElseThrow();
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESOLUCAO, em("2026-11-17T10:00")));
        emTransacao(() -> relogios.reiniciarParaReabertura(chamado, versaoPadrao(), Prioridade.ALTA, em("2026-11-18T10:00")));

        Violacao ciclo2 = emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.CAMPO, em("2026-11-20T09:00"))).violacao().orElseThrow();

        assertThat(ciclo1.ciclo()).isEqualTo(1);
        assertThat(ciclo2.ciclo()).isEqualTo(2);
        assertThat(ciclo1.marcoInicial()).isEqualTo(ABERTURA);
        assertThat(ciclo2.marcoInicial()).isEqualTo(em("2026-11-18T10:00"));
        assertThat(ciclo2.violadaEm()).isEqualTo(em("2026-11-19T10:00"));
    }

    // Depois da reabertura, o recálculo conta do marco da reabertura, não da abertura.
    @Test
    void shouldRecalculateFromReopeningMark() {
        UUID chamado = abrir(Prioridade.NORMAL);
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESPOSTA, em("2026-11-16T10:00")));
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.CAMPO, em("2026-11-17T10:00")));
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESOLUCAO, em("2026-11-18T10:00")));
        emTransacao(() -> relogios.reiniciarParaReabertura(chamado, versaoPadrao(), Prioridade.NORMAL, em("2026-11-18T10:00")));
        relogio.definir(em("2026-11-18T11:00"));

        emTransacao(() -> relogios.recalcularPorPrioridade(chamado, versaoPadrao(), Prioridade.ALTA));

        assertThat(relogios.relogiosAtivos(chamado)).extracting(RelogioView::tipo, RelogioView::venceEm).containsExactly(
                tuple(TipoRelogio.RESPOSTA, em("2026-11-16T16:30")),   // cumprido: não muda
                tuple(TipoRelogio.CAMPO, em("2026-11-19T10:00")),      // reabertura (quarta 10:00) + 540
                tuple(TipoRelogio.RESOLUCAO, em("2026-11-20T10:00")));
    }

    // Campo nunca cumprido e já vencido na reabertura: a violação é marcada ao encerrar o ciclo.
    @Test
    void shouldReturnPendingViolationOfClosedCycle() {
        UUID chamado = abrir(Prioridade.ALTA);
        emTransacao(() -> relogios.cumprir(chamado, TipoRelogio.RESOLUCAO, em("2026-11-16T10:00")));

        List<Violacao> violacoes = emTransacao(() -> relogios.reiniciarParaReabertura(chamado, versaoPadrao(), Prioridade.ALTA, em("2026-11-18T10:00")));

        assertThat(violacoes).extracting(Violacao::tipo, Violacao::ciclo, Violacao::violadaEm)
                .containsExactly(tuple(TipoRelogio.CAMPO, 1, em("2026-11-16T16:30")));
    }

    private UUID abrir(Prioridade prioridade) {
        UUID chamado = ChamadoTeste.chamado(jdbc);
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
