package com.gestaosla.backend.sla.internal;

import java.time.Instant;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class RelogioSlaService implements RelogiosSla {

    private final RelogioSlaRepository relogios;
    private final CalendarioJanela calendario;

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
        return relogios.findByChamadoIdAndTipoAndAtivoTrue(chamadoId, tipo)
                .orElseThrow(() -> new IllegalStateException("Chamado " + chamadoId + " sem relógio " + tipo + " ativo."))
                .cumprir(instante);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelogioView> relogiosAtivos(UUID chamadoId) {
        return relogios.findByChamadoIdAndAtivoTrue(chamadoId).stream()
                .sorted(Comparator.comparing(RelogioSla::getTipo)).map(RelogioSla::view).toList();
    }

    private RelogioSla novo(UUID chamadoId, TipoRelogio tipo, int ciclo, Prazos prazos, Instant marco) {
        int prazo = tipo.prazoEm(prazos);
        return new RelogioSla(chamadoId, tipo, ciclo, marco, prazo, calendario.adicionarMinutos(marco, prazo));
    }
}
