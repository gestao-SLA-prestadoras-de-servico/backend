package com.gestaosla.backend.chamado.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Generated;

import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.EntidadeBase;
import com.gestaosla.backend.shared.api.RegraNegocioException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Chamado (RF-12). A máquina de estados fica aqui, não no controller: nenhuma tela ou rotina leva o chamado a um estado
 * inválido (RN-10, RN-13). Toda mutação usa bloqueio otimista (RN-117). Público só para o teste em unit/; fora do
 * módulo, o Modulith barra.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chamado extends EntidadeBase {

    /** Número legível (RN-14), da sequência {@code chamado_numero_seq}. */
    @Generated
    @Column(insertable = false, updatable = false)
    private Long numero;

    private UUID contratoId;

    private UUID clienteId;

    /** Versão da política vigente na abertura (RN-05). */
    private UUID politicaVersaoId;

    @Enumerated(EnumType.STRING)
    private Prioridade prioridadePadrao;

    private String titulo;

    private String descricao;

    private String local;

    @Enumerated(EnumType.STRING)
    private Prioridade prioridadeSugerida;

    @Enumerated(EnumType.STRING)
    private Prioridade prioridadeDefinida;

    @Enumerated(EnumType.STRING)
    private EstadoChamado estado;

    private UUID tecnicoSub;

    private String posicionamento;

    private Instant posicionadoEm;

    private Instant abertoEm;

    private Instant resolvidoEm;

    private Instant avisoEnviadoEm;

    private Instant estadoFinalEm;

    private String motivoCancelamento;

    @Version
    private long version;

    // Lidos de relogio_sla, só para filtrar e ordenar a listagem no banco. O sla continua dono da tabela; o chamado não
    // grava nela.
    @Formula("(exists (select 1 from relogio_sla r where r.chamado_id = id and r.violado_em is not null))")
    private boolean comViolacao;

    @Formula("(select min(r.vence_em) from relogio_sla r where r.chamado_id = id and r.ativo"
            + " and r.cumprido_em is null and r.violado_em is null)")
    private Instant proximoVencimento;

    public Chamado(UUID contratoId, UUID clienteId, PoliticaDoContrato versao, String titulo, String descricao, String local,
            Prioridade prioridadeSugerida, Instant abertoEm) {
        this.contratoId = contratoId;
        this.clienteId = clienteId;
        this.politicaVersaoId = versao.versaoId();
        this.prioridadePadrao = versao.prioridadePadrao();
        this.titulo = titulo;
        this.descricao = descricao;
        this.local = local;
        this.prioridadeSugerida = prioridadeSugerida;
        this.estado = EstadoChamado.ABERTO;
        this.abertoEm = abertoEm;
    }

    /** A do gestor ou, enquanto ele não define, a padrão da versão da política (RN-15, RN-16). */
    public Prioridade prioridadeEfetiva() {
        return prioridadeDefinida != null ? prioridadeDefinida : prioridadePadrao;
    }

    void definirPrioridade(Prioridade prioridade) {
        this.prioridadeDefinida = prioridade;
    }

    /**
     * Muda o estado (RN-10). Fora da máquina, lança {@code TRANSICAO_INVALIDA} (RN-13); estados finais não aceitam
     * nenhuma transição (RN-11). Só a {@link TransicaoChamado} chama, para a auditoria e o histórico não faltarem.
     *
     * @return o estado anterior
     */
    public EstadoChamado transicionar(EstadoChamado destino, Instant instante) {
        if (!estado.podeIrPara(destino)) {
            throw new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA,
                    "Transição de " + estado + " para " + destino + " não é permitida.");
        }
        EstadoChamado anterior = estado;
        estado = destino;
        if (destino == EstadoChamado.RESOLVIDO) {
            resolvidoEm = instante;
        }
        if (destino.finalizado()) {
            estadoFinalEm = instante; // início dos 180 dias de retenção das evidências (RN-100)
        }
        return anterior;
    }
}
