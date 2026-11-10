package com.gestaosla.backend.cadastro.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface VersaoPoliticaRepository extends JpaRepository<VersaoPolitica, UUID> {

    Optional<VersaoPolitica> findFirstByPoliticaIdOrderByNumeroDesc(UUID politicaId);

    Page<VersaoPolitica> findByPoliticaId(UUID politicaId, Pageable pagina);

    @Query("""
            select v from VersaoPolitica v where v.politicaId in :politicas
            and v.numero = (select max(v2.numero) from VersaoPolitica v2 where v2.politicaId = v.politicaId)""")
    List<VersaoPolitica> atuais(Collection<UUID> politicas);
}
