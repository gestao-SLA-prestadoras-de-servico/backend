package com.gestaosla.backend.shared.api;

/**
 * Serviço externo fora do ar, lento além do timeout ou respondendo 5xx. Sai como 503 {@code SERVICO_INDISPONIVEL};
 * a causa real só vai para o log.
 */
public class ServicoIndisponivelException extends RuntimeException {

    public ServicoIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
