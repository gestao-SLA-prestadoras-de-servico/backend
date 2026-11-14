package com.gestaosla.backend.sla.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gestaosla.backend.sla.api.TipoRelogio;

interface RelogioSlaRepository extends JpaRepository<RelogioSla, UUID> {

    Optional<RelogioSla> findByChamadoIdAndTipoAndAtivoTrue(UUID chamadoId, TipoRelogio tipo);

    List<RelogioSla> findByChamadoIdAndAtivoTrue(UUID chamadoId);

    List<RelogioSla> findByChamadoId(UUID chamadoId);
}
