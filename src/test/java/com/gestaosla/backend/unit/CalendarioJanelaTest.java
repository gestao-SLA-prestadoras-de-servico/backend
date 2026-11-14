package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.gestaosla.backend.sla.api.CalendarioJanela;

class CalendarioJanelaTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    private static final CalendarioJanela calendario = new CalendarioJanela(Clock.system(FORTALEZA));

    // Datas no fuso do sistema. 13/11/2026 é sexta-feira; 25/12/2026 também; 01/01/2027 é sexta.
    @ParameterizedTest(name = "{0} + {1} min = {2}")
    @CsvSource({
            // Critérios de aceite da [sla - 01]
            "2026-11-13T16:30, 120,  2026-11-16T09:30",  // sexta 16:30 → segunda 09:30
            "2026-11-14T10:00, 540,  2026-11-16T17:00",  // sábado → segunda 17:00 (não 08:00 de terça)
            "2026-11-17T08:00, 2700, 2026-11-23T17:00",  // terça 08:00 + 5 dias úteis → segunda seguinte 17:00
            "2026-11-18T07:59, 60,   2026-11-18T09:00",  // antes da abertura conta a partir das 08:00
            "2026-11-18T08:00, 60,   2026-11-18T09:00",
            // Bordas
            "2026-11-15T12:00, 60,   2026-11-16T09:00",  // domingo
            "2026-11-13T17:00, 60,   2026-11-16T09:00",  // sexta às 17:00 em ponto já está fora
            "2026-11-13T18:30, 1,    2026-11-16T08:01",  // sexta à noite
            "2026-11-18T16:00, 60,   2026-11-18T17:00",  // termina no fechamento: vence às 17:00 do mesmo dia
            "2026-11-18T16:00, 61,   2026-11-19T08:01",
            "2026-11-18T10:00, 0,    2026-11-18T10:00",
            "2026-10-30T16:00, 120,  2026-11-02T09:00",  // virada de mês, atravessando o fim de semana
            "2026-12-31T16:00, 120,  2027-01-01T09:00",  // virada de ano; 1º de janeiro conta como útil (RN-63)
            "2026-12-25T08:00, 540,  2026-12-25T17:00"}) // Natal conta como dia útil (RN-63)
    void shouldAddWindowMinutes(LocalDateTime inicio, long minutos, LocalDateTime esperado) {
        assertThat(calendario.adicionarMinutos(em(inicio), minutos)).isEqualTo(em(esperado));
    }

    @ParameterizedTest(name = "{0} → {1} = {2} min")
    @CsvSource({
            "2026-11-13T16:30, 2026-11-16T09:30, 120",
            "2026-11-14T10:00, 2026-11-16T17:00, 540",
            "2026-11-17T08:00, 2026-11-23T17:00, 2700",
            "2026-11-18T07:59, 2026-11-18T09:00, 60",
            "2026-11-14T10:00, 2026-11-15T20:00, 0",     // fim de semana inteiro
            "2026-11-18T17:30, 2026-11-19T07:30, 0",     // noite
            "2026-11-18T12:00, 2026-11-18T12:00, 0",
            "2026-11-18T12:00, 2026-11-18T11:00, 0"})    // fim antes do início
    void shouldCountWindowMinutesBetween(LocalDateTime inicio, LocalDateTime fim, long esperado) {
        assertThat(calendario.minutosUteisEntre(em(inicio), em(fim))).isEqualTo(esperado);
    }

    // O fuso vem do Clock, não da JVM: a suíte roda com TZ=UTC e o resultado continua o de Fortaleza.
    @Test
    void shouldUseTimeZoneFromClock() {
        Instant sexta1630Fortaleza = em(LocalDateTime.parse("2026-11-13T16:30"));
        CalendarioJanela emUtc = new CalendarioJanela(Clock.system(ZoneOffset.UTC));

        assertThat(calendario.adicionarMinutos(sexta1630Fortaleza, 120)).isEqualTo(em(LocalDateTime.parse("2026-11-16T09:30")));
        // Em UTC, 16:30 de Fortaleza são 19:30: fora da janela, conta a partir de segunda 08:00 UTC.
        assertThat(emUtc.adicionarMinutos(sexta1630Fortaleza, 120)).isEqualTo(Instant.parse("2026-11-16T10:00:00Z"));
    }

    @Test
    void shouldRejectNegativeMinutes() {
        assertThatThrownBy(() -> calendario.adicionarMinutos(Instant.EPOCH, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Instant em(LocalDateTime local) {
        return local.atZone(FORTALEZA).toInstant();
    }
}
