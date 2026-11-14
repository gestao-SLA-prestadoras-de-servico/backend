package com.gestaosla.backend.sla.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prioridade;

/**
 * Relógios de SLA de um chamado. Os métodos de escrita rodam na transação de quem chama (o chamado) e falham fora de
 * uma, com {@link org.springframework.transaction.IllegalTransactionStateException}.
 */
public interface RelogiosSla {

    /**
     * Cria os três relógios do primeiro ciclo, com o vencimento calculado pela versão da política e pela prioridade
     * (RF-30, RN-28). O chamador passa a versão que vai guardar no chamado (RN-05).
     *
     * @param instante abertura do chamado; fora da janela, o prazo começa a correr na próxima abertura (RN-61)
     */
    List<RelogioView> iniciarRelogios(UUID chamadoId, PoliticaDoContrato versao, Prioridade prioridade, Instant instante);

    /**
     * Registra o evento que cumpre o relógio ativo do tipo. Só o primeiro evento do ciclo conta. Cumprimento depois do
     * prazo marca a violação, se a rotina de 15 minutos ainda não a marcou, e a devolve para o chamador avisar (RF-33).
     *
     * @throws IllegalStateException o chamado não tem relógio ativo desse tipo
     */
    ResultadoCumprimento cumprir(UUID chamadoId, TipoRelogio tipo, Instant instante);

    /** Relógios ativos do chamado (um por tipo), com o estado derivado. Vazio se o chamado não tem relógios. */
    List<RelogioView> relogiosAtivos(UUID chamadoId);
}
