package com.gestaosla.backend.relatorio.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
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

    RelatorioSlaController(RelatorioSlaService service) {
        this.service = service;
    }

    @GetMapping("/sla")
    @PreAuthorize("hasRole('GESTOR')")
    @Operation(summary = "Consultar relatório gerencial de cumprimento de SLA")
    RelatorioSlaService.RelatorioSlaResposta sla(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fim,
            @RequestParam(required = false) UUID clienteId, @RequestParam(required = false) UUID tecnicoSub) {
        if (inicio.isAfter(fim)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inicio deve ser anterior ou igual a fim.");
        }
        return service.gerar(inicio, fim, clienteId, tecnicoSub);
    }
}
