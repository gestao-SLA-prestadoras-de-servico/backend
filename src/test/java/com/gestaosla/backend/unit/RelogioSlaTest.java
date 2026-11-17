package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gestaosla.backend.sla.api.EstadoRelogio;
import com.gestaosla.backend.sla.api.ResultadoCumprimento;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;
import com.gestaosla.backend.sla.internal.RelogioSla;

class RelogioSlaTest {

    private static final UUID CHAMADO = UUID.randomUUID();
    private static final Instant MARCO = Instant.parse("2026-11-16T11:00:00Z");
    private static final Instant VENCE = Instant.parse("2026-11-16T13:00:00Z");

    @Test
    void shouldStartRunning() {
        assertThat(relogio().estado()).isEqualTo(EstadoRelogio.CORRENDO);
    }

    @Test
    void shouldFulfillOnTimeWithoutViolation() {
        RelogioSla relogio = relogio();

        ResultadoCumprimento resultado = relogio.cumprir(VENCE.minusSeconds(1));

        assertThat(resultado.cumpriuAgora()).isTrue();
        assertThat(resultado.violacao()).isEmpty();
        assertThat(relogio.getVioladoEm()).isNull();
        assertThat(relogio.estado()).isEqualTo(EstadoRelogio.CUMPRIDO);
    }

    // RN-65: violação é o prazo ultrapassado; no instante exato do vencimento ainda está no prazo.
    @Test
    void shouldNotViolateWhenFulfilledExactlyAtDeadline() {
        RelogioSla relogio = relogio();

        assertThat(relogio.cumprir(VENCE).violacao()).isEmpty();
        assertThat(relogio.estado()).isEqualTo(EstadoRelogio.CUMPRIDO);
    }

    // RF-33, RN-67: cumprimento atrasado viola com o horário do estouro, não o da detecção, e devolve a violação.
    @Test
    void shouldMarkViolationAtDeadlineWhenFulfilledLate() {
        RelogioSla relogio = relogio();
        Instant atrasado = VENCE.plus(Duration.ofHours(3));

        ResultadoCumprimento resultado = relogio.cumprir(atrasado);

        assertThat(resultado.cumpriuAgora()).isTrue();
        assertThat(resultado.violacao()).contains(new Violacao(CHAMADO, TipoRelogio.RESPOSTA, 1, MARCO, VENCE));
        assertThat(relogio.getVioladoEm()).isEqualTo(VENCE);
        assertThat(relogio.getCumpridoEm()).isEqualTo(atrasado);
        assertThat(relogio.estado()).isEqualTo(EstadoRelogio.VIOLADO);
    }

    @Test
    void shouldKeepFirstFulfillmentOfCycle() {
        RelogioSla relogio = relogio();
        Instant primeiro = VENCE.minusSeconds(60);
        relogio.cumprir(primeiro);

        ResultadoCumprimento segundo = relogio.cumprir(VENCE.plus(Duration.ofDays(1)));

        assertThat(segundo.cumpriuAgora()).isFalse();
        assertThat(segundo.violacao()).isEmpty();
        assertThat(relogio.getCumpridoEm()).isEqualTo(primeiro);
        assertThat(relogio.estado()).isEqualTo(EstadoRelogio.CUMPRIDO);
    }

    // RN-69: violado pela rotina e cumprido depois continua VIOLADO, e não gera um segundo aviso.
    @Test
    void shouldStayViolatedWhenFulfilledAfterViolation() {
        RelogioSla relogio = relogio();
        assertThat(relogio.marcarViolacao()).isPresent();

        ResultadoCumprimento resultado = relogio.cumprir(VENCE.plus(Duration.ofHours(1)));

        assertThat(resultado.cumpriuAgora()).isTrue();
        assertThat(resultado.violacao()).isEmpty();
        assertThat(relogio.estado()).isEqualTo(EstadoRelogio.VIOLADO);
    }

    @Test
    void shouldMarkViolationOnlyOnce() {
        RelogioSla relogio = relogio();

        assertThat(relogio.marcarViolacao()).isPresent();
        assertThat(relogio.marcarViolacao()).isEmpty();
        assertThat(relogio.getVioladoEm()).isEqualTo(VENCE);
    }

    // RN-17: o prazo novo vale a partir do marco; vencimento ainda no futuro não viola.
    @Test
    void shouldRecalculateRunningClock() {
        RelogioSla relogio = relogio();
        Instant novoVence = VENCE.plus(Duration.ofHours(5));

        assertThat(relogio.recalcular(540, novoVence, VENCE)).isEmpty();
        assertThat(relogio.getPrazoMinutos()).isEqualTo(540);
        assertThat(relogio.getVenceEm()).isEqualTo(novoVence);
        assertThat(relogio.getMarcoInicial()).isEqualTo(MARCO);
        assertThat(relogio.estado()).isEqualTo(EstadoRelogio.CORRENDO);
    }

    // Prazo novo já no passado: a violação é marcada na hora, com o vencimento novo.
    @Test
    void shouldViolateRightAwayWhenRecalculatedDeadlineIsPast() {
        RelogioSla relogio = relogio();
        Instant novoVence = MARCO.plus(Duration.ofMinutes(30));

        assertThat(relogio.recalcular(30, novoVence, VENCE)).contains(new Violacao(CHAMADO, TipoRelogio.RESPOSTA, 1, MARCO, novoVence));
        assertThat(relogio.getVioladoEm()).isEqualTo(novoVence);
    }

    // RN-18: prazo cumprido ou violado não é recalculado.
    @Test
    void shouldRefuseToRecalculateFulfilledOrViolatedClock() {
        RelogioSla cumprido = relogio();
        cumprido.cumprir(VENCE.minusSeconds(1));
        RelogioSla violado = relogio();
        violado.marcarViolacao();

        assertThat(cumprido.correndo()).isFalse();
        assertThat(violado.correndo()).isFalse();
        assertThatThrownBy(() -> cumprido.recalcular(540, VENCE, VENCE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> violado.recalcular(540, VENCE, VENCE)).isInstanceOf(IllegalStateException.class);
        assertThat(violado.getVioladoEm()).isEqualTo(VENCE);
    }

    @Test
    void shouldCloseCycleKeepingHistory() {
        RelogioSla cumprido = relogio();
        cumprido.cumprir(VENCE.minusSeconds(1));

        assertThat(cumprido.encerrarCiclo(VENCE.plus(Duration.ofDays(1)))).isEmpty();
        assertThat(cumprido.isAtivo()).isFalse();
        assertThat(cumprido.estado()).isEqualTo(EstadoRelogio.CUMPRIDO);
    }

    // Ainda correndo e já vencido na reabertura: a violação não pode se perder entre duas passadas da rotina.
    @Test
    void shouldMarkPendingViolationWhenClosingExpiredCycle() {
        RelogioSla correndo = relogio();

        assertThat(correndo.encerrarCiclo(VENCE.plusSeconds(1))).isPresent();
        assertThat(correndo.getVioladoEm()).isEqualTo(VENCE);
        assertThat(relogio().encerrarCiclo(VENCE)).isEmpty();
    }

    private static RelogioSla relogio() {
        return new RelogioSla(CHAMADO, TipoRelogio.RESPOSTA, 1, MARCO, 120, VENCE);
    }
}
