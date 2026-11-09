package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.BrokerTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.outbox.api.EventoSaida;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.outbox.internal.PublicadorOutboxTestHelper;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class PublicadorOutboxTest {

    private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registro) {
        registro.add("spring.rabbitmq.host", BrokerTeste.CONTAINER::getHost);
        registro.add("spring.rabbitmq.port", BrokerTeste::portaAmqp);
        registro.add("spring.rabbitmq.username", () -> BrokerTeste.USUARIO);
        registro.add("spring.rabbitmq.password", () -> BrokerTeste.USUARIO);
        // Com o broker parado, o ciclo não espera os 5 s de produção.
        registro.add("app.outbox.publicador.espera-confirmacao", () -> "2s");
    }

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private OutboxService outbox;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitAdmin admin;

    @Autowired
    private MeterRegistry metricas;

    @BeforeEach
    void limpar() {
        jdbc.update("delete from outbox_eventos");
        admin.purgeQueue("notificacoes.interna", false);
        admin.purgeQueue("notificacoes.email", false);
    }

    @Test
    void shouldPublishInCreationOrderAndMarkOnlyAfterConfirm() {
        // Gravados fora de ordem: a ordem de publicação é a de criado_em, não a de inserção.
        UUID terceiro = registrar(T0.plusSeconds(20), "interna");
        UUID primeiro = registrar(T0, "interna");
        UUID segundo = registrar(T0.plusSeconds(10), "interna");
        relogio.definir(T0.plusSeconds(60));

        PublicadorOutboxTestHelper.publicarPendentes(contexto);

        List<Message> recebidas = List.of(receber("notificacoes.interna"), receber("notificacoes.interna"),
                receber("notificacoes.interna"));
        assertThat(recebidas).extracting(m -> m.getMessageProperties().getMessageId())
                .containsExactly(primeiro.toString(), segundo.toString(), terceiro.toString());
        var propriedades = recebidas.get(0).getMessageProperties();
        assertThat(propriedades.getContentType()).isEqualTo("application/json");
        assertThat(propriedades.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(propriedades.getType()).isEqualTo("ATRIBUICAO");
        assertThat(new String(recebidas.get(0).getBody(), StandardCharsets.UTF_8))
                .contains("\"eventoId\": \"" + primeiro + "\"").contains("\"titulo\": \"Impressora parada\"");
        assertThat(jdbc.queryForList("select publicado_em from outbox_eventos", Timestamp.class))
                .hasSize(3).allSatisfy(publicado -> assertThat(publicado.toInstant()).isEqualTo(T0.plusSeconds(60)));
    }

    @Test
    void shouldKeepEventPendingWhenThereIsNoDestinationQueueAndStopTheBatch() {
        UUID semFila = registrar(T0, "inexistente");
        UUID seguinte = registrar(T0.plusSeconds(10), "interna");

        PublicadorOutboxTestHelper.publicarPendentes(contexto);

        Map<String, Object> falhou = linha(semFila);
        assertThat(falhou.get("publicado_em")).isNull();
        assertThat(falhou).containsEntry("tentativas", 1);
        assertThat((String) falhou.get("ultimo_erro")).startsWith("Sem fila de destino");
        assertThat(linha(seguinte)).containsEntry("publicado_em", null).containsEntry("tentativas", 0);
        assertThat(rabbit.receive("notificacoes.interna", 500)).isNull();
    }

    @Test
    void shouldKeepEventsPendingWhileBrokerIsDownAndPublishThemWhenItIsBack() {
        UUID evento = registrar(T0, "email");
        BrokerTeste.rabbitmqctl("stop_app");
        try {
            PublicadorOutboxTestHelper.publicarPendentes(contexto);
        } finally {
            BrokerTeste.rabbitmqctl("start_app");
            BrokerTeste.rabbitmqctl("await_startup");
        }

        Map<String, Object> pendente = linha(evento);
        assertThat(pendente.get("publicado_em")).isNull();
        assertThat(pendente).containsEntry("tentativas", 1);
        assertThat((String) pendente.get("ultimo_erro")).isNotBlank();

        PublicadorOutboxTestHelper.publicarPendentes(contexto);

        assertThat(linha(evento).get("publicado_em")).isNotNull();
        assertThat(receber("notificacoes.email").getMessageProperties().getMessageId()).isEqualTo(evento.toString());
    }

    @Test
    void shouldExposePendingCountAndOldestPendingAge() {
        registrar(T0, "interna");
        registrar(T0.plusSeconds(600), "interna");
        relogio.definir(T0.plusSeconds(3600));

        assertThat(metricas.get("outbox.eventos.pendentes").gauge().value()).isEqualTo(2);
        assertThat(metricas.get("outbox.pendente.mais.antigo.idade").gauge().value()).isEqualTo(3600);

        PublicadorOutboxTestHelper.publicarPendentes(contexto);

        assertThat(metricas.get("outbox.eventos.pendentes").gauge().value()).isZero();
        assertThat(metricas.get("outbox.pendente.mais.antigo.idade").gauge().value()).isZero();
    }

    private UUID registrar(Instant criadoEm, String routingKey) {
        relogio.definir(criadoEm);
        UUID eventoId = UUID.randomUUID();
        transacao.executeWithoutResult(t -> outbox.registrar(new EventoSaida(eventoId, "ATRIBUICAO", "notificacoes",
                List.of(routingKey), Map.of("titulo", "Impressora parada"))));
        return eventoId;
    }

    private Message receber(String fila) {
        Message mensagem = rabbit.receive(fila, 2000);
        assertThat(mensagem).as("mensagem em " + fila).isNotNull();
        return mensagem;
    }

    private Map<String, Object> linha(UUID eventoId) {
        return jdbc.queryForMap("select publicado_em, tentativas, ultimo_erro from outbox_eventos where evento_id = ?",
                eventoId);
    }
}
