package com.gestaosla.backend.cadastro.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.cadastro.api.RazaoSocialClienteConsulta;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class RazaoSocialClienteConsultaService implements RazaoSocialClienteConsulta {

    private final ClienteRepository clientes;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> razoesSociais(Collection<UUID> clienteIds) {
        if (clienteIds.isEmpty()) return Map.of();
        return clientes.findAllById(clienteIds).stream()
                .collect(Collectors.toMap(Cliente::getId, Cliente::getRazaoSocial));
    }
}
