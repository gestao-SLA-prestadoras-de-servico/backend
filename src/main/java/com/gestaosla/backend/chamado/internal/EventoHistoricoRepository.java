package com.gestaosla.backend.chamado.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EventoHistoricoRepository extends JpaRepository<EventoHistorico, UUID> {

    // Desempate pelo id (UUID v7, crescente): dois eventos no mesmo instante saem na ordem de gravação.
    List<EventoHistorico> findByChamadoIdOrderByOcorridoEmAscIdAsc(UUID chamadoId);
}
