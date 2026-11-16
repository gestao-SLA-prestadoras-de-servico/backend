package com.gestaosla.backend.chamado.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PreferenciaNotificacaoRepository extends JpaRepository<PreferenciaNotificacao, UUID> {

    Optional<PreferenciaNotificacao> findByChamadoIdAndGestorSub(UUID chamadoId, UUID gestorSub);

    boolean existsByChamadoIdAndGestorSub(UUID chamadoId, UUID gestorSub);

    List<PreferenciaNotificacao> findByChamadoId(UUID chamadoId);
}
