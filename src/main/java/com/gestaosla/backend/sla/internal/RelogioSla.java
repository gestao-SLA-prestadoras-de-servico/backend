package com.gestaosla.backend.sla.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.gestaosla.backend.shared.api.EntidadeBase;
import com.gestaosla.backend.sla.api.EstadoRelogio;
import com.gestaosla.backend.sla.api.RelogioView;
import com.gestaosla.backend.sla.api.ResultadoCumprimento;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Um relógio de SLA de um ciclo do chamado (RN-23 a RN-28). As regras de cumprimento e violação ficam aqui. Público só
 * para o teste em unit/; fora do módulo, o Modulith barra.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelogioSla extends EntidadeBase {

    private UUID chamadoId;

    @Enumerated(EnumType.STRING)
    private TipoRelogio tipo;

    private int ciclo;

    private boolean ativo;

    private Instant marcoInicial;

    private int prazoMinutos;

    private Instant venceEm;

    private Instant cumpridoEm;

    private Instant violadoEm;

    @Version
    private long version;

    public RelogioSla(UUID chamadoId, TipoRelogio tipo, int ciclo, Instant marcoInicial, int prazoMinutos, Instant venceEm) {
        this.chamadoId = chamadoId;
        this.tipo = tipo;
        this.ciclo = ciclo;
        this.ativo = true;
        this.marcoInicial = marcoInicial;
        this.prazoMinutos = prazoMinutos;
        this.venceEm = venceEm;
    }

    /**
     * Só o primeiro evento do ciclo cumpre o relógio. Cumprir exatamente no vencimento ainda está no prazo: a violação é
     * o prazo ultrapassado (RN-65).
     */
    public ResultadoCumprimento cumprir(Instant instante) {
        if (cumpridoEm != null) {
            return new ResultadoCumprimento(false, Optional.empty());
        }
        cumpridoEm = instante;
        Optional<Violacao> violacao = instante.isAfter(venceEm) ? marcarViolacao() : Optional.empty();
        return new ResultadoCumprimento(true, violacao);
    }

    /**
     * Único ponto que marca violação: no cumprimento atrasado, no recálculo para um prazo já vencido e na rotina de 15
     * minutos. Grava o horário real do estouro, o vencimento, não o da detecção (RN-67).
     *
     * @return a violação, se foi marcada agora; vazio se já estava marcada (a mesma violação não gera dois avisos)
     */
    public Optional<Violacao> marcarViolacao() {
        if (violadoEm != null) {
            return Optional.empty();
        }
        violadoEm = venceEm;
        return Optional.of(new Violacao(chamadoId, tipo, ciclo, marcoInicial, violadoEm));
    }

    /** Violado continua VIOLADO mesmo se cumprido depois (RN-69). */
    public EstadoRelogio estado() {
        if (violadoEm != null) {
            return EstadoRelogio.VIOLADO;
        }
        return cumpridoEm != null ? EstadoRelogio.CUMPRIDO : EstadoRelogio.CORRENDO;
    }

    RelogioView view() {
        return new RelogioView(tipo, ciclo, ativo, marcoInicial, prazoMinutos, venceEm, cumpridoEm, violadoEm, estado());
    }
}
