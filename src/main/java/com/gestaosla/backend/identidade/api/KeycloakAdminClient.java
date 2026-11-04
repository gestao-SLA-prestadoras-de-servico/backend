package com.gestaosla.backend.identidade.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Cliente da Admin API do Keycloak (RN-127, RN-131, RN-137).
 * Centraliza criação, alteração de papel, envio de e-mail de senha, desabilitação, reabilitação e remoção de contas.
 */
public interface KeycloakAdminClient {

    /**
     * Cria a conta do usuário no Keycloak (RN-127).
     * Não pode ser repetida às cegas: se houver timeout, busca por username antes de tentar de novo.
     * Conta com o mesmo username que ainda não tem registro local (cadastro anterior que não terminou) é
     * reaproveitada: recebe os dados novos, é habilitada e o {@code sub} dela é devolvido.
     *
     * @param usuario dados do usuário a criar
     * @return sub (UUID do usuário no Keycloak)
     * @throws UsuarioJaExisteException se o username for de um usuário com registro local, ou o e-mail for de outra
     *                                  conta (409)
     * @throws IdpIndisponivelException se o Keycloak estiver inacessível ou estourar o timeout (503)
     */
    UUID criarUsuario(NovoUsuarioKeycloak usuario);

    /**
     * Atribui papel de realm ao usuário (RN-127, RN-131). Operação idempotente com retry.
     *
     * @param userId sub do usuário
     * @param papel  papel de realm
     * @throws IdpIndisponivelException se o Keycloak estiver inacessível ou estourar o timeout (503)
     */
    void atribuirPapel(UUID userId, Papel papel);

    /**
     * Dispara o e-mail do Keycloak para o usuário definir sua senha (RN-138).
     * Operação não idempotente: não repete cegamente.
     *
     * @param userId sub do usuário
     * @throws IdpIndisponivelException se o Keycloak estiver inacessível ou estourar o timeout (503)
     */
    void enviarDefinicaoSenha(UUID userId);

    /**
     * Desabilita a conta do usuário no Keycloak (RN-132). Operação idempotente com retry.
     *
     * @param userId sub do usuário
     * @throws IdpIndisponivelException se o Keycloak estiver inacessível ou estourar o timeout (503)
     */
    void desabilitar(UUID userId);

    /**
     * Reabilita a conta do usuário no Keycloak (RN-136). Operação idempotente com retry.
     *
     * @param userId sub do usuário
     * @throws IdpIndisponivelException se o Keycloak estiver inacessível ou estourar o timeout (503)
     */
    void reabilitar(UUID userId);

    /**
     * Remove a conta do usuário no Keycloak (compensação RN-136). Operação idempotente com retry.
     *
     * @param userId sub do usuário
     * @throws IdpIndisponivelException se o Keycloak estiver inacessível ou estourar o timeout (503)
     */
    void remover(UUID userId);

    /**
     * Busca usuário pelo username exato. Operação idempotente com retry.
     *
     * @param username username do usuário
     * @return usuário encontrado ou Optional.empty()
     * @throws IdpIndisponivelException se o Keycloak estiver inacessível ou estourar o timeout (503)
     */
    Optional<UsuarioKeycloak> buscarPorUsername(String username);
}
