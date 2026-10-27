package com.gestaosla.backend.identidade.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findBySub(UUID sub);
}
