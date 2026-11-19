package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.chamado.api.AtendimentoConsulta;
import com.gestaosla.backend.chamado.api.AtendimentoCorrente;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.chamado.api.EvidenciasDoChamado;
import com.gestaosla.backend.identidade.api.UsuarioConsulta;
import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.MontadorDestinatarios;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.TipoRelogio;


/**
 * Atendimentos do chamado. A escrita roda na transação da operação do chamado (atribuição, reabertura, reatribuição,
 * cancelamento). O índice único parcial garante no máximo um corrente por chamado, também em corrida.
 */
@Service
class AtendimentoService implements AtendimentoConsulta {

    private static final List<EstadoAtendimento> CORRENTES = List.of(EstadoAtendimento.PENDENTE, EstadoAtendimento.EM_ANDAMENTO);

    private final AtendimentoRepository atendimentos;
    private final ChamadoRepository chamados;
    private final TransicaoChamado transicao;
    private final RelogiosSla relogios;
    private final NotificadorViolacao notificador;
    private final Optional<EvidenciasDoChamado> evidencias; // Vazio só sem o módulo evidencia: sem evidência, não conclui.
    private final PreferenciaNotificacaoService preferencias;
    private final UsuarioConsulta usuarios;
    private final MontadorDestinatarios destinatarios;
    private final OutboxService outbox;
    private final Clock clock;

    AtendimentoService(AtendimentoRepository atendimentos, ChamadoRepository chamados, TransicaoChamado transicao,
            RelogiosSla relogios, NotificadorViolacao notificador, Optional<EvidenciasDoChamado> evidencias,
            PreferenciaNotificacaoService preferencias, UsuarioConsulta usuarios, MontadorDestinatarios destinatarios,
            OutboxService outbox, Clock clock) {
        this.atendimentos = atendimentos;
        this.chamados = chamados;
        this.transicao = transicao;
        this.relogios = relogios;
        this.notificador = notificador;
        this.evidencias = evidencias;
        this.preferencias = preferencias;
        this.usuarios = usuarios;
        this.destinatarios = destinatarios;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * O técnico atribuído chegou ao local (RF-22): ATRIBUIDO passa a EM_ATENDIMENTO e o atendimento PENDENTE (criado na
     * reabertura) começa; sem ele, um novo é criado. Cumpre o SLA de campo (RN-24) só no primeiro início do ciclo: depois
     * de uma reatribuição, o sla ignora o segundo cumprimento (RN-22).
     */
    @Transactional
    void iniciar(UUID chamadoId, Ator tecnico) {
        Chamado chamado = doTecnico(chamadoId, tecnico);
        transicao.transicionar(chamado, EstadoChamado.EM_ATENDIMENTO, tecnico);
        Instant agora = Instant.now(clock);
        Atendimento atendimento = atendimentos.findByChamadoIdAndEstadoIn(chamadoId, List.of(EstadoAtendimento.PENDENTE))
                .orElseGet(() -> atendimentos.save(new Atendimento(chamadoId, tecnico.sub())));
        atendimento.iniciar(agora);
        relogios.cumprir(chamadoId, TipoRelogio.CAMPO, agora).violacao().ifPresent(v -> notificador.notificar(chamado, List.of(v)));
    }

    /** Descrição técnica do que foi executado (RF-23), só com o atendimento em andamento. */
    @Transactional
    void registrarDescricao(UUID chamadoId, Ator tecnico, String descricao) {
        doTecnico(chamadoId, tecnico);
        atendimentos.findByChamadoIdAndEstadoIn(chamadoId, CORRENTES)
                .orElseThrow(() -> new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA, "O chamado não tem atendimento em andamento."))
                .descrever(descricao);
    }

