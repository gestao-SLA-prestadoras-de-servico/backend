package com.gestaosla.backend.sla.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.sla.api.ApuracaoChamado;
import com.gestaosla.backend.sla.api.ApuracaoSla;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class ApuracaoSlaService implements ApuracaoSla {

    private final RelogioSlaRepository relogios;
    private final ApuracaoRelogioRepository apuracoes;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Violacao> congelarApuracao(UUID chamadoId, UUID clienteId, Prioridade prioridade, UUID tecnicoSub) {
        if (apuracoes.existsByChamadoId(chamadoId)) {
            return List.of();
        }
        Map<TipoRelogio, List<RelogioSla>> porTipo = relogios.findByChamadoId(chamadoId).stream()
                .collect(Collectors.groupingBy(RelogioSla::getTipo));
        if (porTipo.size() != TipoRelogio.values().length) {
            throw new IllegalStateException("Chamado " + chamadoId + " não tem os três relógios para apurar.");
        }
        Instant agora = Instant.now(clock);
        List<Violacao> violacoes = new ArrayList<>();
        for (TipoRelogio tipo : TipoRelogio.values()) {
            List<RelogioSla> ciclos = porTipo.get(tipo);
            RelogioSla ultimo = ciclos.stream().filter(RelogioSla::isAtivo).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Chamado " + chamadoId + " sem relógio " + tipo + " ativo."));
            // Correndo com o prazo vencido: a rotina de 15 minutos ainda não passou; viola antes de fotografar.
            if (ultimo.correndo() && agora.isAfter(ultimo.getVenceEm())) {
                ultimo.marcarViolacao().ifPresent(violacoes::add);
            }
            boolean violouAntes = ciclos.stream().anyMatch(r -> !r.isAtivo() && r.getVioladoEm() != null);
            apuracoes.save(new ApuracaoRelogio(ultimo, violouAntes, clienteId, prioridade, tecnicoSub, agora));
        }
        return violacoes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApuracaoChamado> consultarApuracao(UUID chamadoId) {
        List<ApuracaoRelogio> linhas = apuracoes.findByChamadoId(chamadoId).stream()
                .sorted(Comparator.comparing(ApuracaoRelogio::getTipo)).toList();
        if (linhas.isEmpty()) {
            return Optional.empty();
        }
        ApuracaoRelogio primeira = linhas.get(0);
        return Optional.of(new ApuracaoChamado(chamadoId, primeira.getClienteId(), primeira.getPrioridade(),
                primeira.getTecnicoSub(), primeira.getCongeladaEm(), linhas.stream().map(ApuracaoRelogio::view).toList()));
    }
}
