package com.gestaosla.backend.identidade.api;

/** Papéis de realm do Keycloak. A autorização usa sempre o do token (RN-130). */
public enum Papel {
    CLIENTE, TECNICO, GESTOR
}
