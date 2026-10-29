package com.gestaosla.backend.identidade.api;

/**
 * Dados para criação de uma nova conta de usuário no Keycloak (RN-127).
 */
public record NovoUsuarioKeycloak(
        String username,
        String email,
        String firstName,
        String lastName
) {

    public NovoUsuarioKeycloak {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username não pode ser vazio.");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email não pode ser vazio.");
        }
    }

    public NovoUsuarioKeycloak(String username, String email, String nomeCompleto) {
        this(username, email, extrairPrimeiroNome(nomeCompleto), extrairSobrenome(nomeCompleto));
    }

    private static String extrairPrimeiroNome(String nomeCompleto) {
        if (nomeCompleto == null || nomeCompleto.isBlank()) {
            return "";
        }
        int pos = nomeCompleto.trim().indexOf(' ');
        return pos > 0 ? nomeCompleto.trim().substring(0, pos) : nomeCompleto.trim();
    }

    private static String extrairSobrenome(String nomeCompleto) {
        if (nomeCompleto == null || nomeCompleto.isBlank()) {
            return "";
        }
        int pos = nomeCompleto.trim().indexOf(' ');
        return pos > 0 ? nomeCompleto.trim().substring(pos + 1).trim() : "";
    }
}
