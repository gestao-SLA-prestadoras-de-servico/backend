package com.gestaosla.backend.chamado.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

import lombok.RequiredArgsConstructor;

/**
 * Preferência de notificação de conclusão, individual por gestor e por chamado (RF-56, RN-36, RN-40). O gestor vem
 * sempre do token: um gestor nunca altera a preferência de outro. Só audita quando algo muda (RN-121).
 */
@Service
@RequiredArgsConstructor
class PreferenciaNotificacaoService {

    private final ChamadoRepository chamados;
    private final PreferenciaNotificacaoRepository preferencias;
    private final AuditoriaService auditoria;

    @Transactional
    void ativar(UUID chamadoId, Ator gestor) {
        exigirChamado(chamadoId);
        if (preferencias.existsByChamadoIdAndGestorSub(chamadoId, gestor.sub())) {
            return;
        }
        // A UNIQUE (chamado_id, gestor_sub) barra a duplicata de dois cliques simultâneos.
        preferencias.save(new PreferenciaNotificacao(chamadoId, gestor.sub()));
        auditar(chamadoId, gestor, true);
    }

    @Transactional
    void desativar(UUID chamadoId, Ator gestor) {
        exigirChamado(chamadoId);
        preferencias.findByChamadoIdAndGestorSub(chamadoId, gestor.sub()).ifPresent(p -> {
            preferencias.delete(p);
            auditar(chamadoId, gestor, false);
        });
    }

    @Transactional(readOnly = true)
    boolean ativa(UUID chamadoId, UUID gestorSub) {
        return preferencias.existsByChamadoIdAndGestorSub(chamadoId, gestorSub);
    }

    /** Gestores a avisar quando o técnico concluir o atendimento (RN-36). */
    @Transactional(readOnly = true)
    List<UUID> gestoresComPreferencia(UUID chamadoId) {
        return preferencias.findByChamadoId(chamadoId).stream().map(PreferenciaNotificacao::getGestorSub).toList();
    }

    private void exigirChamado(UUID chamadoId) {
        if (!chamados.existsById(chamadoId)) {
            throw new RecursoNaoEncontradoException();
        }
    }

    private void auditar(UUID chamadoId, Ator gestor, boolean ativa) {
        auditoria.registrar(gestor, OperacaoAuditada.ALTERACAO_PREFERENCIA_NOTIFICACAO, EntidadeAuditada.PREFERENCIA_NOTIFICACAO,
                chamadoId, chamadoId, Map.of("ativa", ativa));
    }
}
