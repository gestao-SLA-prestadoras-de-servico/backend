package com.gestaosla.backend.cadastro.internal;

import java.net.URI;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.cadastro.internal.PoliticaService.PoliticaComVersao;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RespostasErro;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

/** Prazos sempre em minutos de janela (RN-29); a conversão para "dias úteis" é do frontend. */
@RestController
@Tag(name = "Cadastro")
@RequestMapping("/api/politicas")
@PreAuthorize("hasRole('GESTOR')")
@RequiredArgsConstructor
class PoliticaController {

    private final PoliticaService servico;

    @Operation(summary = "Cadastrar política de SLA", description = "Somente GESTOR. Cria a política com a versão 1. "
            + "Em cada prioridade, resposta < campo < resolução (RN-30); senão, 422 ORDEM_PRAZOS_INVALIDA.")
    @ApiResponse(responseCode = "201", description = "Política criada")
    @PostMapping
    ResponseEntity<PoliticaResponse> criar(@Valid @RequestBody DadosPolitica dados,
            // Por expressão: importar UsuarioCorrente criaria ciclo entre cadastro e identidade.
            @AuthenticationPrincipal(expression = "sub") UUID sub, @AuthenticationPrincipal(expression = "papel") Papel papel) {
        PoliticaComVersao criada = servico.criar(dados, Ator.usuario(sub, papel));
        return ResponseEntity.created(URI.create("/api/politicas/" + criada.politica().getId())).body(PoliticaResponse.de(criada));
    }

    @Operation(summary = "Criar nova versão da política", description = "Somente GESTOR. Não existe edição: as versões "
            + "anteriores ficam como estão, e chamados já abertos mantêm os prazos da sua versão (RN-04, RN-05).")
    @ApiResponse(responseCode = "201", description = "Versão criada")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/versoes")
    ResponseEntity<VersaoResponse> novaVersao(@PathVariable UUID id, @Valid @RequestBody DadosVersao dados,
            @AuthenticationPrincipal(expression = "sub") UUID sub, @AuthenticationPrincipal(expression = "papel") Papel papel) {
        VersaoPolitica versao = servico.novaVersao(id, dados, Ator.usuario(sub, papel));
        return ResponseEntity.created(URI.create("/api/politicas/" + id + "/versoes")).body(VersaoResponse.de(versao));
    }

    @Operation(summary = "Listar políticas de SLA", description = "Somente GESTOR. Paginado, com a versão atual de cada uma.")
    @GetMapping
    PagedModel<PoliticaResponse> listar(
            @ParameterObject @PageableDefault(size = 20, sort = "nome", direction = Sort.Direction.ASC) Pageable pagina) {
        return new PagedModel<>(servico.listar(pagina).map(PoliticaResponse::de));
    }

    @Operation(summary = "Histórico de versões da política", description = "Somente GESTOR. Paginado, da mais nova para a mais antiga.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @GetMapping("/{id}/versoes")
    PagedModel<VersaoResponse> versoes(@PathVariable UUID id,
            @ParameterObject @PageableDefault(size = 20, sort = "numero", direction = Sort.Direction.DESC) Pageable pagina) {
        return new PagedModel<>(servico.versoes(id, pagina).map(VersaoResponse::de));
    }

    record PoliticaResponse(UUID id, String nome, VersaoResponse versaoAtual) {

        static PoliticaResponse de(PoliticaComVersao p) {
            return new PoliticaResponse(p.politica().getId(), p.politica().getNome(), VersaoResponse.de(p.versaoAtual()));
        }
    }

    /** {@code criadaPor} é o sub do gestor; nulo na política padrão, criada pela migração. */
    record VersaoResponse(UUID id, int numero, Prioridade prioridadePadrao, PrazosPorPrioridade prazos, Instant criadaEm,
            UUID criadaPor) {

        static VersaoResponse de(VersaoPolitica v) {
            return new VersaoResponse(v.getId(), v.getNumero(), v.getPrioridadePadrao(), v.prazos(), v.getCriadaEm(), v.getCriadaPor());
        }
    }
}
