package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static RelogioSla relogio() {
        return new RelogioSla(CHAMADO, TipoRelogio.RESPOSTA, 1, MARCO, 120, VENCE);
    }
}
