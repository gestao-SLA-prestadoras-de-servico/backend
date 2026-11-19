package com.gestaosla.backend.sla.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * Recalcula os relógios ainda correndo quando a prioridade é definida ou alterada (RF-31, RN-17): novo prazo da
     * versão informada, contado do marco inicial vigente de cada relógio. Cumpridos, violados e inativos não mudam
     * (RN-18). A versão é a guardada no chamado ({@code ContratoConsulta.versaoDaPolitica}), não a vigente do contrato.
     *
     * @return violações marcadas agora, quando o novo vencimento já passou; o chamador grava os avisos
     */
    List<Violacao> recalcularPorPrioridade(UUID chamadoId, PoliticaDoContrato versao, Prioridade novaPrioridade);

    /**
     * Reabertura (RF-32, RN-96, RN-97): encerra o ciclo atual de CAMPO e RESOLUCAO, que fica como histórico, e cria o
     * ciclo seguinte com o marco na reabertura. RESPOSTA não muda.
     *
     * @return violações do ciclo encerrado que ainda não tinham sido marcadas (prazo vencido sem a rotina ter passado)
     */
    List<Violacao> reiniciarParaReabertura(UUID chamadoId, PoliticaDoContrato versao, Prioridade prioridade, Instant instante);

    /**
     * Relógios correndo com o prazo vencido antes de {@code agora}, em ordem de vencimento e id, depois do cursor
     * ({@code aposVenceEm}, {@code aposId}). Ficam travados até o fim da transação de quem chama; os que outra transação
     * está alterando ficam de fora e voltam na próxima passada (SKIP LOCKED). Para a rotina de 15 minutos (RF-34).
     */
    List<RelogioVencido> travarVencidos(Instant agora, Instant aposVenceEm, UUID aposId, int limite);

    /**
     * Marca a violação de um relógio vencido (RN-67: {@code violado_em} = vencimento).
     *
     * @return a violação, se foi marcada agora; vazio se já estava marcada
     */
    Optional<Violacao> marcarViolacao(UUID relogioId);

    /** Relógios ativos do chamado (um por tipo), com o estado derivado. Vazio se o chamado não tem relógios. */
    List<RelogioView> relogiosAtivos(UUID chamadoId);

    /** Todos os relógios do chamado, ativos e de ciclos anteriores, por tipo e ciclo. */
    List<RelogioView> historico(UUID chamadoId);
}
