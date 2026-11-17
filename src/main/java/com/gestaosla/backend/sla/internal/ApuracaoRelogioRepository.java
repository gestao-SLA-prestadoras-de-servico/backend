package com.gestaosla.backend.sla.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ApuracaoRelogioRepository extends JpaRepository<ApuracaoRelogio, UUID> {

    boolean existsByChamadoId(UUID chamadoId);

    List<ApuracaoRelogio> findByChamadoId(UUID chamadoId);
}
