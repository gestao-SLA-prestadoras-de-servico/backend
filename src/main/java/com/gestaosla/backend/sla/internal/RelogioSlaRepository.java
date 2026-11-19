package com.gestaosla.backend.sla.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.gestaosla.backend.sla.api.TipoRelogio;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

interface RelogioSlaRepository extends JpaRepository<RelogioSla, UUID> {

    // Rotina de violação ([chamado - 14]): índice relogio_sla_correndo_idx. Lock timeout -2 = SKIP LOCKED no Hibernate.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select r from RelogioSla r
            where r.ativo and r.cumpridoEm is null and r.violadoEm is null and r.venceEm < :agora
              and (r.venceEm > :aposVenceEm or (r.venceEm = :aposVenceEm and r.id > :aposId))
            order by r.venceEm, r.id""")
    List<RelogioSla> travarVencidos(Instant agora, Instant aposVenceEm, UUID aposId, Limit limite);

    Optional<RelogioSla> findByChamadoIdAndTipoAndAtivoTrue(UUID chamadoId, TipoRelogio tipo);

    List<RelogioSla> findByChamadoIdAndAtivoTrue(UUID chamadoId);

    List<RelogioSla> findByChamadoId(UUID chamadoId);
}
