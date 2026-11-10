package com.gestaosla.backend.cadastro.internal;

import java.net.URI;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RespostasErro;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Cadastro")
@RequestMapping("/api")
@RequiredArgsConstructor
class ContratoController {

    private final ContratoService servico;

    @Operation(summary = "Cadastrar contrato", description = "Somente GESTOR. A vigência vale do início até 23:59:59 do fim, no fuso do sistema.")
    @ApiResponse(responseCode = "201", description = "Contrato criado")
    @PostMapping("/contratos")
    @PreAuthorize("hasRole('GESTOR')")
    ResponseEntity<ContratoResponse> criar(@Valid @RequestBody DadosContrato dados) {
        Contrato contrato = servico.criar(dados);
        return ResponseEntity.created(URI.create("/api/contratos/" + contrato.getId())).body(ContratoResponse.de(contrato));
    }

    // ?clienteId= &vigente= (em relação a hoje) &page=0&size=20&sort=codigo
    @Operation(summary = "Listar contratos", description = "Somente GESTOR. Paginado; `vigente` compara com a data de hoje.")
    @GetMapping("/contratos")
    @PreAuthorize("hasRole('GESTOR')")
    PagedModel<ContratoResponse> listar(@RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) Boolean vigente,
            @ParameterObject @PageableDefault(size = 20, sort = "codigo", direction = Sort.Direction.ASC) Pageable pagina) {
        return new PagedModel<>(servico.listar(clienteId, vigente, pagina).map(ContratoResponse::de));
    }

    @Operation(summary = "Vincular ou trocar a política do contrato", description = "Somente GESTOR. O contrato tem uma "
            + "política por vez; chamados já abertos mantêm a versão da abertura (RN-02, RN-05). Política inexistente: 422.")
    @ApiResponse(responseCode = "200", description = "Política vinculada")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PutMapping("/contratos/{id}/politica")
    @PreAuthorize("hasRole('GESTOR')")
    ContratoResponse vincularPolitica(@PathVariable UUID id, @Valid @RequestBody Vinculo corpo,
            @AuthenticationPrincipal(expression = "sub") UUID sub, @AuthenticationPrincipal(expression = "papel") Papel papel) {
        return ContratoResponse.de(servico.vincularPolitica(id, corpo.politicaId(), Ator.usuario(sub, papel)));
    }

    @Operation(summary = "Contratos vigentes da minha empresa", description = "Somente CLIENTE. A empresa vem do usuário autenticado.")
    @GetMapping("/me/contratos")
    @PreAuthorize("hasRole('CLIENTE')")
    // A empresa é lida do UsuarioCorrente por expressão: importar o tipo faria o cadastro depender do identidade, que já
    // depende do cadastro (ciclo entre módulos).
    PagedModel<ContratoResponse> meus(@AuthenticationPrincipal(expression = "empresaClienteId") UUID empresaClienteId,
            @ParameterObject @PageableDefault(size = 20, sort = "codigo", direction = Sort.Direction.ASC) Pageable pagina) {
        return new PagedModel<>(servico.listarVigentesDoCliente(empresaClienteId, pagina).map(ContratoResponse::de));
    }

    record Vinculo(@NotNull UUID politicaId) {
    }

    /** {@code politicaId} nulo: contrato ainda sem política, não recebe chamados (RN-03). */
    record ContratoResponse(UUID id, UUID clienteId, String codigo, LocalDate inicioVigencia, LocalDate fimVigencia,
            UUID politicaId) {

        static ContratoResponse de(Contrato c) {
            return new ContratoResponse(c.getId(), c.getClienteId(), c.getCodigo(), c.getInicioVigencia(), c.getFimVigencia(),
                    c.getPoliticaId());
        }
    }
}
