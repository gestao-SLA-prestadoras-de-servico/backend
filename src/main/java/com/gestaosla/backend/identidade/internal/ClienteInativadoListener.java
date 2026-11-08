package com.gestaosla.backend.identidade.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gestaosla.backend.cadastro.api.ClienteInativado;
import com.gestaosla.backend.shared.api.Ator;

import lombok.RequiredArgsConstructor;

/**
 * Depois do commit, inativa os usuários do cliente inativado. A falha em um usuário não desfaz a inativação do cliente
 * nem interrompe os demais: fica no log, e o gestor pode inativá-lo pelo endpoint.
 */
@Component
@RequiredArgsConstructor
class ClienteInativadoListener {

    private static final Logger log = LoggerFactory.getLogger(ClienteInativadoListener.class);

    private final UsuarioRepository usuarios;
    private final InativacaoUsuarioService inativacao;

    @TransactionalEventListener
    void aoInativarCliente(ClienteInativado evento) {
        for (Usuario usuario : usuarios.findByClienteIdAndAtivoTrue(evento.clienteId())) {
            try {
                inativacao.inativar(usuario.getId(), null, Ator.SISTEMA);
            } catch (RuntimeException falha) {
                log.error("Usuário {} do cliente inativado {} continua ativo.", usuario.getId(), evento.clienteId(), falha);
            }
        }
    }
}
