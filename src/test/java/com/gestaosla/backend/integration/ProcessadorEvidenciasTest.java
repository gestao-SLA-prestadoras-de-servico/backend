package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.BrokerTeste;
import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.FotoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;
import com.gestaosla.backend.outbox.internal.PublicadorOutboxTestHelper;

import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor das evidências (evidencia-03) com RabbitMQ e MinIO reais. O listener só liga aqui (nos demais testes o
 * {@code auto-startup} é falso). A classe fecha o contexto no fim: um consumidor vivo roubaria as mensagens que o
 * TopologiaRabbitTest deixa em evidencias.processamento (o broker é um só por JVM).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProcessadorEvidenciasTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");
    private static final String FILA = "evidencias.processamento";
    private static final String DLQ = "evidencias.processamento.dlq";

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registro) {
        registro.add("spring.rabbitmq.host", BrokerTeste.CONTAINER::getHost);
        registro.add("spring.rabbitmq.port", BrokerTeste::portaAmqp);
        registro.add("spring.rabbitmq.username", () -> BrokerTeste.USUARIO);
        registro.add("spring.rabbitmq.password", () -> BrokerTeste.USUARIO);
        registro.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RelogioMutavel relogio;
    @Autowired
    private ApplicationContext contexto;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private RabbitAdmin admin;
    @Autowired
    private MeterRegistry metricas;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private S3Client s3;
    @LocalServerPort
    private int porta;

    private ArmazenamentoTestHelper storage;
    private UUID tecnico;
    private UUID chamado;
    private UUID atendimento;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        storage = new ArmazenamentoTestHelper(contexto);
        jdbc.update("delete from outbox_eventos");
        jdbc.update("delete from evidencia");
        admin.purgeQueue(FILA, false);
        admin.purgeQueue(DLQ, false);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "EM_ATENDIMENTO", tecnico);
        atendimento = UUID.randomUUID();
        jdbc.update("insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version) "
                + "values (?, now(), now(), ?, ?, 'EM_ANDAMENTO', 0)", atendimento, chamado, tecnico);
    }

    @AfterEach
    void limpar() {
        jdbc.update("delete from outbox_eventos");
    }

    // RF-61, RN-82, RN-78: RECEBIDA -> DISPONIVEL pelo caminho real (endpoint, outbox, broker, listener), com os dois derivados.
    @Test
    void shouldProcessUploadedEvidenceToAvailableWithThumbnailAndPreview() throws Exception {
        UUID id = enviar(foto(2000, 1500));
        String chaveOriginal = chaveOriginal(id);
        String hashAntes = sha256(storage.ler(chaveOriginal));
        assertThat(estado(id)).isEqualTo("RECEBIDA");

        PublicadorOutboxTestHelper.publicarPendentes(contexto);
        aguardarEstado(id, "DISPONIVEL");

        Map<String, Object> linha = jdbc.queryForMap("select * from evidencia where id = ?", id);
        String prefixo = "evidencias/" + chamado + "/" + id + "/";
        assertThat(linha).containsEntry("chave_miniatura", prefixo + "miniatura.jpg")
                .containsEntry("chave_visualizacao", prefixo + "visualizacao.jpg");
        assertThat(((Number) linha.get("version")).longValue()).isGreaterThan(0);
        BufferedImage miniatura = ImageIO.read(new ByteArrayInputStream(storage.ler(prefixo + "miniatura.jpg")));
        BufferedImage visualizacao = ImageIO.read(new ByteArrayInputStream(storage.ler(prefixo + "visualizacao.jpg")));
        assertThat(new int[] {miniatura.getWidth(), miniatura.getHeight()}).containsExactly(320, 240);
        assertThat(new int[] {visualizacao.getWidth(), visualizacao.getHeight()}).containsExactly(1280, 960);
        assertThat(sha256(storage.ler(chaveOriginal))).as("original intacto (RN-78)").isEqualTo(hashAntes)
                .isEqualTo(linha.get("hash_sha256"));
        assertThat(storage.listar(prefixo)).hasSize(3);
        aguardarFilaVazia();
        assertThat(mensagensNaFila(DLQ)).isZero();
    }

    // RN-86, RN-115: a segunda entrega da mesma mensagem não gera nada (2 gravações no total: miniatura e visualização).
    @Test
    void shouldGenerateDerivativesOnlyOnceWhenTheSameMessageIsDeliveredTwice() throws Exception {
        UUID id = enviar(foto(1500, 1000));
        Mockito.clearInvocations(s3);
        Message mensagem = mensagem(id);

        publicar(mensagem);
        aguardarEstado(id, "DISPONIVEL");
        aguardarFilaVazia();
        Map<String, Instant> antes = storage.listar("evidencias/" + chamado + "/" + id + "/");
        assertThat(invocacoes("putObject")).isEqualTo(2);

        publicar(mensagem);
        aguardarFilaVazia();

        assertThat(invocacoes("putObject")).as("nenhuma gravação na segunda entrega").isEqualTo(2);
        assertThat(storage.listar("evidencias/" + chamado + "/" + id + "/")).isEqualTo(antes);
        assertThat(estado(id)).isEqualTo("DISPONIVEL");
        assertThat(mensagensNaFila(DLQ)).isZero();
    }

    // RN-86: DISPONIVEL e EXPURGADA (e também FALHA ou id desconhecido) são descartadas, com ack e sem tocar em nada.
    @Test
    void shouldDiscardAvailableAndExpungedEvidenceWithoutProcessing() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (String estado : List.of("DISPONIVEL", "EXPURGADA", "FALHA")) {
            ids.add(inserir(estado, foto(800, 600)));
        }
        Mockito.clearInvocations(s3);

        for (UUID id : ids) {
            publicar(mensagem(id));
        }
        publicar(mensagem(UUID.randomUUID()));
        aguardarFilaVazia();

        assertThat(estado(ids.get(0))).isEqualTo("DISPONIVEL");
        assertThat(estado(ids.get(1))).isEqualTo("EXPURGADA");
        assertThat(estado(ids.get(2))).isEqualTo("FALHA");
        assertThat(invocacoes("putObject")).isZero();
        assertThat(invocacoes("getObject")).isZero();
        for (UUID id : ids) {
            assertThat(storage.listar("evidencias/" + chamado + "/" + id + "/")).hasSize(1); // só o original
        }
        assertThat(mensagensNaFila(DLQ)).isZero();
    }

    // RN-87, RN-88, RF-61: storage fora do ar -> 3 tentativas, FALHA no banco, mensagem na DLQ, fila principal vazia.
    @Test
    void shouldTryThreeTimesThenMarkFailureAndSendTheMessageToTheDlq() throws Exception {
        UUID id = inserir("RECEBIDA", foto(800, 600));
        Mockito.clearInvocations(s3);
        doThrow(SdkClientException.create("storage fora do ar")).when(s3).getObject(anyGetObject());

        publicar(mensagem(id));
        aguardarEstado(id, "FALHA");

        assertThat(invocacoes("getObject")).isEqualTo(3);
        Message naDlq = aguardarNaDlq(id);
        assertThat(naDlq).isNotNull();
        aguardarFilaVazia();
        assertThat(mensagensNaFila(FILA)).isZero();
        assertThat(storage.listar("evidencias/" + chamado + "/" + id + "/")).hasSize(1);
    }

    @Test
    void shouldBecomeAvailableWhenTheSecondAttemptSucceeds() throws Exception {
        UUID id = inserir("RECEBIDA", foto(800, 600));
        Mockito.clearInvocations(s3);
        doThrow(SdkClientException.create("falha momentânea")).doCallRealMethod().when(s3).getObject(anyGetObject());

        publicar(mensagem(id));
        aguardarEstado(id, "DISPONIVEL");

        assertThat(invocacoes("getObject")).isEqualTo(2);
        aguardarFilaVazia();
        assertThat(mensagensNaFila(DLQ)).isZero();
    }

    // RN-87: imagem ilegível não é transitória; vai direto para FALHA na primeira tentativa.
    @Test
    void shouldFailImmediatelyWhenTheImageDoesNotDecode() throws Exception {
        UUID id = inserirComOriginal("RECEBIDA", "isto não é uma imagem".getBytes(StandardCharsets.UTF_8));
        Mockito.clearInvocations(s3);

        publicar(mensagem(id));
        aguardarEstado(id, "FALHA");

        assertThat(invocacoes("getObject")).isEqualTo(1);
        assertThat(aguardarNaDlq(id)).isNotNull();
        aguardarFilaVazia();
        assertThat(mensagensNaFila(FILA)).isZero();
        assertThat(storage.listar("evidencias/" + chamado + "/" + id + "/")).hasSize(1);
    }

    // RN-89: FALHA da evidência não mexe no atendimento nem no chamado (linhas inteiras, inclusive version e updated_at).
    @Test
    void shouldNotChangeTheChamadoNorTheAttendanceWhenTheEvidenceFails() throws Exception {
        UUID id = inserirComOriginal("RECEBIDA", new byte[] {1, 2, 3});
        Map<String, Object> chamadoAntes = jdbc.queryForMap("select * from chamado where id = ?", chamado);
        Map<String, Object> atendimentoAntes = jdbc.queryForMap("select * from atendimento where id = ?", atendimento);
        long historicoAntes = jdbc.queryForObject("select count(*) from chamado_historico where chamado_id = ?", Long.class, chamado);

        publicar(mensagem(id));
        aguardarEstado(id, "FALHA");
        aguardarFilaVazia();

        assertThat(jdbc.queryForMap("select * from chamado where id = ?", chamado)).isEqualTo(chamadoAntes);
        assertThat(jdbc.queryForMap("select * from atendimento where id = ?", atendimento)).isEqualTo(atendimentoAntes);
        assertThat(jdbc.queryForObject("select count(*) from chamado_historico where chamado_id = ?", Long.class, chamado))
                .isEqualTo(historicoAntes);
    }

    // RN-90: durante o commit do resultado (UPDATE lento por uma trigger de teste) a mensagem segue sem ack; só depois dele sai.
    @Test
    void shouldAckOnlyAfterTheResultIsPersisted() throws Exception {
        UUID id = enviar(foto(1200, 800));
        jdbc.execute("create function lento_teste_evidencia() returns trigger language plpgsql as $$ begin "
                + "perform pg_sleep(3); return new; end; $$");
        jdbc.execute("create trigger lento_teste_evidencia before update on evidencia for each row "
                + "when (new.estado = 'DISPONIVEL') execute function lento_teste_evidencia()");
        try {
            PublicadorOutboxTestHelper.publicarPendentes(contexto);
            aguardarEstado(id, "PROCESSANDO");

            assertThat(estado(id)).isEqualTo("PROCESSANDO");
            assertThat(mensagensNaoConfirmadas(FILA)).as("sem ack enquanto o resultado não foi gravado").isEqualTo(1);

            aguardarEstado(id, "DISPONIVEL");
            aguardarFilaVazia();
        } finally {
            jdbc.execute("drop trigger lento_teste_evidencia on evidencia");
            jdbc.execute("drop function lento_teste_evidencia()");
        }
    }

    // RF-29 (evidencia-05): FALHA -> reprocessamento pelo endpoint -> evento novo publicado -> DISPONIVEL. A mensagem antiga,
    // reinjetada da DLQ à mão, acha a evidência já DISPONIVEL e é descartada sem gravar nada.
    @Test
    void shouldReprocessFailedEvidenceToAvailableAndDiscardTheOldDlqMessage() throws Exception {
        UUID id = enviar(foto(1500, 1000));
        doThrow(SdkClientException.create("storage fora do ar")).when(s3).getObject(anyGetObject());
        PublicadorOutboxTestHelper.publicarPendentes(contexto);
        aguardarEstado(id, "FALHA");
        Message antiga = aguardarNaDlq(id);
        assertThat(antiga).isNotNull();
        aguardarFilaVazia();
        Mockito.reset(s3);

        mvc.perform(post("/api/evidencias/{id}/reprocessamento", id).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isAccepted());
        PublicadorOutboxTestHelper.publicarPendentes(contexto);
        aguardarEstado(id, "DISPONIVEL");
        aguardarFilaVazia();

        assertThat(storage.listar("evidencias/" + chamado + "/" + id + "/")).hasSize(3);
        assertThat(mensagensNaFila(DLQ)).isZero();
        long gravacoes = invocacoes("putObject");

        publicar(antiga);
        aguardarFilaVazia();

        assertThat(estado(id)).isEqualTo("DISPONIVEL");
        assertThat(invocacoes("putObject")).as("a mensagem antiga não gera nada").isEqualTo(gravacoes);
    }

    // RF-64: um gauge por estado, lido do banco.
    @Test
    void shouldExposeEvidenceCountPerState() throws Exception {
        for (var contagem : Map.of("RECEBIDA", 1, "PROCESSANDO", 2, "DISPONIVEL", 3, "FALHA", 4, "EXPURGADA", 5).entrySet()) {
            for (int i = 0; i < contagem.getValue(); i++) {
                inserirComOriginal(contagem.getKey(), new byte[] {1});
            }
        }

        assertThat(gauge("RECEBIDA")).isEqualTo(1);
        assertThat(gauge("PROCESSANDO")).isEqualTo(2);
        assertThat(gauge("DISPONIVEL")).isEqualTo(3);
        assertThat(gauge("FALHA")).isEqualTo(4);
        assertThat(gauge("EXPURGADA")).isEqualTo(5);
    }

    // Critério do pico: o limite de latência NÃO foi combinado. A asserção abaixo é um valor PROVISÓRIO e frouxo
    // (p95 < 5 x p95 da base + 200 ms no processamento e + 500 ms nos envios); os números medidos vão para docs/testes.
    @Test
    void shouldKeepApiLatencyUnderProvisionalLimitDuringA50UploadBurst() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        byte[] foto = foto(3000, 2000);
        for (int i = 0; i < 30; i++) {
            consultar(http); // aquecimento
        }
        List<Long> base = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            base.add(consultar(http));
        }

        // O publicador roda a cada 500 ms (em produção, a cada 5 s) enquanto os envios chegam.
        ScheduledExecutorService publicador = Executors.newSingleThreadScheduledExecutor();
        publicador.scheduleWithFixedDelay(() -> PublicadorOutboxTestHelper.publicarPendentes(contexto), 0, 500, TimeUnit.MILLISECONDS);
        ExecutorService envios = Executors.newFixedThreadPool(50);
        List<Long> duranteEnvios = new ArrayList<>();
        List<Long> duranteProcessamento = new ArrayList<>();
        try {
            List<Future<Integer>> respostas = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                respostas.add(envios.submit((Callable<Integer>) () -> enviarPorHttp(http, foto)));
            }
            while (!respostas.stream().allMatch(Future::isDone)) {
                duranteEnvios.add(consultar(http));
            }
            for (Future<Integer> resposta : respostas) {
                assertThat(resposta.get()).isEqualTo(202);
            }
            long fim = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (disponiveis() < 50 && System.nanoTime() < fim) {
                duranteProcessamento.add(consultar(http));
            }
        } finally {
            envios.shutdownNow();
            publicador.shutdownNow();
        }

        assertThat(disponiveis()).as("as 50 evidências chegam a DISPONIVEL").isEqualTo(50);
        List<Long> pico = new ArrayList<>(duranteEnvios);
        pico.addAll(duranteProcessamento);
        long p95Base = p95(base);
        long p95Pico = p95(pico);
        System.out.printf("MEDIDA-PICO base: n=%d p50=%d ms p95=%d ms | envios: n=%d p95=%d ms | processamento: n=%d p95=%d ms | pico: n=%d p95=%d ms max=%d ms%n",
                base.size(), p50(base), p95Base, duranteEnvios.size(), p95(duranteEnvios), duranteProcessamento.size(),
                p95(duranteProcessamento), pico.size(), p95Pico, Collections.max(pico));
        // As duas fases são medidas à parte: misturadas, a fase de processamento (bem mais longa) diluiria a dos envios.
        assertThat(p95(duranteProcessamento)).as("processamento em segundo plano; limite não combinado, valor provisório: 5 x base + 200 ms")
                .isLessThan(5 * p95Base + 200);
        assertThat(p95(duranteEnvios)).as("durante os envios (a sanitização dos 50 uploads também consome CPU); limite não combinado, valor provisório: 5 x base + 500 ms")
                .isLessThan(5 * p95Base + 500);
    }

    // ---- apoio ----

    private static Consumer<GetObjectRequest.Builder> anyGetObject() {
        return any();
    }

    // Só a sobrecarga com Consumer, a que o ArmazenamentoEvidencias chama: ela delega à de GetObjectRequest/PutObjectRequest
    // por dentro do spy, e contar as duas dobraria o resultado.
    private long invocacoes(String metodo) {
        return Mockito.mockingDetails(s3).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(metodo) && i.getMethod().getParameterTypes()[0] == Consumer.class).count();
    }

    private double gauge(String estado) {
        return metricas.get("evidencias").tag("estado", estado).gauge().value();
    }

    private long disponiveis() {
        return jdbc.queryForObject("select count(*) from evidencia where estado = 'DISPONIVEL'", Long.class);
    }

    private String estado(UUID id) {
        return jdbc.queryForObject("select estado from evidencia where id = ?", String.class, id);
    }

    private String chaveOriginal(UUID id) {
        return jdbc.queryForObject("select chave_original from evidencia where id = ?", String.class, id);
    }

    private void aguardarEstado(UUID id, String esperado) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).untilAsserted(
                () -> assertThat(estado(id)).isEqualTo(esperado));
    }

    private Message aguardarNaDlq(UUID id) {
        long fim = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < fim) {
            Message m = rabbit.receive(DLQ, 500);
            if (m != null && new String(m.getBody(), StandardCharsets.UTF_8).contains(id.toString())) {
                return m;
            }
        }
        return null;
    }

    /** Nada pronto nem sem ack na fila principal: o listener terminou (e confirmou) tudo o que recebeu. */
    private void aguardarFilaVazia() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(
                () -> assertThat(mensagensNaFila(FILA) + mensagensNaoConfirmadas(FILA)).isZero());
    }

    private long mensagensNaFila(String fila) {
        return contagemRabbit(fila, 1);
    }

    private long mensagensNaoConfirmadas(String fila) {
        return contagemRabbit(fila, 2);
    }

    // rabbitmqctl consulta o processo da fila na hora; a API de gerenciamento só atualiza as estatísticas a cada 5 s.
    private long contagemRabbit(String fila, int coluna) {
        try {
            String saida = BrokerTeste.CONTAINER.execInContainer("rabbitmqctl", "list_queues", "--quiet", "name",
                    "messages_ready", "messages_unacknowledged").getStdout();
            for (String linha : saida.split("\n")) {
                String[] partes = linha.trim().split("\\s+");
                if (partes[0].equals(fila)) {
                    return Long.parseLong(partes[coluna]);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("fila " + fila + " não listada");
    }

    private Message mensagem(UUID id) {
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        p.setMessageId(UUID.randomUUID().toString());
        String corpo = "{\"evidenciaId\":\"" + id + "\",\"chamadoId\":\"" + chamado + "\",\"chaveOriginal\":\"x\"}";
        return new Message(corpo.getBytes(StandardCharsets.UTF_8), p);
    }

    /** Publica e espera o confirm do broker, para a mensagem já estar na fila quando o teste olhar. */
    private void publicar(Message mensagem) {
        rabbit.invoke(t -> {
            t.send("evidencias", "processamento", mensagem);
            t.waitForConfirmsOrDie(5000);
            return null;
        });
    }

    private UUID enviar(byte[] foto) throws Exception {
        String corpo = mvc.perform(multipart("/api/chamados/{id}/evidencias", chamado).file(new MockMultipartFile("arquivo", "f.jpg", "image/jpeg", foto))
                .header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO"))).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(corpo).get("id").asString());
    }

    private UUID inserir(String estado, byte[] foto) {
        return inserirComOriginal(estado, foto);
    }

    private UUID inserirComOriginal(String estado, byte[] original) {
        UUID id = UUID.randomUUID();
        String chave = "evidencias/" + chamado + "/" + id + "/original.jpg";
        storage.gravar(chave, original, "image/jpeg");
        jdbc.update("""
                insert into evidencia (id, created_at, updated_at, atendimento_id, chamado_id, nome, tipo_conteudo, tamanho,
                                       hash_sha256, chave_original, estado, enviada_em, version)
                values (?, now(), now(), ?, ?, 'f.jpg', 'image/jpeg', ?, ?, ?, ?, now(), 0)""",
                id, atendimento, chamado, original.length, "0".repeat(64), chave, estado);
        return id;
    }

    /** JPEG de fotografia sintética (gradiente e elipses), com o tamanho pedido. */
    private static byte[] foto(int largura, int altura) {
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagem.createGraphics();
        Random sorteio = new Random(42);
        g.setPaint(new java.awt.GradientPaint(0, 0, Color.DARK_GRAY, largura, altura, Color.ORANGE));
        g.fillRect(0, 0, largura, altura);
        for (int i = 0; i < 150; i++) {
            g.setColor(new Color(sorteio.nextInt(0xFFFFFF)));
            g.fillOval(sorteio.nextInt(largura), sorteio.nextInt(altura), 20 + sorteio.nextInt(300), 20 + sorteio.nextInt(300));
        }
        g.dispose();
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            ImageIO.write(imagem, "jpeg", saida);
            return saida.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private long consultar(HttpClient http) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/chamados/" + chamado))
                .header("Authorization", token(SUB_GESTOR, "GESTOR")).GET().build();
        long inicio = System.nanoTime();
        HttpResponse<Void> resposta = http.send(pedido, HttpResponse.BodyHandlers.discarding());
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
        assertThat(resposta.statusCode()).isEqualTo(200);
        return ms;
    }

    private int enviarPorHttp(HttpClient http, byte[] foto) throws Exception {
        String limite = "----teste" + UUID.randomUUID();
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        corpo.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"f.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        corpo.write(foto);
        corpo.write(("\r\n--" + limite + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/chamados/" + chamado + "/evidencias"))
                .header("Authorization", token(tecnico.toString(), "TECNICO")).header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray())).build();
        return http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static long p95(List<Long> amostras) {
        return percentil(amostras, 0.95);
    }

    private static long p50(List<Long> amostras) {
        return percentil(amostras, 0.50);
    }

    private static long percentil(List<Long> amostras, double p) {
        List<Long> ordenadas = new ArrayList<>(amostras);
        Collections.sort(ordenadas);
        return ordenadas.isEmpty() ? 0 : ordenadas.get((int) Math.min(ordenadas.size() - 1, Math.ceil(ordenadas.size() * p) - 1));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
