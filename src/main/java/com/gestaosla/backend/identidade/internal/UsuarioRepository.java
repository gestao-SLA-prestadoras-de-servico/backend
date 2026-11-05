package com.gestaosla.backend.identidade.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gestaosla.backend.shared.api.Papel;

interface UsuarioRepository extends JpaRepository<Usuario, UUID>, JpaSpecificationExecutor<Usuario> {

    Optional<Usuario> findBySub(UUID sub);

    boolean existsBySub(UUID sub);

    boolean existsByEmailIgnoreCase(String email);

    List<Usuario> findByPerfilAndAtivoTrue(Papel perfil);
}
