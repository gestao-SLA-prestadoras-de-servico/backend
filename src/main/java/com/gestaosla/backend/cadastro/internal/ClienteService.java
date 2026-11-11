package com.gestaosla.backend.cadastro.internal;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.cadastro.api.ClienteConsulta;
import com.gestaosla.backend.cadastro.api.ClienteInativado;
import com.gestaosla.backend.shared.api.Paginas;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class ClienteService implements ClienteConsulta {

    private final ClienteRepository clientes;
    private final ApplicationEventPublisher eventos;

    @Transactional
    Cliente criar(DadosCliente dados) {
        return clientes.save(new Cliente(dados.razaoSocial(), dados.emailContato(), dados.endereco()));
    }

    /**
     * {@code version} é a que o gestor viu ao abrir o cliente: se outro gestor salvou depois, 409 em vez de sobrescrever
     * (RN-117). Cliente inativo pode ser editado (ex.: corrigir o e-mail do histórico).
     */
    @Transactional
    Cliente editar(UUID id, long version, DadosCliente dados) {
        Cliente cliente = buscar(id);
        if (cliente.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(Cliente.class, id);
        }
        cliente.alterar(dados.razaoSocial(), dados.emailContato(), dados.endereco());
        return cliente;
    }

    @Transactional(readOnly = true)
    Page<Cliente> listar(String razaoSocial, Boolean ativo, Pageable pagina) {
        Specification<Cliente> filtro = Specification.unrestricted();
        if (razaoSocial != null && !razaoSocial.isBlank()) {
            filtro = filtro.and((c, q, cb) -> cb.like(cb.lower(c.get("razaoSocial")), "%" + razaoSocial.toLowerCase() + "%"));
        }
        if (ativo != null) {
            filtro = filtro.and((c, q, cb) -> cb.equal(c.get("ativo"), ativo));
        }
        return clientes.findAll(filtro, Paginas.comDesempate(pagina));
    }

    // Idempotente: inativar de novo não repete o evento.
    @Transactional
    void inativar(UUID id) {
        if (buscar(id).inativar()) {
            eventos.publishEvent(new ClienteInativado(id));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String emailContato(UUID clienteId) {
        return buscar(clienteId).getEmailContato();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean estaAtivo(UUID clienteId) {
        return buscar(clienteId).isAtivo();
    }

    private Cliente buscar(UUID id) {
        return clientes.findById(id).orElseThrow(RecursoNaoEncontradoException::new);
    }
}
