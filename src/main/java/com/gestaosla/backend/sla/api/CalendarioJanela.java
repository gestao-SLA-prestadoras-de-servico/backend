package com.gestaosla.backend.sla.api;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

/**
 * Janela de atendimento: segunda a sexta, das 08h às 17h, no fuso do sistema (RN-60, RN-62). Todo prazo de SLA passa
 * por aqui. Função pura: não lê a hora atual nem o banco; o {@link Clock} só fornece o fuso. Feriados contam como dia
 * útil (RN-63).
 */
@Component
public class CalendarioJanela {

    public static final LocalTime ABERTURA = LocalTime.of(8, 0);
    public static final LocalTime FECHAMENTO = LocalTime.of(17, 0);

    private final ZoneId fuso;

    public CalendarioJanela(Clock clock) {
        this.fuso = clock.getZone();
    }

    /**
     * Instante em que se completam {@code minutos} de janela a partir de {@code instante} (RN-29). Início fora da janela
     * começa a contar na próxima abertura (RN-61). Um prazo que se completa exatamente no fechamento vence às 17:00
     * desse dia, não às 08:00 do dia seguinte.
     */
    public Instant adicionarMinutos(Instant instante, long minutos) {
        if (minutos < 0) {
            throw new IllegalArgumentException("Minutos de janela não podem ser negativos: " + minutos);
        }
        ZonedDateTime cursor = normalizar(instante.atZone(fuso));
        Duration falta = Duration.ofMinutes(minutos);
        while (true) {
            Duration restanteNoDia = Duration.between(cursor, fechamento(cursor.toLocalDate()));
            if (falta.compareTo(restanteNoDia) <= 0) {
                return cursor.plus(falta).toInstant();
            }
            falta = falta.minus(restanteNoDia);
            cursor = abertura(proximoDiaUtil(cursor.toLocalDate()));
        }
    }

    /** Minutos de janela entre os dois instantes (arredondados para baixo); 0 se {@code fim} não for depois de {@code inicio}. */
    public long minutosUteisEntre(Instant inicio, Instant fim) {
        if (!fim.isAfter(inicio)) {
            return 0;
        }
        ZonedDateTime de = inicio.atZone(fuso);
        ZonedDateTime ate = fim.atZone(fuso);
        Duration total = Duration.ZERO;
        // ponytail: um passo por dia; prazos de SLA são de dias úteis, não de anos.
        for (LocalDate dia = de.toLocalDate(); !dia.isAfter(ate.toLocalDate()); dia = dia.plusDays(1)) {
            if (!diaUtil(dia)) {
                continue;
            }
            ZonedDateTime comeco = maior(de, abertura(dia));
            ZonedDateTime termino = menor(ate, fechamento(dia));
            if (termino.isAfter(comeco)) {
                total = total.plus(Duration.between(comeco, termino));
            }
        }
        return total.toMinutes();
    }

    /** Leva o instante para dentro da janela: antes das 08h, à abertura do dia; a partir das 17h ou no fim de semana, à próxima abertura. */
    private ZonedDateTime normalizar(ZonedDateTime instante) {
        LocalDate dia = instante.toLocalDate();
        if (!diaUtil(dia) || !instante.toLocalTime().isBefore(FECHAMENTO)) {
            return abertura(proximoDiaUtil(dia));
        }
        return instante.toLocalTime().isBefore(ABERTURA) ? abertura(dia) : instante;
    }

    private static LocalDate proximoDiaUtil(LocalDate dia) {
        LocalDate proximo = dia.plusDays(1);
        while (!diaUtil(proximo)) {
            proximo = proximo.plusDays(1);
        }
        return proximo;
    }

    private static boolean diaUtil(LocalDate dia) {
        return dia.getDayOfWeek() != DayOfWeek.SATURDAY && dia.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private ZonedDateTime abertura(LocalDate dia) {
        return dia.atTime(ABERTURA).atZone(fuso);
    }

    private ZonedDateTime fechamento(LocalDate dia) {
        return dia.atTime(FECHAMENTO).atZone(fuso);
    }

    private static ZonedDateTime maior(ZonedDateTime a, ZonedDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static ZonedDateTime menor(ZonedDateTime a, ZonedDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
