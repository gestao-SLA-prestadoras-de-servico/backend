package com.gestaosla.backend.identidade.internal;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RespostasErro;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Identidade")
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
class UsuarioController {

    private final UsuarioRepository usuarios;
    private final CadastroUsuarioService cadastro;

    @Operation(summary = "Cadastrar usuário", description = "Somente GESTOR. Cria a conta no Keycloak com o papel e o registro "
            + "local; o usuário recebe por e-mail o link para definir a senha (o sistema nunca recebe senha).")
    @ApiResponse(responseCode = "201", description = "Usuário cadastrado; emailDefinicaoSenhaEnviado=false se o e-mail falhou")
    @ApiResponse(responseCode = "409", ref = RespostasErro.CONFLITO)
    @ApiResponse(responseCode = "422", ref = RespostasErro.REGRA_NEGOCIO)
    @ApiResponse(responseCode = "503", ref = RespostasErro.SERVICO_INDISPONIVEL)
    @PostMapping
    @PreAuthorize("hasRole('GESTOR')")
    ResponseEntity<UsuarioCadastrado> cadastrar(@Valid @RequestBody NovoUsuario dados,
            @AuthenticationPrincipal UsuarioCorrente gestor) {
        CadastroUsuarioService.Resultado resultado = cadastro.cadastrar(dados, gestor);
        Usuario u = resultado.usuario();
        return ResponseEntity.created(URI.create("/api/usuarios/" + u.getId())).body(new UsuarioCadastrado(u.getId(),
                u.getSub(), u.getNome(), u.getEmail(), u.getPerfil(), u.getClienteId(), resultado.emailDefinicaoSenhaEnviado()));
    }

    /** Usuário autenticado, achado pelo {@code sub} do token; nunca por parâmetro da requisição. */
    @Operation(summary = "Usuário autenticado", description = "Qualquer perfil. Identificado pelo sub do token.")
    @GetMapping("/me")
    UsuarioResponse me(@AuthenticationPrincipal UsuarioCorrente usuario) {
        return usuarios.findBySub(usuario.sub())
                .map(u -> new UsuarioResponse(u.getId(), u.getSub(), u.getNome(), u.getEmail(), u.getPerfil(), u.getClienteId()))
                .orElseThrow(RecursoNaoEncontradoException::new);
    }

    record UsuarioResponse(UUID id, UUID sub, String nome, String email, Papel perfil, UUID clienteId) {
    }

    record UsuarioCadastrado(UUID id, UUID sub, String nome, String email, Papel perfil, UUID clienteId,
            boolean emailDefinicaoSenhaEnviado) {
    }
}
