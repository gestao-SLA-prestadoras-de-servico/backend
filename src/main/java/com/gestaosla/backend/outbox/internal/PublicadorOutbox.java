package com.gestaosla.backend.outbox.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Publica os eventos pendentes da tabela de saída (RF-50, RN-114): em ordem de criação, com confirmação do broker, e
 * só então marca o evento como publicado. Broker fora ou sem confirmação para o ciclo. Devolução ou nack é problema de
 * um destino só: o evento fica pendente, os seguintes do mesmo destino esperam (a ordem por fila se mantém) e os de
 * outros destinos seguem (RN-48). Se a aplicação cair entre a confirmação e a marcação, o evento sai de novo: entrega
 * ao menos uma vez (RN-115).
 */
@Component
@RequiredArgsConstructor
class PublicadorOutbox {

    private static final Logger log = LoggerFactory.getLogger(PublicadorOutbox.class);

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    private final PublicadorOutboxProperties propriedades;

    record Pendente(UUID id, UUID eventoId, String tipo, String exchange, String routingKey, String payload,
            OffsetDateTime criadoEm) {

        String destino() {
            return exchange + "/" + routingKey;
        }
    }

    private enum Resultado { PUBLICADO, DESTINO_FALHOU, BROKER_FALHOU }

    // Esvazia em lotes: continua enquanto vierem lotes cheios, para o acúmulo sair no mesmo ciclo quando o broker volta.
    // Sem transação: cada evento é marcado assim que confirmado, sem segurar conexão enquanto espera o broker.
    @Scheduled(initialDelayString = "${app.outbox.publicador.intervalo}",
            fixedDelayString = "${app.outbox.publicador.intervalo}")
    void publicarPendentes() {
        Set<String> destinosComFalha = new HashSet<>();
        // Keyset a partir do último lido: os pendentes que ficaram para trás não voltam no mesmo ciclo, senão um lote
        // cheio só de eventos pulados seria relido para sempre.
        OffsetDateTime aposCriadoEm = Instant.EPOCH.atOffset(ZoneOffset.UTC);
        UUID aposId = new UUID(0, 0);
        List<Pendente> lote;
        do {
            lote = jdbc.sql("""
                    select id, evento_id, tipo, exchange, routing_key, payload::text as payload, criado_em
                    from outbox_eventos where publicado_em is null and (criado_em, id) > (?, ?)
                    order by criado_em, id limit ?""")
                    .params(aposCriadoEm, aposId, propriedades.tamanhoLote()).query(Pendente.class).list();
            for (Pendente evento : lote) {
                aposCriadoEm = evento.criadoEm();
                aposId = evento.id();
                if (destinosComFalha.contains(evento.destino())) {
                    continue;
                }
                switch (publicar(evento)) {
                    case BROKER_FALHOU -> {
                        return;
                    }
                    case DESTINO_FALHOU -> destinosComFalha.add(evento.destino());
                    case PUBLICADO -> { }
                }
            }
        } while (lote.size() == propriedades.tamanhoLote());
    }

    private Resultado publicar(Pendente evento) {
        String erro;
        Resultado resultado = Resultado.DESTINO_FALHOU;
        try {
            CorrelationData confirmacao = new CorrelationData(evento.eventoId().toString());
            rabbit.send(evento.exchange(), evento.routingKey(), mensagem(evento), confirmacao);
            var resposta = confirmacao.getFuture()
                    .get(propriedades.esperaConfirmacao().toMillis(), TimeUnit.MILLISECONDS);
            // mandatory=true: sem fila de destino o broker devolve a mensagem (antes do ack) e a confirma mesmo assim.
            if (confirmacao.getReturned() != null) {
                erro = "Sem fila de destino: " + confirmacao.getReturned().getReplyText();
            } else if (!resposta.ack()) {
                erro = "Recusada pelo broker: " + resposta.reason();
            } else {
                jdbc.sql("update outbox_eventos set publicado_em = ? where id = ?")
                        .params(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC), evento.id())
                        .update();
                return Resultado.PUBLICADO;
            }
        } catch (AmqpException | ExecutionException e) {
            erro = "Broker indisponível: " + e.getMessage();
            resultado = Resultado.BROKER_FALHOU;
        } catch (TimeoutException e) {
            erro = "Sem confirmação do broker em " + propriedades.esperaConfirmacao();
            resultado = Resultado.BROKER_FALHOU;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            erro = "Publicação interrompida";
            resultado = Resultado.BROKER_FALHOU;
        }
        jdbc.sql("update outbox_eventos set tentativas = tentativas + 1, ultimo_erro = ? where id = ?")
                .params(erro, evento.id()).update();
        log.warn("Evento {} da outbox não publicado ({}); tenta de novo no próximo ciclo: {}", evento.eventoId(),
                resultado == Resultado.BROKER_FALHOU ? "o ciclo para" : "o destino " + evento.destino() + " espera",
                erro);
        return resultado;
    }

    private static Message mensagem(Pendente evento) {
        MessageProperties propriedades = new MessageProperties();
        // Os consumidores descartam repetições pelo eventoId (RN-55, RN-115).
        propriedades.setMessageId(evento.eventoId().toString());
        propriedades.setType(evento.tipo());
        propriedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propriedades.setContentEncoding(StandardCharsets.UTF_8.name());
        propriedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(evento.payload().getBytes(StandardCharsets.UTF_8), propriedades);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PublicadorOutboxProperties.class)
    static class Config {

        // A devolução também chega ao CorrelationData do publicador, que decide; aqui ela só fica registrada no log.
        @Bean
        RabbitTemplateCustomizer devolucaoSemFila() {
            return template -> template.setReturnsCallback(devolvida -> log.warn(
                    "Mensagem {} devolvida pelo broker (exchange {}, routing key {}): {}",
                    devolvida.getMessage().getMessageProperties().getMessageId(), devolvida.getExchange(),
                    devolvida.getRoutingKey(), devolvida.getReplyText()));
        }
    }
}
