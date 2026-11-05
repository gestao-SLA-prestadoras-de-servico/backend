package com.gestaosla.backend.identidade.internal;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.shared.api.Papel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Identidade")
@RequestMapping("/api/tecnicos")
@PreAuthorize("hasRole('GESTOR')")
@RequiredArgsConstructor
class TecnicoController {

    private final UsuarioConsultaService consulta;

    @Operation(summary = "Listar técnicos", description = "Somente GESTOR. Paginado (20 por página, máximo 100), para escolher o "
            + "técnico na atribuição e na inativação. Sem `ativos`, lista os ativos.")
    @GetMapping
    PagedModel<TecnicoResponse> listar(@RequestParam(defaultValue = "true") boolean ativos,
            @ParameterObject @PageableDefault(size = 20, sort = "nome", direction = Sort.Direction.ASC) Pageable pagina) {
        return new PagedModel<>(consulta.listar(Papel.TECNICO, ativos, null, pagina).map(TecnicoResponse::de));
    }

    record TecnicoResponse(UUID sub, String nome, long chamadosNaoFinalizados) {

        // A carga real entra quando o módulo chamado existir; até lá, 0.
        static TecnicoResponse de(Usuario u) {
            return new TecnicoResponse(u.getSub(), u.getNome(), 0);
        }
    }
}
