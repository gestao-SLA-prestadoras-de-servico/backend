package com.gestaosla.backend.chamado.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ChamadosParaRetencao {

    private final JdbcClient jdbc;

    public Set<UUID> finalizadosAntes(List<UUID> ids, Instant corte) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql("""
                select id from chamado
                where id in (:ids) and estado in ('ENCERRADO', 'CANCELADO') and estado_final_em < :corte
                """).param("ids", ids).param("corte", corte.atOffset(ZoneOffset.UTC)).query(UUID.class).set();
    }
}
