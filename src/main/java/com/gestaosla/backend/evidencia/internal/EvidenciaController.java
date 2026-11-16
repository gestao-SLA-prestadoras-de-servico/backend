package com.gestaosla.backend.evidencia.internal;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.gestaosla.backend.evidencia.internal.ConsultaEvidenciaService.Conteudo;
import com.gestaosla.backend.evidencia.internal.ConsultaEvidenciaService.Variante;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.RespostasErro;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Evidências")
@RequestMapping("/api")
@RequiredArgsConstructor
class EvidenciaController {

    record EvidenciaRecebida(UUID id, EstadoEvidencia estado) {
    }

    record ItemEvidencia(UUID id, String nome, String tipoConteudo, long tamanho, String hashSha256, EstadoEvidencia estado,
            Instant enviadaEm, Instant expurgadaEm) {
    }

    record EvidenciasDoAtendimento(UUID atendimentoId, List<ItemEvidencia> evidencias) {
    }

    // O conteúdo de uma chave nunca muda (RN-77), então o navegador pode guardar; "private" porque o acesso é por usuário.
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate();

    private final RecepcaoEvidenciaService recepcao;
    private final ConsultaEvidenciaService consulta;
    private final ReprocessamentoService reprocessamento;
    private final ArmazenamentoEvidencias armazenamento;

    @Operation(summary = "Enviar evidência", description = "Somente o TECNICO atribuído, com o atendimento em andamento. "
            + "Multipart, campo `arquivo`: JPEG, PNG ou WebP de até 10 MB (o tipo vem dos bytes, não da extensão). A imagem "
            + "é sanitizada (EXIF removido, rotação aplicada) e gravada antes da resposta; os derivados são gerados depois, "
            + "e a evidência fica RECEBIDA. 413 `ARQUIVO_MUITO_GRANDE`; 422 `FORMATO_NAO_SUPORTADO`, `IMAGEM_MUITO_GRANDE`, "
            + "`IMAGEM_INVALIDA` ou `TRANSICAO_INVALIDA` (sem atendimento em andamento).")
    @ApiResponse(responseCode = "202", description = "Evidência recebida")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @ApiResponse(responseCode = "413", ref = RespostasErro.ARQUIVO_MUITO_GRANDE)
    @ApiResponse(responseCode = "422", ref = RespostasErro.REGRA_NEGOCIO)
    @PostMapping(path = "/chamados/{id}/evidencias", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TECNICO')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    EvidenciaRecebida enviar(@PathVariable UUID id, @RequestParam("arquivo") MultipartFile arquivo,
            @AuthenticationPrincipal UsuarioCorrente usuario) {
        return new EvidenciaRecebida(recepcao.receber(id, arquivo, usuario), EstadoEvidencia.RECEBIDA);
    }

    @Operation(summary = "Listar evidências do chamado", description = "Os três papéis, dentro do isolamento do chamado (outro cliente "
            + "ou técnico não atribuído recebe 404). Agrupadas por atendimento e ordenadas por `enviadaEm`; as EXPURGADAS "
            + "continuam na lista, só com os metadados (RN-104). Sem paginação.")
    @ApiResponse(responseCode = "200", description = "Evidências por atendimento")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @GetMapping("/chamados/{id}/evidencias")
    @PreAuthorize("hasAnyRole('GESTOR', 'TECNICO', 'CLIENTE')")
    List<EvidenciasDoAtendimento> listar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        Map<UUID, List<ItemEvidencia>> porAtendimento = new LinkedHashMap<>();
        for (Evidencia e : consulta.listar(id, usuario)) {
            porAtendimento.computeIfAbsent(e.getAtendimentoId(), k -> new ArrayList<>()).add(new ItemEvidencia(e.getId(),
                    e.getNome(), e.getTipoConteudo(), e.getTamanho(), e.getHashSha256(), e.getEstado(), e.getEnviadaEm(), e.getExpurgadaEm()));
        }
        return porAtendimento.entrySet().stream().map(a -> new EvidenciasDoAtendimento(a.getKey(), a.getValue())).toList();
    }

    @Operation(summary = "Baixar o conteúdo da evidência", description = "Os três papéis, dentro do isolamento do chamado (senão 404). "
            + "`variante`: `visualizacao` (padrão, JPEG de até 1280 px), `miniatura` (JPEG de até 320 px) ou `original`. Se o "
            + "derivado pedido ainda não existe (evidência RECEBIDA, PROCESSANDO ou FALHA), serve o original. Resposta em streaming, "
            + "com `Content-Type`, `Content-Length`, `ETag` forte e `Cache-Control: private`; `If-None-Match` igual responde 304 "
            + "sem ler o storage. Evidência EXPURGADA: 410 `EVIDENCIA_EXPURGADA`. Variante desconhecida: 400.")
    @ApiResponse(responseCode = "200", description = "Bytes da imagem", content = @Content(mediaType = "image/*"))
    @ApiResponse(responseCode = "304", description = "Não modificado (`If-None-Match`)", content = @Content)
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @ApiResponse(responseCode = "410", ref = RespostasErro.EVIDENCIA_EXPURGADA)
    @ApiResponse(responseCode = "503", ref = RespostasErro.SERVICO_INDISPONIVEL)
    @GetMapping("/evidencias/{id}/conteudo")
    @PreAuthorize("hasAnyRole('GESTOR', 'TECNICO', 'CLIENTE')")
    ResponseEntity<StreamingResponseBody> conteudo(@PathVariable UUID id,
            @RequestParam(defaultValue = "visualizacao") Variante variante, @AuthenticationPrincipal UsuarioCorrente usuario,
            WebRequest request) {
        Conteudo conteudo = consulta.localizar(id, variante, usuario);
        if (request.checkNotModified(conteudo.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CACHE).build();
        }
        // Aberto antes de responder: falha do storage (503) ainda sai como erro normal e o tamanho vai no Content-Length.
        ObjetoLido lido = armazenamento.ler(conteudo.chave());
        StreamingResponseBody corpo = saida -> {
            try (InputStream entrada = lido.conteudo()) { // fecha também se o cliente abortar
                entrada.transferTo(saida);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(conteudo.tipoConteudo())).contentLength(lido.tamanho())
                .cacheControl(CACHE).header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(corpo);
    }

    @Operation(summary = "Reprocessar evidência com falha", description = "Somente o GESTOR. A evidência precisa estar em FALHA "
            + "(senão 422 `REPROCESSAMENTO_NAO_PERMITIDO`): volta a RECEBIDA, é auditada e um novo evento de processamento "
            + "entra na outbox.")
    @ApiResponse(responseCode = "202", description = "Evidência recolocada na fila de processamento")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @ApiResponse(responseCode = "422", ref = RespostasErro.REGRA_NEGOCIO)
    @PostMapping("/evidencias/{id}/reprocessamento")
    @PreAuthorize("hasRole('GESTOR')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    EvidenciaRecebida reprocessar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        reprocessamento.reprocessar(id, usuario);
        return new EvidenciaRecebida(id, EstadoEvidencia.RECEBIDA);
    }
}
