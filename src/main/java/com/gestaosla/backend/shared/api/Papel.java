package com.gestaosla.backend.shared.api;

/** Papéis de realm do Keycloak. A autorização usa sempre o do token (RN-130). No shared porque auditoria e identidade usam. */
public enum Papel {
    CLIENTE, TECNICO, GESTOR
}
