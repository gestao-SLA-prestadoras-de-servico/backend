package com.gestaosla.backend.chamado.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.identidade.api.UsuarioConsulta;
import com.gestaosla.backend.outbox.api.DestinatariosInternos;
import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.MontadorDestinatarios;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.sla.api.Violacao;

import lombok.RequiredArgsConstructor;

/**
 * Único lugar que grava aviso de violação de SLA na outbox (RF-58). Recebe as violações que o sla devolveu e grava um
 * evento por relógio, para todos os gestores ativos e o técnico atribuído, se houver (RN-39), na transação de quem
 * chama (RN-52).
 */
@Component
@RequiredArgsConstructor
class NotificadorViolacao {

    private final UsuarioConsulta usuarios;
    private final MontadorDestinatarios destinatarios;
    private final OutboxService outbox;

    @Transactional(propagation = Propagation.MANDATORY)
    void notificar(Chamado chamado, List<Violacao> violacoes) {
        if (violacoes.isEmpty()) {
            return;
        }
        List<UUID> subs = new ArrayList<>(usuarios.listarGestoresAtivos());
        if (chamado.getTecnicoSub() != null) {
            subs.add(chamado.getTecnicoSub());
        }
        DestinatariosInternos para = destinatarios.internos(subs);
        EventoNotificacao.Chamado ref = new EventoNotificacao.Chamado(chamado.getId(), chamado.getNumero());
        for (Violacao violacao : violacoes) {
            outbox.registrar(new EventoNotificacao.Violacao(ref, chamado.getTitulo(), para, relogio(violacao)), eventoId(violacao));
        }
    }

    /**
     * Determinístico (RN-54): a mesma violação nunca gera dois avisos, e a de um ciclo novo, com outro marco inicial,
     * gera um aviso novo.
     */
    static UUID eventoId(Violacao violacao) {
        String chave = "violacao:" + violacao.chamadoId() + ":" + violacao.tipo() + ":" + violacao.marcoInicial();
        return UUID.nameUUIDFromBytes(chave.getBytes(StandardCharsets.UTF_8));
    }

    private static EventoNotificacao.Relogio relogio(Violacao violacao) {
        return switch (violacao.tipo()) {
            case RESPOSTA -> EventoNotificacao.Relogio.RESPOSTA;
            case CAMPO -> EventoNotificacao.Relogio.ATENDIMENTO_CAMPO;
            case RESOLUCAO -> EventoNotificacao.Relogio.RESOLUCAO;
        };
    }
}
