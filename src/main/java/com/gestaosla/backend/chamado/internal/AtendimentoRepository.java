package com.gestaosla.backend.chamado.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gestaosla.backend.chamado.api.EstadoAtendimento;

interface AtendimentoRepository extends JpaRepository<Atendimento, UUID> {

    Optional<Atendimento> findByChamadoIdAndEstadoIn(UUID chamadoId, Collection<EstadoAtendimento> estados);

    List<Atendimento> findByChamadoIdOrderByCreatedAtAscIdAsc(UUID chamadoId);
}
