package com.gestaosla.backend;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

public final class EvidenciaRetencaoTeste {

    private EvidenciaRetencaoTeste() {
    }

    public record Registro(UUID id, UUID chamado, UUID atendimento, String original, String miniatura,
            String visualizacao, String prefixo) {
    }

    public static Registro criar(JdbcTemplate jdbc, String estadoChamado, Instant dataFinal, String estadoEvidencia,
            Instant envio, boolean derivados) {
        UUID chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), estadoChamado, UUID.randomUUID());
        if (dataFinal != null) {
            jdbc.update("update chamado set estado_final_em = ? where id = ?", dataFinal.atOffset(ZoneOffset.UTC), chamado);
        }
        UUID atendimento = UUID.randomUUID();
        jdbc.update("""
                insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version)
                values (?, now(), now(), ?, ?, 'CONCLUIDO', 0)""", atendimento, chamado, UUID.randomUUID());
        UUID id = UUID.randomUUID();
        String original = ArmazenamentoTestHelper.original(chamado, id, "png");
        String miniatura = ArmazenamentoTestHelper.miniatura(chamado, id);
        String visualizacao = ArmazenamentoTestHelper.visualizacao(chamado, id);
        jdbc.update("""
                insert into evidencia (id, created_at, updated_at, atendimento_id, chamado_id, tipo_conteudo, tamanho,
                                       hash_sha256, chave_original, chave_miniatura, chave_visualizacao, estado,
                                       enviada_em, version)
                values (?, now(), now(), ?, ?, 'image/png', 1, ?, ?, ?, ?, ?, ?, 0)""", id, atendimento, chamado,
                "a".repeat(64), original, derivados ? miniatura : null, derivados ? visualizacao : null,
                estadoEvidencia, envio.atOffset(ZoneOffset.UTC));
        return new Registro(id, chamado, atendimento, original, miniatura, visualizacao,
                ArmazenamentoTestHelper.prefixo(chamado, id));
    }
}
