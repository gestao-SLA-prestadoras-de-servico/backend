package com.gestaosla.backend.cadastro.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface PoliticaSlaRepository extends JpaRepository<PoliticaSla, UUID> {

    boolean existsByNome(String nome);

    // Duas versões novas ao mesmo tempo: a segunda espera a primeira e numera depois dela.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PoliticaSla p where p.id = :id")
    Optional<PoliticaSla> buscarParaVersionar(UUID id);
}
