package com.gestaosla.backend.relatorio.internal;

import java.time.Instant;
import java.time.Clock;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/relatorios")
@Tag(name = "Relatórios")
class RelatorioSlaController {

    private final RelatorioSlaService service;
    private final RelatorioSlaExportacaoService exportacao;
    private final RelatorioSlaExportador exportador;
    private final Clock clock;

    RelatorioSlaController(RelatorioSlaService service, RelatorioSlaExportacaoService exportacao,
            RelatorioSlaExportador exportador, Clock clock) {
        this.service = service;
        this.exportacao = exportacao;
        this.exportador = exportador;
        this.clock = clock;
    }

    @GetMapping("/sla")
    @PreAuthorize("hasRole('GESTOR')")
    @Operation(summary = "Consultar relatório gerencial de cumprimento de SLA")
    RelatorioSlaService.RelatorioSlaResposta sla(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fim,
            @RequestParam(required = false) UUID clienteId, @RequestParam(required = false) UUID tecnicoSub) {
        validarPeriodo(inicio, fim);
        return service.gerar(inicio, fim, clienteId, tecnicoSub);
    }

    @GetMapping(value = "/sla.csv", produces = "text/csv")
    @PreAuthorize("hasRole('GESTOR')")
    @Operation(summary = "Exportar relatório gerencial de cumprimento de SLA em CSV")
    ResponseEntity<byte[]> csv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fim,
            @RequestParam(required = false) UUID clienteId, @RequestParam(required = false) UUID tecnicoSub) {
        validarPeriodo(inicio, fim);
        var dados = exportacao.gerar(inicio, fim, clienteId, tecnicoSub);
        String nome = RelatorioSlaExportador.nomeArquivo("csv", inicio, fim, clock.getZone());
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nome).build().toString())
                .body(exportador.csv(dados));
    }

    @GetMapping(value = "/sla.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('GESTOR')")
    @Operation(summary = "Exportar relatório gerencial de cumprimento de SLA em PDF")
    ResponseEntity<byte[]> pdf(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fim,
            @RequestParam(required = false) UUID clienteId, @RequestParam(required = false) UUID tecnicoSub) {
        validarPeriodo(inicio, fim);
        var dados = exportacao.gerar(inicio, fim, clienteId, tecnicoSub);
        String nome = RelatorioSlaExportador.nomeArquivo("pdf", inicio, fim, clock.getZone());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nome).build().toString())
                .body(exportador.pdf(dados, inicio, fim));
    }

    private void validarPeriodo(Instant inicio, Instant fim) {
        if (inicio.isAfter(fim)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inicio deve ser anterior ou igual a fim.");
        }
    }
}
