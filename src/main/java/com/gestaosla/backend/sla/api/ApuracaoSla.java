package com.gestaosla.backend.sla.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gestaosla.backend.cadastro.api.Prioridade;

/** Apuração dos SLAs no encerramento (RF-36, RN-68). */
public interface ApuracaoSla {

    /**
     * Fotografa os três relógios do chamado no encerramento, na transação de quem chama. Os dados do chamado vêm do
     * chamador: o sla não depende do chamado. Relógio ainda correndo com o prazo vencido é violado antes de congelar.
     * Chamar de novo não faz nada: a apuração gravada nunca muda. Chamado cancelado não deve ser congelado (RN-19).
     *
     * @param tecnicoSub técnico responsável no encerramento, que leva o crédito no relatório; {@code null} se não houver
     * @return violações marcadas agora; o chamador grava os avisos
     */
    List<Violacao> congelarApuracao(UUID chamadoId, UUID clienteId, Prioridade prioridade, UUID tecnicoSub);

    /** Apuração congelada; vazia se o chamado não foi encerrado ou foi cancelado (RN-19). */
    Optional<ApuracaoChamado> consultarApuracao(UUID chamadoId);
}
