package com.gestaosla.backend.chamado.internal;

import java.util.UUID;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.sla.api.ApuracaoSla;

import lombok.RequiredArgsConstructor;

/**
 * Validação do chamado resolvido (RF-20): ENCERRADO, com o {@code estado_final_em} e a apuração de SLA congelada (RN-68).
 * O encerramento serve também à validação automática ([chamado - 15]).
 */
@Service
@RequiredArgsConstructor
class ValidacaoService {

    private final ChamadoRepository chamados;
    private final TransicaoChamado transicao;
    private final ApuracaoSla apuracao;
    private final NotificadorViolacao notificador;

    /** @param version a que o cliente leu: se o chamado mudou depois, 409 em vez de validar (RN-117) */
    @Transactional
    void validar(UUID chamadoId, long version, UsuarioCorrente cliente) {
        encerrar(daEmpresa(chamados, chamadoId, version, cliente), Ator.usuario(cliente.sub(), cliente.papel()));
    }

    /** RESOLVIDO → ENCERRADO; fora de RESOLVIDO, {@code TRANSICAO_INVALIDA} (RN-10). */
    @Transactional(propagation = Propagation.MANDATORY)
    void encerrar(Chamado chamado, Ator ator) {
        transicao.transicionar(chamado, EstadoChamado.ENCERRADO, ator);
        notificador.notificar(chamado, apuracao.congelarApuracao(chamado.getId(), chamado.getClienteId(),
                chamado.prioridadeEfetiva(), chamado.getTecnicoSub()));
    }

    /**
     * Chamado da empresa do cliente, na versão que ele leu. De outra empresa, inexistente (RN-91, RN-126); versão
     * diferente, conflito (409).
     */
    static Chamado daEmpresa(ChamadoRepository chamados, UUID chamadoId, long version, UsuarioCorrente cliente) {
        Chamado chamado = chamados.findById(chamadoId).filter(c -> c.getClienteId().equals(cliente.empresaClienteId()))
                .orElseThrow(RecursoNaoEncontradoException::new);
        if (chamado.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(Chamado.class, chamadoId);
        }
        return chamado;
    }
}
