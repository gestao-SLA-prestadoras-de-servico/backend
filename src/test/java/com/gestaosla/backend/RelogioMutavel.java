package com.gestaosla.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Clock de teste: o tempo só anda quando o teste manda. Com {@code @Import(RelogioMutavel.Configuracao.class)}
 * ele substitui o Clock de produção em todo bean. O contexto é reaproveitado entre testes, então
 * cada teste chama {@link #definir(Instant)} antes de depender da hora.
 */
public class RelogioMutavel extends Clock {

    private final ZoneId fuso;
    private volatile Instant agora;

    public RelogioMutavel(Instant agora, ZoneId fuso) {
        this.agora = agora;
        this.fuso = fuso;
    }

    public void definir(Instant instante) {
        agora = instante;
    }

    public void avancar(Duration duracao) {
        agora = agora.plus(duracao);
    }

    @Override
    public Instant instant() {
        return agora;
    }

    @Override
    public ZoneId getZone() {
        return fuso;
    }

    // O sistema tem fuso único (RN-62); uma cópia em outro fuso deixaria de avançar junto.
    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("fuso único: use o Clock injetado");
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuracao {

        @Bean
        @Primary
        RelogioMutavel relogioMutavel(@Value("${app.fuso-horario}") ZoneId fuso) {
            return new RelogioMutavel(Instant.parse("2026-01-05T12:00:00Z"), fuso);
        }
    }
}
