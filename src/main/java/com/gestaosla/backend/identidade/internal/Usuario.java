package com.gestaosla.backend.identidade.internal;

import java.util.UUID;

import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.EntidadeBase;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Registro local do usuário; o {@code sub} do Keycloak o identifica em todo o sistema (RN-129). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Usuario extends EntidadeBase {

    private UUID sub;

    private String nome;

    private String email;

    @Enumerated(EnumType.STRING)
    private Papel perfil;

    private boolean ativo;

    // Empresa do usuário de perfil CLIENTE (RN-130); nula para técnico e gestor.
    private UUID clienteId;

    @Version
    private long version;

    Usuario(UUID sub, String nome, String email, Papel perfil, UUID clienteId) {
        this.sub = sub;
        this.nome = nome;
        this.email = email;
        this.perfil = perfil;
        this.clienteId = clienteId;
        this.ativo = true;
    }
}
