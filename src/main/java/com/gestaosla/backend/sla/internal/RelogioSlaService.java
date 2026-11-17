package com.gestaosla.backend.sla.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prazos;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.sla.api.CalendarioJanela;
import com.gestaosla.backend.sla.api.RelogioView;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.ResultadoCumprimento;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class RelogioSlaService implements RelogiosSla {

    // Ordem de exibição: a do enum (RESPOSTA, CAMPO, RESOLUCAO), depois o ciclo.
    private static final Comparator<RelogioSla> POR_TIPO_E_CICLO =
            Comparator.comparing(RelogioSla::getTipo).thenComparingInt(RelogioSla::getCiclo);

    private final RelogioSlaRepository relogios;
    private final CalendarioJanela calendario;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RelogioView> iniciarRelogios(UUID chamadoId, PoliticaDoContrato versao, Prioridade prioridade, Instant instante) {
        Prazos prazos = versao.prazos(prioridade);
        return Arrays.stream(TipoRelogio.values())
                .map(tipo -> relogios.save(novo(chamadoId, tipo, 1, prazos, instante)).view())
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ResultadoCumprimento cumprir(UUID chamadoId, TipoRelogio tipo, Instant instante) {
        return ativo(chamadoId, tipo).cumprir(instante);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Violacao> recalcularPorPrioridade(UUID chamadoId, PoliticaDoContrato versao, Prioridade novaPrioridade) {
        Prazos prazos = versao.prazos(novaPrioridade);
        Instant agora = Instant.now(clock);
        List<Violacao> violacoes = new ArrayList<>();
        for (RelogioSla relogio : relogios.findByChamadoIdAndAtivoTrue(chamadoId)) {
            if (relogio.correndo()) {
                int prazo = relogio.getTipo().prazoEm(prazos);
                relogio.recalcular(prazo, calendario.adicionarMinutos(relogio.getMarcoInicial(), prazo), agora)
                        .ifPresent(violacoes::add);
            }
        }
        return violacoes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Violacao> reiniciarParaReabertura(UUID chamadoId, PoliticaDoContrato versao, Prioridade prioridade,
            Instant instante) {
        Prazos prazos = versao.prazos(prioridade);
        List<Violacao> violacoes = new ArrayList<>();
        List<RelogioSla> novos = new ArrayList<>();
        for (TipoRelogio tipo : List.of(TipoRelogio.CAMPO, TipoRelogio.RESOLUCAO)) {
            RelogioSla atual = ativo(chamadoId, tipo);
            atual.encerrarCiclo(instante).ifPresent(violacoes::add);
            novos.add(novo(chamadoId, tipo, atual.getCiclo() + 1, prazos, instante));
        }
        // Grava a inativação antes de inserir o ciclo novo: o índice único parcial admite um só ativo por tipo, e o
        // Hibernate, sem o flush, faria os INSERTs antes dos UPDATEs.
        relogios.flush();
        relogios.saveAll(novos);
        return violacoes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelogioView> relogiosAtivos(UUID chamadoId) {
        return relogios.findByChamadoIdAndAtivoTrue(chamadoId).stream().sorted(POR_TIPO_E_CICLO).map(RelogioSla::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelogioView> historico(UUID chamadoId) {
        return relogios.findByChamadoId(chamadoId).stream().sorted(POR_TIPO_E_CICLO).map(RelogioSla::view).toList();
    }

    private RelogioSla ativo(UUID chamadoId, TipoRelogio tipo) {
        return relogios.findByChamadoIdAndTipoAndAtivoTrue(chamadoId, tipo)
                .orElseThrow(() -> new IllegalStateException("Chamado " + chamadoId + " sem relógio " + tipo + " ativo."));
    }

    private RelogioSla novo(UUID chamadoId, TipoRelogio tipo, int ciclo, Prazos prazos, Instant marco) {
        int prazo = tipo.prazoEm(prazos);
        return new RelogioSla(chamadoId, tipo, ciclo, marco, prazo, calendario.adicionarMinutos(marco, prazo));
    }
}
