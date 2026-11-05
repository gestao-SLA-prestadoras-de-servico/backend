package com.gestaosla.backend.identidade.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.identidade.api.UsuarioConsulta;
import com.gestaosla.backend.shared.api.Papel;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class UsuarioConsultaService implements UsuarioConsulta {

    private final UsuarioRepository usuarios;

    @Transactional(readOnly = true)
    Page<Usuario> listar(Papel perfil, Boolean ativo, UUID clienteId, Pageable pagina) {
        Specification<Usuario> filtro = Specification.unrestricted();
        if (perfil != null) {
            filtro = filtro.and((u, q, cb) -> cb.equal(u.get("perfil"), perfil));
        }
        if (ativo != null) {
            filtro = filtro.and((u, q, cb) -> cb.equal(u.get("ativo"), ativo));
        }
        if (clienteId != null) {
            filtro = filtro.and((u, q, cb) -> cb.equal(u.get("clienteId"), clienteId));
        }
        return usuarios.findAll(filtro, pagina);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listarGestoresAtivos() {
        return usuarios.findByPerfilAndAtivoTrue(Papel.GESTOR).stream().map(Usuario::getSub).toList();
    }
}
