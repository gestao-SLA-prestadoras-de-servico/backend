package com.gestaosla.backend.sla.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.sla.api.ApuracaoChamado;
import com.gestaosla.backend.sla.api.ResultadoApuracao;
import com.gestaosla.backend.sla.api.TipoRelogio;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Resultado congelado de um relógio no encerramento (RN-68). {@code @Immutable} e trigger no banco: nunca muda. Sem
 * {@code EntidadeBase}: registro imutável não tem updated_at.
 */
@Entity
@Immutable
@Table(name = "apuracao_sla")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ApuracaoRelogio {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID chamadoId;

    @Enumerated(EnumType.STRING)
    private TipoRelogio tipo;

    private UUID clienteId;

    @Enumerated(EnumType.STRING)
    private Prioridade prioridade;

    private UUID tecnicoSub;

    private int prazoMinutos;

    private Instant venceEm;

    private Instant cumpridoEm;

    private Instant violadoEm;

    @Enumerated(EnumType.STRING)
    private ResultadoApuracao resultado;

    private boolean violouEmCicloAnterior;

    private Instant congeladaEm;

    ApuracaoRelogio(RelogioSla ultimoCiclo, boolean violouEmCicloAnterior, UUID clienteId, Prioridade prioridade,
            UUID tecnicoSub, Instant congeladaEm) {
        this.chamadoId = ultimoCiclo.getChamadoId();
        this.tipo = ultimoCiclo.getTipo();
        this.clienteId = clienteId;
        this.prioridade = prioridade;
        this.tecnicoSub = tecnicoSub;
        this.prazoMinutos = ultimoCiclo.getPrazoMinutos();
        this.venceEm = ultimoCiclo.getVenceEm();
        this.cumpridoEm = ultimoCiclo.getCumpridoEm();
        this.violadoEm = ultimoCiclo.getVioladoEm();
        this.violouEmCicloAnterior = violouEmCicloAnterior;
        // RN-69 em todos os ciclos: a violação de um ciclo anterior conta na apuração final.
        this.resultado = violadoEm != null || violouEmCicloAnterior ? ResultadoApuracao.VIOLADO : ResultadoApuracao.CUMPRIDO;
        this.congeladaEm = congeladaEm;
    }

    ApuracaoChamado.Relogio view() {
        return new ApuracaoChamado.Relogio(tipo, prazoMinutos, venceEm, cumpridoEm, violadoEm, resultado, violouEmCicloAnterior);
    }
}
