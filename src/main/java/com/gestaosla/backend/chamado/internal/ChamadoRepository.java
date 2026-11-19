package com.gestaosla.backend.chamado.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.gestaosla.backend.chamado.api.EstadoChamado;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

interface ChamadoRepository extends JpaRepository<Chamado, UUID>, JpaSpecificationExecutor<Chamado> {

    long countByTecnicoSubAndEstadoIn(UUID tecnicoSub, Collection<EstadoChamado> estados);

    List<Chamado> findByTecnicoSubAndEstadoIn(UUID tecnicoSub, Collection<EstadoChamado> estados);

    // Conclusão ([chamado - 11]): a conferência das evidências e a mudança de estado acontecem com o chamado travado.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chamado c where c.id = :id")
    Optional<Chamado> buscarParaAlterar(UUID id);

    // Validação automática ([chamado - 15]): resolvidos em ordem, depois do cursor. Lock timeout -2 = SKIP LOCKED no
    // Hibernate: o chamado que o cliente está validando ou reabrindo fica para a próxima passada.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select c from Chamado c
            where c.estado = com.gestaosla.backend.chamado.api.EstadoChamado.RESOLVIDO
              and (c.resolvidoEm > :aposResolvidoEm or (c.resolvidoEm = :aposResolvidoEm and c.id > :aposId))
            order by c.resolvidoEm, c.id""")
    List<Chamado> travarResolvidos(Instant aposResolvidoEm, UUID aposId, Limit limite);
}
