package com.gestaosla.backend.sla.api;

import com.gestaosla.backend.cadastro.api.Prazos;

/**
 * Os três relógios de SLA (RN-23 a RN-25). Gravado como texto. Na mensagem de notificação, {@code CAMPO} sai como
 * {@code ATENDIMENTO_CAMPO} ({@code outbox.api.EventoNotificacao.Relogio}).
 */
public enum TipoRelogio {

    /** Cumprido pelo primeiro posicionamento do gestor (RN-23). */
    RESPOSTA,
    /** Cumprido pelo primeiro registro de atendimento presencial do técnico (RN-24). */
    CAMPO,
    /** Cumprido quando o chamado passa a RESOLVIDO (RN-25). */
    RESOLUCAO;

    /** Prazo deste relógio, em minutos de janela, dentre os prazos de uma prioridade. */
    public int prazoEm(Prazos prazos) {
        return switch (this) {
            case RESPOSTA -> prazos.resposta();
            case CAMPO -> prazos.campo();
            case RESOLUCAO -> prazos.resolucao();
        };
    }
}
