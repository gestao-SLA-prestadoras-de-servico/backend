package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.internal.Atendimento;

class AtendimentoTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");

    @Test
    void shouldStartPending() {
        Atendimento atendimento = new Atendimento(UUID.randomUUID(), UUID.randomUUID());

        assertThat(atendimento.getEstado()).isEqualTo(EstadoAtendimento.PENDENTE);
        assertThat(atendimento.getEncerradoEm()).isNull();
    }

    // RN-21: interromper encerra o atendimento corrente, sem apagá-lo.
    @ParameterizedTest
    @EnumSource(names = {"PENDENTE", "EM_ANDAMENTO"})
    void shouldInterruptCurrentAttendance(EstadoAtendimento corrente) {
        Atendimento atendimento = em(corrente);

        atendimento.interromper(AGORA);

        assertThat(atendimento.getEstado()).isEqualTo(EstadoAtendimento.INTERROMPIDO);
        assertThat(atendimento.getEncerradoEm()).isEqualTo(AGORA);
    }

    @ParameterizedTest
    @EnumSource(names = {"CONCLUIDO", "INTERROMPIDO"})
    void shouldNotInterruptFinishedAttendance(EstadoAtendimento encerrado) {
        Atendimento atendimento = em(encerrado);

        assertThatThrownBy(() -> atendimento.interromper(AGORA)).isInstanceOf(IllegalStateException.class);
        assertThat(atendimento.getEstado()).isEqualTo(encerrado);
    }

    private static Atendimento em(EstadoAtendimento estado) {
        Atendimento atendimento = new Atendimento(UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(atendimento, "estado", estado);
        return atendimento;
    }
}
