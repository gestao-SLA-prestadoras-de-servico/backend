package com.gestaosla.backend.chamado.internal;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RespostasErro;
import com.gestaosla.backend.sla.api.RelogioView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Chamados")
@RequestMapping("/api/chamados")
@RequiredArgsConstructor
class ChamadoController {

    private final AberturaChamadoService abertura;
    private final ConsultaChamadoService consulta;
    private final PrioridadeService prioridades;
    private final PosicionamentoService posicionamentos;
    private final AtribuicaoService atribuicoes;
    private final PreferenciaNotificacaoService preferencias;
    private final CancelamentoService cancelamentos;
    private final AtendimentoService atendimentos;
    private final ValidacaoService validacoes;
    private final ReaberturaService reaberturas;

    @Operation(summary = "Abrir chamado", description = "Somente CLIENTE, sob contrato vigente da própria empresa. Os três "
            + "prazos de SLA são calculados na mesma transação; a prioridade sugerida não muda nenhum prazo.")
    @ApiResponse(responseCode = "201", description = "Chamado aberto")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping
    @PreAuthorize("hasRole('CLIENTE')")
    ResponseEntity<ChamadoView> abrir(@Valid @RequestBody DadosAbertura dados, @AuthenticationPrincipal UsuarioCorrente usuario) {
        Chamado chamado = abertura.abrir(dados, usuario);
        return ResponseEntity.created(URI.create("/api/chamados/" + chamado.getId()))
                .body(ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, chamado.getId())));
    }

    // ?estado= &prioridade= &comViolacao= &clienteId= &tecnicoSub= (os dois últimos só para o gestor)
    @Operation(summary = "Listar chamados", description = "Gestor vê todos; cliente, os da própria empresa; técnico, os "
            + "atribuídos a ele. `clienteId` e `tecnicoSub` só valem para o gestor. Ordenação: `numero` (padrão, "
            + "decrescente) ou `proximoVencimento` (prazo de SLA correndo mais próximo).")
    @GetMapping
    @PreAuthorize("hasAnyRole('GESTOR', 'TECNICO', 'CLIENTE')")
    PagedModel<ChamadoView> listar(@RequestParam(required = false) EstadoChamado estado,
            @RequestParam(required = false) Prioridade prioridade, @RequestParam(required = false) Boolean comViolacao,
            @RequestParam(required = false) UUID clienteId, @RequestParam(required = false) UUID tecnicoSub,
            @ParameterObject @PageableDefault(size = 20, sort = "numero", direction = Sort.Direction.DESC) Pageable pagina,
            @AuthenticationPrincipal UsuarioCorrente usuario) {
        var filtro = new ConsultaChamadoService.Filtro(estado, prioridade, comViolacao, clienteId, tecnicoSub);
        return new PagedModel<>(consulta.listar(usuario, filtro, pagina)
                .map(c -> ChamadoView.de(usuario.papel(), new ConsultaChamadoService.Detalhe(c, null, null, Map.of(), null))));
    }

    @Operation(summary = "Contagem por estado", description = "Quantidade de chamados de cada estado, no que o usuário pode ver.")
    @GetMapping("/contagem")
    @PreAuthorize("hasAnyRole('GESTOR', 'TECNICO', 'CLIENTE')")
    Map<EstadoChamado, Long> contagem(@AuthenticationPrincipal UsuarioCorrente usuario) {
        return consulta.contarPorEstado(usuario);
    }

    @Operation(summary = "Detalhe do chamado", description = "Com os três relógios de SLA, os atendimentos e a contagem de "
            + "evidências. O técnico não recebe dados de contrato nem de política.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('GESTOR', 'TECNICO', 'CLIENTE')")
    ChamadoView detalhar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Histórico do chamado", description = "Linha do tempo filtrada pelo perfil; o gestor vê todos os eventos.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @GetMapping("/{id}/historico")
    @PreAuthorize("hasAnyRole('GESTOR', 'TECNICO', 'CLIENTE')")
    List<EventoView> historico(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        return consulta.historico(usuario, id).stream()
                .map(e -> new EventoView(e.getEvento().name(), e.getAtorPapel(), e.getOcorridoEm(), e.getDados())).toList();
    }

    @Operation(summary = "Definir ou alterar a prioridade", description = "Somente GESTOR, antes de RESOLVIDO. Recalcula os "
            + "prazos que ainda correm; a mesma prioridade efetiva não altera nada.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PutMapping("/{id}/prioridade")
    @PreAuthorize("hasRole('GESTOR')")
    ChamadoView alterarPrioridade(@PathVariable UUID id, @Valid @RequestBody NovaPrioridade corpo,
            @AuthenticationPrincipal UsuarioCorrente usuario) {
        prioridades.alterar(id, corpo.prioridade(), Ator.usuario(usuario.sub(), usuario.papel()));
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Registrar o primeiro posicionamento", description = "Somente GESTOR, em qualquer estado não final. "
            + "Cumpre o SLA de resposta e envia o texto por e-mail ao cliente. Só uma vez: a segunda responde 422 "
            + "`POSICIONAMENTO_JA_REGISTRADO`.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/posicionamento")
    @PreAuthorize("hasRole('GESTOR')")
    ChamadoView posicionar(@PathVariable UUID id, @Valid @RequestBody Posicionamento corpo,
            @AuthenticationPrincipal UsuarioCorrente usuario) {
        posicionamentos.registrar(id, corpo.texto(), Ator.usuario(usuario.sub(), usuario.papel()));
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Atribuir ou reatribuir o técnico", description = "Somente GESTOR, em ABERTO, ATRIBUIDO ou "
            + "EM_ATENDIMENTO. `version` é a do chamado lido: se mudou, 409. Em EM_ATENDIMENTO o chamado volta a ATRIBUIDO "
            + "e o atendimento é interrompido. Nenhum prazo de SLA muda.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/atribuicao")
    @PreAuthorize("hasRole('GESTOR')")
    ChamadoView atribuir(@PathVariable UUID id, @Valid @RequestBody Atribuicao corpo,
            @AuthenticationPrincipal UsuarioCorrente usuario) {
        atribuicoes.atribuir(id, corpo.tecnicoSub(), corpo.version(), Ator.usuario(usuario.sub(), usuario.papel()));
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Ativar a minha preferência de notificação", description = "Somente GESTOR. Avisa o próprio gestor "
            + "quando o técnico concluir o atendimento deste chamado. Repetir não muda nada.")
    @ApiResponse(responseCode = "204", description = "Preferência ativa")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PutMapping("/{id}/preferencia-notificacao")
    @PreAuthorize("hasRole('GESTOR')")
    ResponseEntity<Void> ativarPreferencia(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        preferencias.ativar(id, Ator.usuario(usuario.sub(), usuario.papel()));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Desativar a minha preferência de notificação", description = "Somente GESTOR; só a do próprio gestor.")
    @ApiResponse(responseCode = "204", description = "Preferência inativa")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @DeleteMapping("/{id}/preferencia-notificacao")
    @PreAuthorize("hasRole('GESTOR')")
    ResponseEntity<Void> desativarPreferencia(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        preferencias.desativar(id, Ator.usuario(usuario.sub(), usuario.papel()));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Cancelar chamado", description = "Somente GESTOR, com motivo, em ABERTO, ATRIBUIDO ou EM_ATENDIMENTO. "
            + "O atendimento em andamento é interrompido; o chamado cancelado não tem apuração de SLA.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/cancelamento")
    @PreAuthorize("hasRole('GESTOR')")
    ChamadoView cancelar(@PathVariable UUID id, @Valid @RequestBody Cancelamento corpo,
            @AuthenticationPrincipal UsuarioCorrente usuario) {
        cancelamentos.cancelar(id, corpo.motivo(), Ator.usuario(usuario.sub(), usuario.papel()));
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Iniciar o atendimento", description = "Somente o TECNICO atribuído, com o chamado em ATRIBUIDO. Cumpre "
            + "o SLA de atendimento em campo no primeiro início do ciclo.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/atendimento/inicio")
    @PreAuthorize("hasRole('TECNICO')")
    ChamadoView iniciarAtendimento(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        atendimentos.iniciar(id, Ator.usuario(usuario.sub(), usuario.papel()));
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Registrar a descrição técnica", description = "Somente o TECNICO atribuído, com o atendimento em "
            + "andamento. Pode ser reescrita até a conclusão.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PutMapping("/{id}/atendimento/descricao")
    @PreAuthorize("hasRole('TECNICO')")
    ChamadoView registrarDescricao(@PathVariable UUID id, @Valid @RequestBody Descricao corpo,
            @AuthenticationPrincipal UsuarioCorrente usuario) {
        atendimentos.registrarDescricao(id, Ator.usuario(usuario.sub(), usuario.papel()), corpo.descricao());
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Concluir o atendimento", description = "Somente o TECNICO atribuído, com o atendimento em andamento. "
            + "Exige ao menos uma evidência (a ainda não processada já vale) e a descrição técnica; sem elas, 422 "
            + "`EVIDENCIA_OBRIGATORIA` ou `DESCRICAO_TECNICA_OBRIGATORIA`. O chamado passa a RESOLVIDO e cumpre o SLA de "
            + "resolução; o cliente recebe e-mail e os gestores com a preferência ativa, aviso.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/atendimento/conclusao")
    @PreAuthorize("hasRole('TECNICO')")
    ChamadoView concluirAtendimento(@PathVariable UUID id, @AuthenticationPrincipal UsuarioCorrente usuario) {
        atendimentos.concluir(id, Ator.usuario(usuario.sub(), usuario.papel()));
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Validar o chamado resolvido", description = "Somente CLIENTE da empresa do chamado, em RESOLVIDO. "
            + "Encerra o chamado e congela a apuração de SLA. `version` é a do chamado lido: se mudou, 409.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/validacao")
    @PreAuthorize("hasRole('CLIENTE')")
    ChamadoView validar(@PathVariable UUID id, @Valid @RequestBody Validacao corpo, @AuthenticationPrincipal UsuarioCorrente usuario) {
        validacoes.validar(id, corpo.version(), usuario);
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    @Operation(summary = "Reabrir o chamado resolvido", description = "Somente CLIENTE da empresa do chamado, em RESOLVIDO, com "
            + "justificativa. Volta a ATRIBUIDO com o mesmo técnico, cria um atendimento novo e reinicia os prazos de campo e "
            + "de resolução. Chamado ENCERRADO: 422 `REABERTURA_NAO_PERMITIDA`. `version` é a do chamado lido: se mudou, 409.")
    @ApiResponse(responseCode = "404", ref = RespostasErro.NAO_ENCONTRADO)
    @PostMapping("/{id}/reabertura")
    @PreAuthorize("hasRole('CLIENTE')")
    ChamadoView reabrir(@PathVariable UUID id, @Valid @RequestBody Reabertura corpo, @AuthenticationPrincipal UsuarioCorrente usuario) {
        reaberturas.reabrir(id, corpo.justificativa(), corpo.version(), usuario);
        return ChamadoView.de(usuario.papel(), consulta.detalhar(usuario, id));
    }

    record Validacao(@NotNull Long version) {
    }

    record Reabertura(@NotBlank @Size(max = 1000) String justificativa, @NotNull Long version) {
    }

    record NovaPrioridade(@NotNull Prioridade prioridade) {
    }

    record Cancelamento(@NotBlank @Size(max = 1000) String motivo) {
    }

    record Descricao(@NotBlank @Size(max = 4000) String descricao) {
    }

    record Posicionamento(@NotBlank @Size(max = 4000) String texto) {
    }

    record Atribuicao(@NotNull UUID tecnicoSub, @NotNull Long version) {
    }

    record EventoView(String evento, Papel atorPapel, Instant ocorridoEm, Map<String, Object> dados) {
    }

    record AtendimentoView(UUID id, UUID tecnicoSub, EstadoAtendimento estado, Instant iniciadoEm, Instant encerradoEm,
            String descricaoTecnica, long evidencias) {
    }

    /**
     * Chamado na visão do perfil. Na listagem, {@code relogios} e {@code atendimentos} não vêm (ficam de fora do JSON). O técnico não recebe
     * nenhum campo de contrato ou política (RF-04); o cliente não recebe o sub do técnico.
     */
    @Schema(oneOf = {ChamadoGestor.class, ChamadoCliente.class, ChamadoTecnico.class})
    sealed interface ChamadoView permits ChamadoGestor, ChamadoCliente, ChamadoTecnico {

        static ChamadoView de(Papel papel, ConsultaChamadoService.Detalhe d) {
            Chamado c = d.chamado();
            List<AtendimentoView> atendimentos = d.atendimentos() == null ? null : d.atendimentos().stream()
                    .map(a -> new AtendimentoView(a.getId(), a.getTecnicoSub(), a.getEstado(), a.getIniciadoEm(),
                            a.getEncerradoEm(), a.getDescricaoTecnica(), d.evidencias().getOrDefault(a.getId(), 0L)))
                    .toList();
            return switch (papel) {
                case GESTOR -> new ChamadoGestor(c.getId(), c.getNumero(), c.getContratoId(), c.getClienteId(), c.getPoliticaVersaoId(),
                        c.getTitulo(), c.getDescricao(), c.getLocal(), c.getEstado(), c.getPrioridadeSugerida(),
                        c.getPrioridadeDefinida(), c.getPrioridadePadrao(), c.prioridadeEfetiva(), c.getTecnicoSub(),
                        c.getAbertoEm(), c.getResolvidoEm(), c.getEstadoFinalEm(), c.isComViolacao(), c.getProximoVencimento(),
                        c.getPosicionamento(), c.getPosicionadoEm(), c.getVersion(), d.notificarConclusao(), d.relogios(), atendimentos);
                case CLIENTE -> new ChamadoCliente(c.getId(), c.getNumero(), c.getContratoId(), c.getClienteId(), c.getPoliticaVersaoId(),
                        c.getTitulo(), c.getDescricao(), c.getLocal(), c.getEstado(), c.getPrioridadeSugerida(),
                        c.getPrioridadeDefinida(), c.getPrioridadePadrao(), c.prioridadeEfetiva(), c.getAbertoEm(),
                        c.getResolvidoEm(), c.getEstadoFinalEm(), c.isComViolacao(), c.getProximoVencimento(),
                        c.getPosicionamento(), c.getPosicionadoEm(), c.getVersion(), d.relogios(), atendimentos);
                case TECNICO -> new ChamadoTecnico(c.getId(), c.getNumero(), c.getClienteId(), c.getTitulo(), c.getDescricao(),
                        c.getLocal(), c.getEstado(), c.getPrioridadeSugerida(), c.prioridadeEfetiva(), c.getTecnicoSub(),
                        c.getAbertoEm(), c.getResolvidoEm(), c.getEstadoFinalEm(), c.isComViolacao(), c.getProximoVencimento(),
                        d.relogios(), atendimentos);
            };
        }
    }

    record ChamadoGestor(UUID id, Long numero, UUID contratoId, UUID clienteId, UUID politicaVersaoId, String titulo, String descricao,
            String local, EstadoChamado estado, Prioridade prioridadeSugerida, Prioridade prioridadeDefinida,
            Prioridade prioridadePadrao, Prioridade prioridade, UUID tecnicoSub, Instant abertoEm, Instant resolvidoEm,
            Instant estadoFinalEm, boolean comViolacao, Instant proximoVencimento, String posicionamento, Instant posicionadoEm,
            long version, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean notificarConclusao,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<RelogioView> relogios,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<AtendimentoView> atendimentos) implements ChamadoView {
    }

    record ChamadoCliente(UUID id, Long numero, UUID contratoId, UUID clienteId, UUID politicaVersaoId, String titulo, String descricao,
            String local, EstadoChamado estado, Prioridade prioridadeSugerida, Prioridade prioridadeDefinida,
            Prioridade prioridadePadrao, Prioridade prioridade, Instant abertoEm, Instant resolvidoEm, Instant estadoFinalEm,
            boolean comViolacao, Instant proximoVencimento, String posicionamento, Instant posicionadoEm,
            // [chamado - 12]: o cliente envia a versão lida ao validar ou reabrir.
            long version, @JsonInclude(JsonInclude.Include.NON_NULL) List<RelogioView> relogios,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<AtendimentoView> atendimentos) implements ChamadoView {
    }

    record ChamadoTecnico(UUID id, Long numero, UUID clienteId, String titulo, String descricao, String local, EstadoChamado estado,
            Prioridade prioridadeSugerida, Prioridade prioridade, UUID tecnicoSub, Instant abertoEm, Instant resolvidoEm,
            Instant estadoFinalEm, boolean comViolacao, Instant proximoVencimento, @JsonInclude(JsonInclude.Include.NON_NULL) List<RelogioView> relogios,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<AtendimentoView> atendimentos) implements ChamadoView {
    }
}
