package com.gestaosla.backend.evidencia.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

interface EvidenciaRepository extends JpaRepository<Evidencia, UUID> {

    interface CandidataExpurgo {
        UUID getId();

        UUID getChamadoId();
    }

    @Query(value = """
            select id, chamado_id as "chamadoId" from evidencia
            where estado <> 'EXPURGADA' and (cast(:cursor as uuid) is null or id > :cursor)
            order by id
            """, nativeQuery = true)
    List<CandidataExpurgo> candidatasExpurgo(UUID cursor, Pageable pagina);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Evidencia e where e.id = :id")
    Optional<Evidencia> buscarComTrava(UUID id);

    interface Contagem {
        UUID getAtendimentoId();

        long getTotal();
    }

    List<Evidencia> findByChamadoIdOrderByEnviadaEmAscIdAsc(UUID chamadoId);

    @Query("select e.atendimentoId as atendimentoId, count(e) as total from Evidencia e where e.chamadoId = :chamadoId group by e.atendimentoId")
    List<Contagem> contarPorAtendimento(UUID chamadoId);

    // UPDATEs condicionais: a reivindicação comita antes da geração, e a conclusão integra a transação que trava a linha.
    // O JPQL não passa pelo @Version nem pelo @UpdateTimestamp: version e updatedAt são atualizados aqui.

    /** Reivindica o processamento (RN-86). PROCESSANDO entra para recuperar um processamento interrompido por queda. */
    @Modifying
    @Transactional
    @Query("""
            update Evidencia e set e.estado = com.gestaosla.backend.evidencia.internal.EstadoEvidencia.PROCESSANDO,
                e.version = e.version + 1, e.updatedAt = :agora
            where e.id = :id and e.estado in (com.gestaosla.backend.evidencia.internal.EstadoEvidencia.RECEBIDA,
                com.gestaosla.backend.evidencia.internal.EstadoEvidencia.PROCESSANDO)""")
    int reivindicar(UUID id, Instant agora);

    @Modifying
    @Transactional
    @Query("""
            update Evidencia e set e.estado = com.gestaosla.backend.evidencia.internal.EstadoEvidencia.DISPONIVEL,
                e.chaveMiniatura = :miniatura, e.chaveVisualizacao = :visualizacao, e.version = e.version + 1, e.updatedAt = :agora
            where e.id = :id and e.estado = com.gestaosla.backend.evidencia.internal.EstadoEvidencia.PROCESSANDO""")
    int concluir(UUID id, String miniatura, String visualizacao, Instant agora);

    @Modifying
    @Transactional
    @Query("""
            update Evidencia e set e.estado = com.gestaosla.backend.evidencia.internal.EstadoEvidencia.FALHA,
                e.version = e.version + 1, e.updatedAt = :agora
            where e.id = :id and e.estado in (com.gestaosla.backend.evidencia.internal.EstadoEvidencia.RECEBIDA,
                com.gestaosla.backend.evidencia.internal.EstadoEvidencia.PROCESSANDO)""")
    int falhar(UUID id, Instant agora);

    /** Reprocessamento manual (RF-29): só FALHA volta a RECEBIDA; 0 linhas = outro estado ou outro gestor chegou antes. */
    @Modifying
    @Transactional
    @Query("""
            update Evidencia e set e.estado = com.gestaosla.backend.evidencia.internal.EstadoEvidencia.RECEBIDA,
                e.version = e.version + 1, e.updatedAt = :agora
            where e.id = :id and e.estado = com.gestaosla.backend.evidencia.internal.EstadoEvidencia.FALHA""")
    int reprocessar(UUID id, Instant agora);
}
