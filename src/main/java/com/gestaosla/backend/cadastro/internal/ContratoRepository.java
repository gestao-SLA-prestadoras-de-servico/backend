package com.gestaosla.backend.cadastro.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface ContratoRepository extends JpaRepository<Contrato, UUID>, JpaSpecificationExecutor<Contrato> {

    boolean existsByCodigo(String codigo);
}
