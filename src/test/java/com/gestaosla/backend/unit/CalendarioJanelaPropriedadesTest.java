package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.gestaosla.backend.sla.api.CalendarioJanela;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/** Propriedades do calendário para instantes e prazos quaisquer, inclusive com segundos quebrados (jqwik). */
class CalendarioJanelaPropriedadesTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    private static final CalendarioJanela calendario = new CalendarioJanela(Clock.system(FORTALEZA));

    // 2020-01-01 a 2035-12-31, em segundos.
    private static final long DE = 1_577_836_800L;
    private static final long ATE = 2_082_758_399L;

    @Property(tries = 3000)
    void minutesBetweenShouldInvertAddMinutes(@ForAll @LongRange(min = DE, max = ATE) long segundos,
            @ForAll @IntRange(min = 1, max = 30_000) int minutos) {
        Instant inicio = Instant.ofEpochSecond(segundos);

        assertThat(calendario.minutosUteisEntre(inicio, calendario.adicionarMinutos(inicio, minutos))).isEqualTo(minutos);
    }

    @Property(tries = 3000)
    void deadlineShouldAlwaysFallInsideWindow(@ForAll @LongRange(min = DE, max = ATE) long segundos,
            @ForAll @IntRange(min = 1, max = 30_000) int minutos) {
        ZonedDateTime prazo = calendario.adicionarMinutos(Instant.ofEpochSecond(segundos), minutos).atZone(FORTALEZA);

        assertThat(prazo.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(prazo.toLocalTime()).isAfter(LocalTime.of(8, 0)).isBeforeOrEqualTo(LocalTime.of(17, 0));
    }

    @Property(tries = 3000)
    void addingInTwoStepsShouldEqualAddingAtOnce(@ForAll @LongRange(min = DE, max = ATE) long segundos,
            @ForAll @IntRange(min = 1, max = 10_000) int primeiro, @ForAll @IntRange(min = 1, max = 10_000) int segundo) {
        Instant inicio = Instant.ofEpochSecond(segundos);

        assertThat(calendario.adicionarMinutos(calendario.adicionarMinutos(inicio, primeiro), segundo))
                .isEqualTo(calendario.adicionarMinutos(inicio, primeiro + segundo));
    }
}
