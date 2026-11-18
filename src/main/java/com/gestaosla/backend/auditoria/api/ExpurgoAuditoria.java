package com.gestaosla.backend.auditoria.api;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Operação da rotina de retenção sobre a trilha de auditoria. */
@Component
@RequiredArgsConstructor
public class ExpurgoAuditoria {

    private final JdbcClient jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removerLote(Instant agora, int tamanho) {
        // O cálculo no fuso da sessão segue o mesmo calendário do trigger da auditoria.
        return jdbc.sql("""
                delete from auditoria where id in (
                    select id from auditoria
                    where ocorrido_em < least(cast(? as timestamptz), transaction_timestamp()) - interval '5 years'
                    order by ocorrido_em, id limit ?
                )""").params(agora.atOffset(ZoneOffset.UTC), tamanho).update();
    }
}
