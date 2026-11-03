package com.gestaosla.backend.identidade.internal;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Identidade")
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
class UsuarioController {

    private final UsuarioRepository usuarios;

    /** Usuário autenticado, achado pelo {@code sub} do token; nunca por parâmetro da requisição. */
    @Operation(summary = "Usuário autenticado", description = "Qualquer perfil. Identificado pelo sub do token.")
    @GetMapping("/me")
    UsuarioResponse me(@AuthenticationPrincipal UsuarioCorrente usuario) {
        return usuarios.findBySub(usuario.sub())
                .map(u -> new UsuarioResponse(u.getId(), u.getSub(), u.getNome(), u.getPerfil()))
                .orElseThrow(RecursoNaoEncontradoException::new);
    }

    record UsuarioResponse(UUID id, UUID sub, String nome, Papel perfil) {
    }
}
