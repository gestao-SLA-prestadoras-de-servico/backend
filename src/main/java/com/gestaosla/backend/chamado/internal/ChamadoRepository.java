package com.gestaosla.backend.chamado.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gestaosla.backend.chamado.api.EstadoChamado;

import jakarta.persistence.LockModeType;

interface ChamadoRepository extends JpaRepository<Chamado, UUID>, JpaSpecificationExecutor<Chamado> {

    long countByTecnicoSubAndEstadoIn(UUID tecnicoSub, Collection<EstadoChamado> estados);

    List<Chamado> findByTecnicoSubAndEstadoIn(UUID tecnicoSub, Collection<EstadoChamado> estados);

    // Conclusão ([chamado - 11]): a conferência das evidências e a mudança de estado acontecem com o chamado travado.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Chamado c where c.id = :id")
    Optional<Chamado> buscarParaAlterar(UUID id);
}
