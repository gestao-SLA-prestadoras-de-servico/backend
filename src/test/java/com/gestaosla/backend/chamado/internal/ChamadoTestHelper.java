package com.gestaosla.backend.chamado.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.sla.api.Violacao;

/**
 * Aciona as peças internas do chamado a partir de {@code integration/} (o CI só roda testes nesse pacote e em
 * {@code unit/}), cada operação na sua transação, como fará a operação de negócio que as usar.
 */
public final class ChamadoTestHelper {

    private final ChamadoRepository chamados;
    private final TransicaoChamado transicao;
    private final NotificadorViolacao notificador;
    private final AtendimentoService atendimentos;
    private final PreferenciaNotificacaoService preferencias;
    private final TransactionTemplate transacao;
    private final RotinaViolacao rotinaViolacao;
    private final RotinaValidacaoAutomatica validacaoAutomatica;

    public ChamadoTestHelper(ApplicationContext contexto) {
        chamados = contexto.getBean(ChamadoRepository.class);
        transicao = contexto.getBean(TransicaoChamado.class);
        notificador = contexto.getBean(NotificadorViolacao.class);
        atendimentos = contexto.getBean(AtendimentoService.class);
        preferencias = contexto.getBean(PreferenciaNotificacaoService.class);
        transacao = contexto.getBean(TransactionTemplate.class);
        rotinaViolacao = contexto.getBean(RotinaViolacao.class);
        validacaoAutomatica = contexto.getBean(RotinaValidacaoAutomatica.class);
    }

    public void transicionar(UUID chamadoId, EstadoChamado destino, Ator ator) {
        transicionar(chamadoId, destino, ator, () -> { });
    }

    /** Lê o chamado, roda {@code entreLeituraEGravacao} (ex.: outra transação que altera o mesmo chamado) e transiciona. */
    public void transicionar(UUID chamadoId, EstadoChamado destino, Ator ator, Runnable entreLeituraEGravacao) {
        transacao.executeWithoutResult(s -> {
            Chamado chamado = chamados.findById(chamadoId).orElseThrow();
            entreLeituraEGravacao.run();
            transicao.transicionar(chamado, destino, ator);
        });
    }

    public void notificar(UUID chamadoId, List<Violacao> violacoes) {
        transacao.executeWithoutResult(s -> notificador.notificar(chamados.findById(chamadoId).orElseThrow(), violacoes));
    }

    public static UUID eventoId(Violacao violacao) {
        return NotificadorViolacao.eventoId(violacao);
    }

    public UUID criarAtendimentoPendente(UUID chamadoId, UUID tecnicoSub) {
        return transacao.execute(s -> atendimentos.criarPendente(chamadoId, tecnicoSub).getId());
    }

    public void interromperCorrente(UUID chamadoId, Instant instante) {
        transacao.executeWithoutResult(s -> atendimentos.interromperCorrente(chamadoId, instante));
    }

    public void criarPendenteSemTransacao(UUID chamadoId, UUID tecnicoSub) {
        atendimentos.criarPendente(chamadoId, tecnicoSub);
    }

    public List<UUID> gestoresComPreferencia(UUID chamadoId) {
        return preferencias.gestoresComPreferencia(chamadoId);
    }

    public List<EstadoAtendimento> estadosDosAtendimentos(UUID chamadoId) {
        return atendimentos.doChamado(chamadoId).stream().map(Atendimento::getEstado).toList();
    }

    /** Uma passada da rotina de violação, com o lote informado (o agendamento usa 100). */
    public void rodarRotinaViolacao(int tamanhoLote) {
        rotinaViolacao.executar(tamanhoLote);
    }

    /** Uma passada da validação automática, com o lote informado (o agendamento usa 100). */
    public void rodarValidacaoAutomatica(int tamanhoLote) {
        validacaoAutomatica.executar(tamanhoLote);
    }

    public static UUID eventoIdAvisoEncerramento(UUID chamadoId, Instant resolvidoEm) {
        return RotinaValidacaoAutomatica.eventoId(chamadoId, resolvidoEm);
    }
}
