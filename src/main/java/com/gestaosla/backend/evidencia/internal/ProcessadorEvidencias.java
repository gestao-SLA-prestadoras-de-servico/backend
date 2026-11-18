package com.gestaosla.backend.evidencia.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor interno das mensagens {@code PROCESSAR_EVIDENCIA} (RF-61, RN-84). Concorrência e prefetch vêm de
 * {@code spring.rabbitmq.listener.simple.*}: este é o único listener do app; se surgir outro, ele ganha fábrica própria.
 * <p>
 * O ack é o automático do container e sai quando este método retorna, isto é, depois do commit do resultado (RN-90).
 * Esgotadas as tentativas, ou com imagem ilegível, a evidência vai para FALHA (RN-88) e a mensagem é rejeitada sem
 * requeue: a DLX da topologia a encaminha à {@code evidencias.processamento.dlq} (RF-61). Nada aqui toca no chamado nem
 * no atendimento (RN-89).
 */
@Component
@RequiredArgsConstructor
class ProcessadorEvidencias {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorEvidencias.class);

    private final GeradorDerivados gerador;
    private final EvidenciaRepository evidencias;
    private final JsonMapper json;
    private final Clock clock;

    @RabbitListener(queues = "evidencias.processamento")
    void processar(Message mensagem) {
        UUID id = idDe(mensagem);
        try {
            gerador.gerar(id);
        } catch (RuntimeException e) {
            marcarFalha(id);
            throw new AmqpRejectAndDontRequeueException("Falha ao processar a evidência " + id, e);
        }
    }

    // Se nem o banco responde, a evidência segue PROCESSANDO e a mensagem vai à DLQ do mesmo jeito: reprocessar a
    // partir da DLQ reivindica de novo (PROCESSANDO é aceito).
    private void marcarFalha(UUID id) {
        try {
            if (evidencias.falhar(id, Instant.now(clock)) > 0) {
                log.error("Evidência {} em FALHA; a mensagem vai para a DLQ", id);
            }
        } catch (RuntimeException e) {
            log.error("Não foi possível marcar a evidência {} como FALHA", id, e);
        }
    }

    private UUID idDe(Message mensagem) {
        try {
            return UUID.fromString(json.readTree(mensagem.getBody()).get("evidenciaId").asString());
        } catch (RuntimeException e) {
            throw new AmqpRejectAndDontRequeueException("Mensagem de evidência sem evidenciaId válido", e);
        }
    }
}
