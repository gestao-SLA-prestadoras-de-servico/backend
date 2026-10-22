package com.gestaosla.backend.shared.api;

/**
 * Recurso inexistente <b>ou</b> fora do isolamento do usuário (RN-126). Sai como 404 com a mesma
 * mensagem nos dois casos, para não revelar que o recurso de outro cliente existe. Nunca usar 403 nesse caso.
 */
public class RecursoNaoEncontradoException extends RuntimeException {

    public RecursoNaoEncontradoException() {
        super("Recurso não encontrado.");
    }
}
