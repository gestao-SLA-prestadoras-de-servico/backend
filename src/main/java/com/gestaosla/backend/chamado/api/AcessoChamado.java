package com.gestaosla.backend.chamado.api;

import java.util.UUID;

import com.gestaosla.backend.identidade.api.UsuarioCorrente;

/**
 * Quem pode ver um chamado: o gestor, todos; o cliente, os da própria empresa; o técnico, os atribuídos a ele no
 * momento (RN-126). Outros módulos (evidencia) perguntam aqui antes de servir algo do chamado.
 */
public interface AcessoChamado {

    boolean podeAcessar(UsuarioCorrente usuario, UUID chamadoId);
}
