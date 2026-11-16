package com.gestaosla.backend.outbox.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.gestaosla.backend.cadastro.api.ClienteConsulta;
import com.gestaosla.backend.identidade.api.UsuarioConsulta;
import com.gestaosla.backend.shared.api.Papel;

import lombok.RequiredArgsConstructor;

/**
 * Única origem dos destinatários das notificações (RN-41, RN-53): sub de técnico ou gestor no canal interno, e-mail de
 * contato do cliente no e-mail. O perfil vem da cópia local: é filtro de destinatário, não autorização (RN-130).
 */
@Component
@RequiredArgsConstructor
public class MontadorDestinatarios {

    private final UsuarioConsulta usuarios;
    private final ClienteConsulta clientes;

    /**
     * @throws IllegalArgumentException se a lista vier vazia ou algum sub não for de técnico ou gestor cadastrado
     */
    public DestinatariosInternos internos(Collection<UUID> subs) {
        List<UUID> distintos = subs.stream().distinct().toList();
        if (distintos.isEmpty()) {
            throw new IllegalArgumentException("Notificação interna sem destinatário.");
        }
        Map<UUID, Papel> perfis = usuarios.perfis(distintos);
        List<UUID> recusados = distintos.stream()
                .filter(sub -> perfis.get(sub) != Papel.TECNICO && perfis.get(sub) != Papel.GESTOR).toList();
        if (!recusados.isEmpty()) {
            throw new IllegalArgumentException(
                    "Notificação interna só vai para técnico ou gestor (RN-41); recusados: " + recusados);
        }
        return new DestinatariosInternos(distintos);
    }

    /** E-mail de contato do cliente (RN-08); cliente inexistente: {@code RecursoNaoEncontradoException}. */
    public DestinatarioEmail emailDoCliente(UUID clienteId) {
        return new DestinatarioEmail(clientes.emailContato(clienteId));
    }
}