    /**
     * O técnico conclui o atendimento (RF-27): o chamado passa a RESOLVIDO e cumpre o SLA de resolução (RN-25). Exige ao
     * menos uma evidência do atendimento, valendo a ainda RECEBIDA (RN-72, RN-73), e a descrição técnica (decisão da
     * [chamado - 11]). Com o chamado travado, a conferência e a mudança de estado ficam na mesma transação (RN-118).
     * O cliente recebe a RESOLUCAO; os gestores com a preferência ativa, a CONCLUSAO (RN-33, RN-36).
     */
    @Transactional
    void concluir(UUID chamadoId, Ator tecnico) {
        Chamado chamado = chamados.buscarParaAlterar(chamadoId).filter(c -> tecnico.sub().equals(c.getTecnicoSub()))
                .orElseThrow(RecursoNaoEncontradoException::new);
        Atendimento atendimento = atendimentos.findByChamadoIdAndEstadoIn(chamadoId, List.of(EstadoAtendimento.EM_ANDAMENTO))
                .orElseThrow(() -> new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA, "O chamado não tem atendimento em andamento."));
        long anexadas = evidencias.map(e -> e.contarPorAtendimento(chamadoId)).orElse(Map.of()).getOrDefault(atendimento.getId(), 0L);
        if (anexadas == 0) {
            throw new RegraNegocioException(CodigoErro.EVIDENCIA_OBRIGATORIA, "Anexe ao menos uma evidência antes de concluir (RN-72).");
        }
        if (atendimento.getDescricaoTecnica() == null || atendimento.getDescricaoTecnica().isBlank()) {
            throw new RegraNegocioException(CodigoErro.DESCRICAO_TECNICA_OBRIGATORIA,
                    "Registre a descrição técnica antes de concluir.");
        }
        Instant agora = Instant.now(clock);
        atendimento.concluir(agora);
        transicao.transicionar(chamado, EstadoChamado.RESOLVIDO, tecnico);
        relogios.cumprir(chamadoId, TipoRelogio.RESOLUCAO, agora).violacao().ifPresent(v -> notificador.notificar(chamado, List.of(v)));
        EventoNotificacao.Chamado ref = new EventoNotificacao.Chamado(chamadoId, chamado.getNumero());
        outbox.registrar(new EventoNotificacao.Resolucao(ref, chamado.getTitulo(), destinatarios.emailDoCliente(chamado.getClienteId())));
        // Gestor inativado com a preferência ainda gravada não é avisado.
        Set<UUID> ativos = Set.copyOf(usuarios.listarGestoresAtivos());
        List<UUID> gestores = preferencias.gestoresComPreferencia(chamadoId).stream().filter(ativos::contains).toList();
        if (!gestores.isEmpty()) {
            outbox.registrar(new EventoNotificacao.Conclusao(ref, chamado.getTitulo(), destinatarios.internos(gestores)));
        }
    }

    /** Chamado atribuído ao técnico do token; para qualquer outro, inexistente (RN-126). */
    private Chamado doTecnico(UUID chamadoId, Ator tecnico) {
        return chamados.findById(chamadoId).filter(c -> tecnico.sub().equals(c.getTecnicoSub()))
                .orElseThrow(RecursoNaoEncontradoException::new);
    }

    /** Novo atendimento PENDENTE do técnico, em cada reabertura (RN-95). */
    @Transactional(propagation = Propagation.MANDATORY)
    Atendimento criarPendente(UUID chamadoId, UUID tecnicoSub) {
        return atendimentos.save(new Atendimento(chamadoId, tecnicoSub));
    }

    /** Interrompe o corrente, se houver, preservando as evidências (RN-21). */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Atendimento> interromperCorrente(UUID chamadoId, Instant instante) {
        Optional<Atendimento> corrente = atendimentos.findByChamadoIdAndEstadoIn(chamadoId, CORRENTES);
        corrente.ifPresent(a -> a.interromper(instante));
        return corrente;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AtendimentoCorrente> correnteDe(UUID chamadoId) {
        return atendimentos.findByChamadoIdAndEstadoIn(chamadoId, CORRENTES).map(Atendimento::corrente);
    }

    @Transactional(readOnly = true)
    List<Atendimento> doChamado(UUID chamadoId) {
        return atendimentos.findByChamadoIdOrderByCreatedAtAscIdAsc(chamadoId);
    }
}
