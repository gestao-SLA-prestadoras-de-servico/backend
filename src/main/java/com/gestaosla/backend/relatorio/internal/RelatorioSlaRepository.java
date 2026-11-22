package com.gestaosla.backend.relatorio.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RelatorioSlaRepository {

    private static final String SELECT_AGREGADO = """
            select %s as cliente_id, %s as tecnico_sub, tipo,
                   count(*) filter (where resultado = 'CUMPRIDO') as cumpridos,
                   count(*) filter (where resultado = 'VIOLADO') as violados
              from apuracao_sla
             where congelada_em >= :inicio and congelada_em <= :fim
               and (cast(:clienteId as uuid) is null or cliente_id = :clienteId)
               and (cast(:tecnicoSub as uuid) is null or tecnico_sub = :tecnicoSub)
               %s
             group by %s
             order by %s
            """;

    private final NamedParameterJdbcTemplate jdbc;

    RelatorioSlaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<LinhaAgregada> geral(Instant inicio, Instant fim, UUID clienteId, UUID tecnicoSub) {
        return consultar("null", "null", "", "null", "null", inicio, fim, clienteId, tecnicoSub);
    }

    List<LinhaAgregada> porCliente(Instant inicio, Instant fim, UUID clienteId, UUID tecnicoSub) {
        return consultar("cliente_id", "null", "", "cliente_id", "null", inicio, fim, clienteId, tecnicoSub);
    }

    List<LinhaAgregada> porTecnico(Instant inicio, Instant fim, UUID clienteId, UUID tecnicoSub) {
        return consultar("null", "tecnico_sub", "and tipo <> 'RESPOSTA'", "null", "tecnico_sub", inicio, fim,
                clienteId, tecnicoSub);
    }

    private List<LinhaAgregada> consultar(String clienteSelect, String tecnicoSelect, String filtroTipo,
            String clienteGroup, String tecnicoGroup, Instant inicio, Instant fim, UUID clienteId, UUID tecnicoSub) {
        var params = new MapSqlParameterSource().addValue("inicio", inicio.atOffset(ZoneOffset.UTC))
                .addValue("fim", fim.atOffset(ZoneOffset.UTC))
                .addValue("clienteId", clienteId).addValue("tecnicoSub", tecnicoSub);
        String agrupamento = ("null".equals(clienteGroup) ? "" : clienteGroup + ", ")
                + ("null".equals(tecnicoGroup) ? "" : tecnicoGroup + ", ") + "tipo";
        String sql = SELECT_AGREGADO.formatted(clienteSelect, tecnicoSelect, filtroTipo, agrupamento, agrupamento);
        return jdbc.query(sql, params, (rs, n) -> new LinhaAgregada(
                rs.getObject("cliente_id", UUID.class), rs.getObject("tecnico_sub", UUID.class), rs.getString("tipo"),
                rs.getLong("cumpridos"), rs.getLong("violados")));
    }

    record LinhaAgregada(UUID clienteId, UUID tecnicoSub, String tipo, long cumpridos, long violados) {
    }
}
