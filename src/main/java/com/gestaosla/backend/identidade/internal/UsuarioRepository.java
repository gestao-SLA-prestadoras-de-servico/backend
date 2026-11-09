package com.gestaosla.backend.identidade.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gestaosla.backend.shared.api.Papel;

import jakarta.persistence.LockModeType;

interface UsuarioRepository extends JpaRepository<Usuario, UUID>, JpaSpecificationExecutor<Usuario> {

    Optional<Usuario> findBySub(UUID sub);

    boolean existsBySub(UUID sub);

    boolean existsByEmailIgnoreCase(String email);

    List<Usuario> findByPerfilAndAtivoTrue(Papel perfil);

    List<Usuario> findBySubIn(Collection<UUID> subs);

    List<Usuario> findByClienteIdAndAtivoTrue(UUID clienteId);

    long countByPerfilAndAtivoTrue(Papel perfil);

    // Ordem fixa: duas transações travam na mesma sequência, sem deadlock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.perfil = :perfil and u.ativo = true order by u.id")
    List<Usuario> travarAtivos(Papel perfil);
}
