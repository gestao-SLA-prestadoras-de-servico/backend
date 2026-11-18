package com.gestaosla.backend.chamado.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gestaosla.backend.chamado.api.EstadoChamado;

interface ChamadoRepository extends JpaRepository<Chamado, UUID>, JpaSpecificationExecutor<Chamado> {

    long countByTecnicoSubAndEstadoIn(UUID tecnicoSub, Collection<EstadoChamado> estados);

    List<Chamado> findByTecnicoSubAndEstadoIn(UUID tecnicoSub, Collection<EstadoChamado> estados);
}
