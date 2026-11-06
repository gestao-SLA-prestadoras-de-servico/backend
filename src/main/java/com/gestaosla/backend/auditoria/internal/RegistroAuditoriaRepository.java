package com.gestaosla.backend.auditoria.internal;

import java.util.UUID;

import org.springframework.data.repository.Repository;

// Só inserção: sem save de alteração, sem delete (RN-124). O expurgo dos vencidos é da Rotina de Retenção (RN-107).
interface RegistroAuditoriaRepository extends Repository<RegistroAuditoria, UUID> {

    RegistroAuditoria save(RegistroAuditoria registro);
}
