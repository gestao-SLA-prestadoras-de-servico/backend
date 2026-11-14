package com.gestaosla.backend.chamado.internal;

import java.time.Instant;
import java.util.UUID;

import com.gestaosla.backend.chamado.api.AtendimentoCorrente;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.shared.api.EntidadeBase;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Uma passagem do técnico pelo chamado (RN-70); as evidências pertencem a ele (RN-71). Nunca é apagado: interromper só
 * muda o estado, e as evidências continuam ligadas a ele (RN-21). Público só para o teste em unit/.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Atendimento extends EntidadeBase {

    private UUID chamadoId;

    private UUID tecnicoSub;

    @Enumerated(EnumType.STRING)
    private EstadoAtendimento estado;

    private Instant iniciadoEm;

    private Instant encerradoEm;

    private String descricaoTecnica;

    @Version
    private long version;

    public Atendimento(UUID chamadoId, UUID tecnicoSub) {
        this.chamadoId = chamadoId;
        this.tecnicoSub = tecnicoSub;
        this.estado = EstadoAtendimento.PENDENTE;
    }

    /**
     * Encerra como interrompido, na reatribuição e no cancelamento (RN-21).
     *
     * @throws IllegalStateException atendimento já concluído ou interrompido
     */
    public void interromper(Instant instante) {
        if (!estado.corrente()) {
            throw new IllegalStateException("Atendimento " + getId() + " já está " + estado + ".");
        }
        estado = EstadoAtendimento.INTERROMPIDO;
        encerradoEm = instante;
    }

    AtendimentoCorrente corrente() {
        return new AtendimentoCorrente(getId(), chamadoId, tecnicoSub, estado);
    }
}
