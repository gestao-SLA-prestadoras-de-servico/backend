package com.gestaosla.backend.chamado.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.RespostasErro;
import com.gestaosla.backend.sla.api.ApuracaoChamado;
import com.gestaosla.backend.sla.api.ApuracaoSla;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

/**
 * Resultado de SLA de um chamado (RF-37). Fica no chamado porque é ele que decide quem acessa o chamado; o sla só
 * guarda a apuração e não pode depender do chamado.
 */
@RestController
@Tag(name = "Chamados")
@PreAuthorize("hasAnyRole('GESTOR', 'TECNICO', 'CLIENTE')")
@RequiredArgsConstructor
class SlaChamadoController {

    private final ApuracaoSla apuracoes;
    private final ConsultaChamadoService consulta;

    @Operation(summary = "Resultado de SLA do chamado", description = "Para quem pode ver o chamado: gestor, todos; cliente, "
            + "os da própria empresa; técnico, os atribuídos a ele. Chamado cancelado ou ainda não encerrado: "
            + "`apurado: false`.")
    @ApiResponse(responseCode = "200", description = "Apuração congelada no encerramento, ou `apurado: false`")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @GetMapping("/api/chamados/{id}/sla")
    SlaResponse consultar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        // RN-126: fora do escopo do usuário, o chamado responde como inexistente (404), nunca 403.
        consulta.buscarVisivel(usuario, id);
        return apuracoes.consultarApuracao(id).map(SlaResponse::de).orElse(SlaResponse.SEM_APURACAO);
    }

    /** {@code apurado: false} para chamado cancelado (RN-19) ou ainda não encerrado; os demais campos vêm nulos. */
    record SlaResponse(boolean apurado, Prioridade prioridade, Instant congeladaEm, List<ApuracaoChamado.Relogio> relogios) {

        static final SlaResponse SEM_APURACAO = new SlaResponse(false, null, null, null);

        static SlaResponse de(ApuracaoChamado a) {
            return new SlaResponse(true, a.prioridade(), a.congeladaEm(), a.relogios());
        }
    }
}
