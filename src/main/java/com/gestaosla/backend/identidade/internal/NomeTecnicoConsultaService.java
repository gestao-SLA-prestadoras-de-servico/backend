package com.gestaosla.backend.identidade.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.identidade.api.NomeTecnicoConsulta;
import com.gestaosla.backend.shared.api.Papel;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class NomeTecnicoConsultaService implements NomeTecnicoConsulta {

    private final UsuarioRepository usuarios;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> nomesTecnicos(Collection<UUID> subs) {
        if (subs.isEmpty()) return Map.of();
        return usuarios.findBySubIn(subs).stream().filter(u -> u.getPerfil() == Papel.TECNICO)
                .collect(Collectors.toMap(Usuario::getSub, Usuario::getNome));
    }
}
