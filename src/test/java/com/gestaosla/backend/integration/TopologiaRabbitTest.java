package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Topologia de infra/rabbitmq/topologia.json num broker real, sem nenhum consumidor nem declaração da aplicação
 * (outbox-02). Mesma imagem do infra/docker-compose.yml.
 */
class TopologiaRabbitTest {

    private static final String USUARIO = "teste";
    private static final long SETE_DIAS_MS = 7L * 24 * 60 * 60 * 1000;
    private static final List<String> FILAS = List.of("notificacoes.email", "notificacoes.interna",
            "notificacoes.email.dlq", "notificacoes.interna.dlq", "evidencias.processamento",
            "evidencias.processamento.dlq");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> broker = new GenericContainer<>("rabbitmq:4.1-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", USUARIO)
            .withEnv("RABBITMQ_DEFAULT_PASS", USUARIO)
            .withCopyFileToContainer(MountableFile.forHostPath("infra/rabbitmq/topologia.json"), "/tmp/topologia.json")
            .withExposedPorts(5672, 15672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private static CachingConnectionFactory conexao;
    private static RabbitTemplate rabbit;
    private static RabbitAdmin admin;
    private static RestClient gerenciamento;
    private static String baseGerenciamento;

    @BeforeAll
    static void subirBroker() throws Exception {
        broker.start();
        var importacao = broker.execInContainer("rabbitmqctl", "import_definitions", "/tmp/topologia.json");
        assertThat(importacao.getExitCode()).as(importacao.getStderr()).isZero();
        conexao = new CachingConnectionFactory(broker.getHost(), broker.getMappedPort(5672));
        conexao.setUsername(USUARIO);
        conexao.setPassword(USUARIO);
        rabbit = new RabbitTemplate(conexao);
        admin = new RabbitAdmin(conexao);
        baseGerenciamento = "http://" + broker.getHost() + ":" + broker.getMappedPort(15672);
        gerenciamento = RestClient.builder().defaultHeaders(h -> h.setBasicAuth(USUARIO, USUARIO)).build();
    }

    @AfterAll
    static void derrubarBroker() {
        conexao.destroy();
        broker.stop();
    }

    @BeforeEach
    void esvaziarFilas() {
        FILAS.forEach(fila -> admin.purgeQueue(fila, false));
    }

    @ParameterizedTest
    @CsvSource({"notificacoes, email, notificacoes.email", "notificacoes, interna, notificacoes.interna",
            "evidencias, processamento, evidencias.processamento"})
    void shouldKeepPublishedMessageQueuedWithoutAnyConsumer(String exchange, String routingKey, String fila) {
        publicar(rabbit, exchange, routingKey);

        // A publicação é assíncrona; a contagem só é confiável depois que o broker enfileira.
        await().atMost(Duration.ofSeconds(5)).until(() -> mensagens(fila) == 1);
    }

    @ParameterizedTest
    @CsvSource({"notificacoes, email, notificacoes.email, notificacoes.email.dlq",
            "notificacoes, interna, notificacoes.interna, notificacoes.interna.dlq",
            "evidencias, processamento, evidencias.processamento, evidencias.processamento.dlq"})
    void shouldDeadLetterRejectedMessageToChannelDlq(String exchange, String routingKey, String fila, String dlq) {
        publicar(rabbit, exchange, routingKey);
        await().atMost(Duration.ofSeconds(5)).until(() -> mensagens(fila) == 1);

        rabbit.execute(canal -> {
            var entrega = canal.basicGet(fila, false);
            canal.basicReject(entrega.getEnvelope().getDeliveryTag(), false);
            return null;
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> mensagens(dlq) == 1);
        assertThat(mensagens(fila)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"notificacoes.email.dlq", "notificacoes.interna.dlq"})
    void shouldExpireNotificationDlqMessagesAfterSevenDays(String dlq) {
        assertThat(((Number) argumentos(dlq).get("x-message-ttl")).longValue()).isEqualTo(SETE_DIAS_MS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidencias.processamento.dlq"})
    void shouldKeepEvidenceDlqMessagesWithoutExpiration(String dlq) {
        // A evidência fica em FALHA (RN-88); a mensagem não expira sozinha.
        assertThat(argumentos(dlq)).doesNotContainKey("x-message-ttl");
    }

    private static void publicar(AmqpTemplate template, String exchange, String routingKey) {
        template.convertAndSend(exchange, routingKey, "{\"eventoId\":\"teste\"}");
    }

    private static long mensagens(String fila) {
        return ((Number) admin.getQueueProperties(fila).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argumentos(String fila) {
        // URI pronta: o template codificaria o %2F do vhost "/".
        Map<String, Object> info = gerenciamento.get().uri(URI.create(baseGerenciamento + "/api/queues/%2F/" + fila))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return (Map<String, Object>) info.get("arguments");
    }
}
