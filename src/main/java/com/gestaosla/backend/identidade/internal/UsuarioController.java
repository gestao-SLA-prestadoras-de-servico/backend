package com.gestaosla.backend.identidade.internal;

import java.net.URI;
import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.Ator;
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
    private final UsuarioConsultaService consulta;
    private final InativacaoUsuarioService inativacao;

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

    // ?perfil= &ativo= &clienteId= &page=0&size=20&sort=nome
    @Operation(summary = "Listar usuários", description = "Somente GESTOR. Paginado (20 por página, máximo 100); inclui inativos. "
            + "O perfil é a cópia local, só para consulta (RN-130).")
    @GetMapping
    @PreAuthorize("hasRole('GESTOR')")
    PagedModel<UsuarioResponse> listar(@RequestParam(required = false) Papel perfil,
            @RequestParam(required = false) Boolean ativo,
            @RequestParam(required = false) UUID clienteId,
            @ParameterObject @PageableDefault(size = 20, sort = "nome", direction = Sort.Direction.ASC) Pageable pagina) {
        return new PagedModel<>(consulta.listar(perfil, ativo, clienteId, pagina).map(UsuarioResponse::de));
    }

    @Operation(summary = "Inativar usuário", description = "Somente GESTOR. Desabilita a conta no Keycloak e preserva o registro. "
            + "Idempotente. Técnico com chamados não finalizados exige `substitutoId`, o id de outro técnico ativo. "
            + "Códigos 422: SUBSTITUTO_OBRIGATORIO, SUBSTITUTO_INVALIDO, AUTOINATIVACAO_NAO_PERMITIDA, ULTIMO_GESTOR_ATIVO.")
    @ApiResponse(responseCode = "204", description = "Usuário inativo")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @ApiResponse(responseCode = "422", ref = RespostasErro.REGRA_NEGOCIO)
    @ApiResponse(responseCode = "503", ref = RespostasErro.SERVICO_INDISPONIVEL)
    @PostMapping("/{id}/inativacao")
    @PreAuthorize("hasRole('GESTOR')")
    ResponseEntity<Void> inativar(@PathVariable UUID id, @RequestBody(required = false) Inativacao corpo,
            @AuthenticationPrincipal UsuarioCorrente gestor) {
        inativacao.inativar(id, corpo == null ? null : corpo.substitutoId(), Ator.usuario(gestor.sub(), gestor.papel()));
        return ResponseEntity.noContent().build();
    }

    /** Usuário autenticado, achado pelo {@code sub} do token; nunca por parâmetro da requisição. */
    @Operation(summary = "Usuário autenticado", description = "Qualquer perfil. Identificado pelo sub do token.")
    @GetMapping("/me")
    UsuarioResponse me(@AuthenticationPrincipal UsuarioCorrente usuario) {
        return usuarios.findBySub(usuario.sub()).map(UsuarioResponse::de).orElseThrow(RecursoNaoEncontradoException::new);
    }

    record Inativacao(UUID substitutoId) {
    }

    record UsuarioResponse(UUID id, UUID sub, String nome, String email, Papel perfil, UUID clienteId, boolean ativo) {

        static UsuarioResponse de(Usuario u) {
            return new UsuarioResponse(u.getId(), u.getSub(), u.getNome(), u.getEmail(), u.getPerfil(), u.getClienteId(), u.isAtivo());
        }
    }

    record UsuarioCadastrado(UUID id, UUID sub, String nome, String email, Papel perfil, UUID clienteId,
            boolean emailDefinicaoSenhaEnviado) {
    }
}
