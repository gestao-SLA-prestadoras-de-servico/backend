package com.gestaosla.backend.chamado.internal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.api.AcessoChamado;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.chamado.api.EvidenciasDoChamado;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.Paginas;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.sla.api.RelogioView;
import com.gestaosla.backend.sla.api.RelogiosSla;

/**
 * Consultas com isolamento por perfil (RF-03, RF-04, RN-126). O escopo sai sempre do usuário autenticado, nunca de
 * parâmetro da requisição: o gestor vê todos, o cliente os da própria empresa e o técnico os atribuídos a ele agora.
 * Chamado fora do escopo responde como inexistente (404).
 */
@Service
class ConsultaChamadoService implements AcessoChamado {

    /** Filtros da listagem. {@code clienteId} e {@code tecnicoSub} só valem para o gestor. */
    record Filtro(EstadoChamado estado, Prioridade prioridade, Boolean comViolacao, UUID clienteId, UUID tecnicoSub) {
    }

    /** {@code notificarConclusao}: a preferência do gestor corrente (RN-40); nula para os outros perfis e na listagem. */
    record Detalhe(Chamado chamado, List<RelogioView> relogios, List<Atendimento> atendimentos, Map<UUID, Long> evidencias,
            Boolean notificarConclusao) {
    }

    private final ChamadoRepository chamados;
    private final AtendimentoService atendimentos;
    private final RelogiosSla relogios;
    private final EventoHistoricoRepository historico;
    private final PreferenciaNotificacaoService preferencias;
    private final Optional<EvidenciasDoChamado> evidencias; // Vazio até o módulo evidencia existir: contagem 0.

    ConsultaChamadoService(ChamadoRepository chamados, AtendimentoService atendimentos, RelogiosSla relogios,
            EventoHistoricoRepository historico, PreferenciaNotificacaoService preferencias,
            Optional<EvidenciasDoChamado> evidencias) {
        this.preferencias = preferencias;
        this.chamados = chamados;
        this.atendimentos = atendimentos;
        this.relogios = relogios;
        this.historico = historico;
        this.evidencias = evidencias;
    }

    @Transactional(readOnly = true)
    Page<Chamado> listar(UsuarioCorrente usuario, Filtro filtro, Pageable pagina) {
        Specification<Chamado> spec = escopo(usuario);
        if (filtro.estado() != null) {
            spec = spec.and((c, q, cb) -> cb.equal(c.get("estado"), filtro.estado()));
        }
        if (filtro.prioridade() != null) {
            // Prioridade efetiva: a do gestor ou a padrão da versão (RN-16).
            spec = spec.and((c, q, cb) -> cb.equal(
                    cb.coalesce(c.<Prioridade>get("prioridadeDefinida"), c.<Prioridade>get("prioridadePadrao")), filtro.prioridade()));
        }
        if (filtro.comViolacao() != null) {
            spec = spec.and((c, q, cb) -> cb.equal(c.get("comViolacao"), filtro.comViolacao()));
        }
        if (usuario.papel() == Papel.GESTOR && filtro.clienteId() != null) {
            spec = spec.and((c, q, cb) -> cb.equal(c.get("clienteId"), filtro.clienteId()));
        }
        if (usuario.papel() == Papel.GESTOR && filtro.tecnicoSub() != null) {
            spec = spec.and((c, q, cb) -> cb.equal(c.get("tecnicoSub"), filtro.tecnicoSub()));
        }
        return chamados.findAll(spec, Paginas.comDesempate(pagina));
    }

    /** Quantidade por estado, no escopo do usuário: alimenta as abas da listagem. */
    @Transactional(readOnly = true)
    Map<EstadoChamado, Long> contarPorEstado(UsuarioCorrente usuario) {
        Map<EstadoChamado, Long> contagem = new LinkedHashMap<>();
        // ponytail: uma contagem por estado (6 consultas); um GROUP BY se as abas pesarem.
        Arrays.stream(EstadoChamado.values()).forEach(estado -> contagem.put(estado,
                chamados.count(escopo(usuario).and((c, q, cb) -> cb.equal(c.get("estado"), estado)))));
        return contagem;
    }

    @Transactional(readOnly = true)
    Detalhe detalhar(UsuarioCorrente usuario, UUID chamadoId) {
        Chamado chamado = buscarVisivel(usuario, chamadoId);
        return new Detalhe(chamado, relogios.relogiosAtivos(chamadoId), atendimentos.doChamado(chamadoId),
                evidencias.map(e -> e.contarPorAtendimento(chamadoId)).orElse(Map.of()),
                usuario.papel() == Papel.GESTOR ? preferencias.ativa(chamadoId, usuario.sub()) : null);
    }

    /** Linha do tempo visível ao perfil; o gestor vê todos os eventos. */
    @Transactional(readOnly = true)
    List<EventoHistorico> historico(UsuarioCorrente usuario, UUID chamadoId) {
        buscarVisivel(usuario, chamadoId);
        return historico.findByChamadoIdOrderByOcorridoEmAscIdAsc(chamadoId).stream()
                .filter(e -> switch (usuario.papel()) {
                    case GESTOR -> true;
                    case CLIENTE -> e.isVisivelCliente();
                    case TECNICO -> e.isVisivelTecnico();
                }).toList();
    }

    @Transactional(readOnly = true)
    Chamado buscarVisivel(UsuarioCorrente usuario, UUID chamadoId) {
        return chamados.findOne(escopo(usuario).and(comId(chamadoId))).orElseThrow(RecursoNaoEncontradoException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean podeAcessar(UsuarioCorrente usuario, UUID chamadoId) {
        return chamados.exists(escopo(usuario).and(comId(chamadoId)));
    }

    private static Specification<Chamado> comId(UUID chamadoId) {
        return (c, q, cb) -> cb.equal(c.get("id"), chamadoId);
    }

    private static Specification<Chamado> escopo(UsuarioCorrente usuario) {
        if (usuario.papel() == null) {
            return (c, q, cb) -> cb.disjunction();
        }
        return switch (usuario.papel()) {
            case GESTOR -> Specification.unrestricted();
            case CLIENTE -> usuario.empresaClienteId() == null
                    ? (c, q, cb) -> cb.disjunction()
                    : (c, q, cb) -> cb.equal(c.get("clienteId"), usuario.empresaClienteId());
            // O técnico atual: removido por reatribuição, perde o acesso na hora.
            case TECNICO -> (c, q, cb) -> cb.equal(c.get("tecnicoSub"), usuario.sub());
        };
    }
}
