package com.gestaosla.backend.outbox.api;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Operação da rotina de retenção sobre a tabela de saída. */
@Component
@RequiredArgsConstructor
public class LimpezaOutbox {

    private final JdbcClient jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removerLote(Instant corte, int tamanho) {
        return jdbc.sql("""
                delete from outbox_eventos where id in (
                    select id from outbox_eventos
                    where publicado_em < ?
                    order by publicado_em, id limit ?
                )""").params(corte.atOffset(ZoneOffset.UTC), tamanho).update();
    }
}
