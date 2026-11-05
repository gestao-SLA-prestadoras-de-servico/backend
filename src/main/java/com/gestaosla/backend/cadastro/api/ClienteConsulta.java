package com.gestaosla.backend.cadastro.api;

import java.util.UUID;

import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

/** O que outros módulos podem saber de um cliente. Cliente inexistente: {@link RecursoNaoEncontradoException}. */
public interface ClienteConsulta {

    /** Destino de todos os e-mails do cliente (RN-08). */
    String emailContato(UUID clienteId);

    /** Cliente inativo não aceita novo contrato, novo usuário nem novo chamado. */
    boolean estaAtivo(UUID clienteId);
}
