package com.gestaosla.backend.sla.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gestaosla.backend.cadastro.api.Prioridade;

/** Apuração congelada de um chamado encerrado (RF-37). */
public record ApuracaoChamado(UUID chamadoId, UUID clienteId, Prioridade prioridade, UUID tecnicoSub, Instant congeladaEm,
        List<Relogio> relogios) {

    /**
     * @param violouEmCicloAnterior a violação veio de um ciclo anterior à última reabertura; o resultado é VIOLADO
     *                              mesmo que o último ciclo tenha sido cumprido no prazo
     */
    public record Relogio(TipoRelogio tipo, int prazoMinutos, Instant venceEm, Instant cumpridoEm, Instant violadoEm,
            ResultadoApuracao resultado, boolean violouEmCicloAnterior) {
    }
}
