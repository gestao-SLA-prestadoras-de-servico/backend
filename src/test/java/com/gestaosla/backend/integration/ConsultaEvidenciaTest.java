package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.HexFormat;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.FotoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.json.JsonMapper;

/** Listagem e download das evidências (evidencia-04) com MinIO real; as linhas são inseridas por SQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ConsultaEvidenciaTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationContext contexto;
    @Autowired
    private JsonMapper json;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapeamentos;
    @MockitoSpyBean
    private S3Client s3;
    @LocalServerPort
    private int porta;

    private ArmazenamentoTestHelper storage;
    private UUID empresa;
    private UUID tecnico;
    private UUID cliente;
    private UUID chamado;
    private UUID atendimento;

    @BeforeEach
    void preparar() {
        storage = new ArmazenamentoTestHelper(contexto);
        empresa = ChamadoTeste.cliente(jdbc);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        cliente = UUID.fromString(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa));
        chamado = ChamadoTeste.chamado(jdbc, empresa, "EM_ATENDIMENTO", tecnico);
        atendimento = atendimento(chamado, "EM_ANDAMENTO");
    }

    @AfterEach
    void limpar() {
        Mockito.reset(s3);
    }

    // RF-26 e RF-46: todos veem evidências ativas no seu escopo; só o gestor vê as expurgadas.
    @Test
    void shouldListEvidenceGroupedByAttendanceOrderedBySentDateForAllRoles() throws Exception {
        UUID antigo = atendimento(chamado, "CONCLUIDO");
        UUID e1 = inserir(chamado, atendimento, "DISPONIVEL", true, T0.plusSeconds(20), png());
        UUID e2 = inserir(chamado, antigo, "RECEBIDA", false, T0.plusSeconds(10), png());
        UUID e3 = inserir(chamado, atendimento, "EXPURGADA", false, T0.plusSeconds(30), png());
        String hash = sha256(png());

        for (String[] quem : new String[][] {{SUB_GESTOR, "GESTOR"}, {tecnico.toString(), "TECNICO"}, {cliente.toString(), "CLIENTE"}}) {
            var lista = mvc.perform(get("/api/chamados/{id}/evidencias", chamado).header(HttpHeaders.AUTHORIZATION, token(quem[0], quem[1])))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].atendimentoId").value(antigo.toString()))
                    .andExpect(jsonPath("$[0].evidencias.length()").value(1))
                    .andExpect(jsonPath("$[0].evidencias[0].id").value(e2.toString()))
                    .andExpect(jsonPath("$[0].evidencias[0].estado").value("RECEBIDA"))
                    .andExpect(jsonPath("$[1].atendimentoId").value(atendimento.toString()))
                    .andExpect(jsonPath("$[1].evidencias[0].id").value(e1.toString()))
                    .andExpect(jsonPath("$[1].evidencias[0].nome").value("foto.png"))
                    .andExpect(jsonPath("$[1].evidencias[0].tipoConteudo").value("image/png"))
                    .andExpect(jsonPath("$[1].evidencias[0].tamanho").value(png().length))
                    .andExpect(jsonPath("$[1].evidencias[0].hashSha256").value(hash))
                    .andExpect(jsonPath("$[1].evidencias[0].estado").value("DISPONIVEL"))
                    .andExpect(jsonPath("$[1].evidencias[0].enviadaEm").value("2026-11-16T10:00:20Z"))
                    .andExpect(jsonPath("$[1].evidencias[0].expurgadaEm").doesNotExist());
            if ("GESTOR".equals(quem[1])) {
                lista.andExpect(jsonPath("$[1].evidencias.length()").value(2))
                        .andExpect(jsonPath("$[1].evidencias[1].id").value(e3.toString()))
                        .andExpect(jsonPath("$[1].evidencias[1].estado").value("EXPURGADA"))
                        .andExpect(jsonPath("$[1].evidencias[1].expurgadaEm").value("2026-11-16T11:00:00Z"))
                        .andExpect(jsonPath("$[1].evidencias[1].miniatura").doesNotExist());
            } else {
                lista.andExpect(jsonPath("$[1].evidencias.length()").value(1));
            }
        }
    }

    @Test
    void shouldReturnPurgedEvidenceMetadataOnlyForManager() throws Exception {
        byte[] original = png();
        UUID id = inserir(chamado, atendimento, "EXPURGADA", false, T0, original);

        mvc.perform(get("/api/evidencias/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.atendimentoId").value(atendimento.toString()))
                .andExpect(jsonPath("$.chamadoId").value(chamado.toString()))
                .andExpect(jsonPath("$.enviadaEm").value(T0.toString()))
                .andExpect(jsonPath("$.expurgadaEm").value("2026-11-16T11:00:00Z"))
                .andExpect(jsonPath("$.hashSha256").value(sha256(original)))
                .andExpect(jsonPath("$.estado").value("EXPURGADA"))
                .andExpect(jsonPath("$.chaveOriginal").doesNotExist());

        for (String[] quem : new String[][] {{tecnico.toString(), "TECNICO"}, {cliente.toString(), "CLIENTE"}}) {
            mvc.perform(get("/api/evidencias/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, token(quem[0], quem[1])))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.hashSha256").doesNotExist());
        }
        mvc.perform(get("/api/evidencias/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/evidencias/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListEmptyWhenTheChamadoHasNoEvidence() throws Exception {
        mvc.perform(get("/api/chamados/{id}/evidencias", chamado).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // RN-126: outro cliente, técnico não atribuído e id inexistente respondem 404, na listagem e no conteúdo.
    @Test
    void shouldAnswer404ForOtherCompanyUnassignedTechnicianAndUnknownIds() throws Exception {
        UUID evidencia = inserir(chamado, atendimento, "DISPONIVEL", true, T0, png());
        UUID outraEmpresa = ChamadoTeste.cliente(jdbc);
        String outroCliente = ChamadoTeste.usuario(jdbc, "CLIENTE", outraEmpresa);
        String outroTecnico = ChamadoTeste.usuario(jdbc, "TECNICO", null);

        for (String[] quem : new String[][] {{outroCliente, "CLIENTE"}, {outroTecnico, "TECNICO"}}) {
            mvc.perform(get("/api/chamados/{id}/evidencias", chamado).header(HttpHeaders.AUTHORIZATION, token(quem[0], quem[1])))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("RECURSO_NAO_ENCONTRADO"));
            assertThat(baixar(quem[0], quem[1], "/api/evidencias/" + evidencia + "/conteudo").getStatus()).isEqualTo(404);
            assertThat(baixar(quem[0], quem[1], "/api/evidencias/" + evidencia + "/conteudo?variante=original").getStatus()).isEqualTo(404);
        }
        mvc.perform(get("/api/chamados/{id}/evidencias", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isNotFound());
        assertThat(baixar(SUB_GESTOR, "GESTOR", "/api/evidencias/" + UUID.randomUUID() + "/conteudo").getStatus()).isEqualTo(404);
    }

    @Test
    void shouldAnswer401WithoutToken() throws Exception {
        mvc.perform(get("/api/chamados/{id}/evidencias", chamado)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/evidencias/{id}/conteudo", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    // RF-28: visualização por padrão (1280), miniatura (320) ou original (bytes e hash iguais ao gravado).
    @Test
    void shouldServeVisualizationByDefaultThumbnailAndOriginalOnRequest() throws Exception {
        byte[] original = png();
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", true, T0, original);
        String base = "/api/evidencias/" + id + "/conteudo";

        for (String[] quem : new String[][] {{SUB_GESTOR, "GESTOR"}, {tecnico.toString(), "TECNICO"}, {cliente.toString(), "CLIENTE"}}) {
            byte[] padrao = baixar(quem[0], quem[1], base).getContentAsByteArray();
            assertThat(largura(padrao)).isEqualTo(1280);
            assertThat(baixar(quem[0], quem[1], base + "?variante=visualizacao").getContentAsByteArray()).isEqualTo(padrao);
            assertThat(largura(baixar(quem[0], quem[1], base + "?variante=miniatura").getContentAsByteArray())).isEqualTo(320);
            byte[] enviado = baixar(quem[0], quem[1], base + "?variante=original").getContentAsByteArray();
            assertThat(enviado).isEqualTo(original);
            assertThat(sha256(enviado)).isEqualTo(sha256(original));
        }
    }

    // RN-81: derivado ainda nulo (RECEBIDA, PROCESSANDO, FALHA) -> serve o original, e o ETag diz que são os bytes do original.
    @Test
    void shouldFallBackToOriginalWhenTheDerivativeDoesNotExistYet() throws Exception {
        for (String estado : List.of("RECEBIDA", "PROCESSANDO", "FALHA")) {
            UUID id = inserir(chamado, atendimento, estado, false, T0, png());
            for (String variante : List.of("", "?variante=visualizacao", "?variante=miniatura")) {
                MockHttpServletResponse r = baixar(SUB_GESTOR, "GESTOR", "/api/evidencias/" + id + "/conteudo" + variante);
                assertThat(r.getStatus()).as(estado + variante).isEqualTo(200);
                assertThat(r.getContentAsByteArray()).as(estado + variante).isEqualTo(png());
                assertThat(r.getContentType()).isEqualTo("image/png");
                assertThat(r.getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + sha256(png()) + "-original\"");
            }
        }
    }

    // Passo técnico: Content-Type, Content-Length, ETag (hash), Cache-Control private; nosniff do Spring Security.
    @Test
    void shouldSendContentTypeLengthEtagAndPrivateCacheControl() throws Exception {
        byte[] original = png();
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", true, T0, original);
        String hash = sha256(original);
        String base = "/api/evidencias/" + id + "/conteudo";

        MockHttpServletResponse visualizacao = baixar(SUB_GESTOR, "GESTOR", base);
        assertThat(visualizacao.getContentType()).isEqualTo("image/jpeg");
        assertThat(visualizacao.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(visualizacao.getContentAsByteArray().length));
        assertThat(visualizacao.getHeaders(HttpHeaders.ETAG)).containsExactly("\"" + hash + "-visualizacao\"");
        assertThat(visualizacao.getHeader(HttpHeaders.CACHE_CONTROL)).contains("private", "no-cache").doesNotContain("public", "max-age");
        assertThat(visualizacao.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline");
        assertThat(visualizacao.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        MockHttpServletResponse miniatura = baixar(SUB_GESTOR, "GESTOR", base + "?variante=miniatura");
        assertThat(miniatura.getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + hash + "-miniatura\"");

        MockHttpServletResponse bruto = baixar(SUB_GESTOR, "GESTOR", base + "?variante=original");
        assertThat(bruto.getContentType()).isEqualTo("image/png");
        assertThat(bruto.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(original.length));
        assertThat(bruto.getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + hash + "-original\"");
    }

    // If-None-Match igual: 304 sem abrir o objeto no storage. ETag de outra variante não vale.
    @Test
    void shouldAnswer304WithoutReadingTheStorageWhenIfNoneMatchMatches() throws Exception {
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", true, T0, png());
        String base = "/api/evidencias/" + id + "/conteudo";
        String etag = baixar(SUB_GESTOR, "GESTOR", base).getHeader(HttpHeaders.ETAG);
        Mockito.clearInvocations(s3);

        MockHttpServletResponse r = baixar(SUB_GESTOR, "GESTOR", base, HttpHeaders.IF_NONE_MATCH, etag);

        assertThat(r.getStatus()).isEqualTo(304);
        assertThat(r.getContentAsByteArray()).isEmpty();
        assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).contains("private", "no-cache").doesNotContain("max-age");
        assertThat(r.getHeaders(HttpHeaders.ETAG)).containsExactly(etag);
        Mockito.verify(s3, Mockito.never()).getObject(any(Consumer.class));

        MockHttpServletResponse outraVariante = baixar(SUB_GESTOR, "GESTOR", base + "?variante=miniatura", HttpHeaders.IF_NONE_MATCH, etag);
        assertThat(outraVariante.getStatus()).isEqualTo(200);
        assertThat(largura(outraVariante.getContentAsByteArray())).isEqualTo(320);
        // Quem não vê o chamado não descobre nada pelo ETag: 404 antes de comparar.
        String outro = ChamadoTeste.usuario(jdbc, "CLIENTE", ChamadoTeste.cliente(jdbc));
        assertThat(baixar(outro, "CLIENTE", base, HttpHeaders.IF_NONE_MATCH, etag).getStatus()).isEqualTo(404);
    }

    // Revogar o acesso vale na hora: o navegador revalida sempre, e a revalidação do técnico antigo dá 404, nunca 304.
    @Test
    void shouldAnswer404NotModifiedToATechnicianWhoLostTheChamadoOnRevalidation() throws Exception {
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", true, T0, png());
        String base = "/api/evidencias/" + id + "/conteudo";
        String antigo = tecnico.toString();
        MockHttpServletResponse primeira = baixar(antigo, "TECNICO", base);
        assertThat(primeira.getStatus()).isEqualTo(200);
        String etag = primeira.getHeader(HttpHeaders.ETAG);
        assertThat(baixar(antigo, "TECNICO", base, HttpHeaders.IF_NONE_MATCH, etag).getStatus()).isEqualTo(304);

        UUID novo = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        jdbc.update("update chamado set tecnico_sub = ? where id = ?", novo, chamado);

        assertThat(baixar(antigo, "TECNICO", base, HttpHeaders.IF_NONE_MATCH, etag).getStatus()).isEqualTo(404);
        assertThat(baixar(novo.toString(), "TECNICO", base, HttpHeaders.IF_NONE_MATCH, etag).getStatus()).isEqualTo(304);
    }

    // HEAD (atendido pelo @GetMapping) responde os mesmos cabeçalhos do GET, sem corpo, e não deixa stream do storage aberto.
    @Test
    void shouldAnswerHeadWithTheSameHeadersAsGetAndLeaveNoStorageStreamOpen() throws Exception {
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", true, T0, png());
        AtomicLong abertos = new AtomicLong();
        AtomicLong fechados = new AtomicLong();
        Mockito.doAnswer(chamada -> {
            @SuppressWarnings("unchecked")
            ResponseInputStream<GetObjectResponse> real = (ResponseInputStream<GetObjectResponse>) chamada.callRealMethod();
            abertos.incrementAndGet();
            InputStream contador = new FilterInputStream(real) {
                @Override
                public void close() throws IOException {
                    fechados.incrementAndGet();
                    super.close();
                }
            };
            return new ResponseInputStream<>(real.response(), AbortableInputStream.create(contador, real::abort));
        }).when(s3).getObject(any(Consumer.class));
        String uri = "http://localhost:" + porta + "/api/evidencias/" + id + "/conteudo";
        HttpClient cliente = HttpClient.newHttpClient();

        HttpResponse<byte[]> get = cliente.send(HttpRequest.newBuilder(URI.create(uri)).header("Authorization", token(SUB_GESTOR, "GESTOR")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Mockito.clearInvocations(s3);
        abertos.set(0);
        fechados.set(0);
        HttpResponse<byte[]> head = cliente.send(HttpRequest.newBuilder(URI.create(uri)).header("Authorization", token(SUB_GESTOR, "GESTOR"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        for (String cabecalho : List.of("Content-Type", "Content-Length", "ETag", "Cache-Control", "Content-Disposition")) {
            assertThat(head.headers().allValues(cabecalho)).as(cabecalho).isEqualTo(get.headers().allValues(cabecalho));
        }
        assertThat(head.headers().firstValueAsLong("Content-Length")).hasValue(get.body().length);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(abertos.get() - fechados.get())
                .as("streams do storage abertos e não fechados após o HEAD (abertos=" + abertos + ")").isZero());
    }

    // RN-81, RN-104: EXPURGADA responde 410 em qualquer variante; os metadados continuam na listagem.
    @Test
    void shouldAnswer410ForExpungedEvidence() throws Exception {
        UUID id = inserir(chamado, atendimento, "EXPURGADA", false, T0, png());

        for (String variante : List.of("", "?variante=miniatura", "?variante=original")) {
            MockHttpServletResponse r = baixar(SUB_GESTOR, "GESTOR", "/api/evidencias/" + id + "/conteudo" + variante);
            assertThat(r.getStatus()).as(variante).isEqualTo(410);
            assertThat(json.readTree(r.getContentAsString()).get("codigo").asString()).isEqualTo("EVIDENCIA_EXPURGADA");
        }
    }

    @Test
    void shouldAnswer400ForUnknownVariant() throws Exception {
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", true, T0, png());

        mvc.perform(get("/api/evidencias/{id}/conteudo?variante=gigante", id).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACAO"));
    }

    // RF-28: 10 MB saem pelo servidor real sem ser lidos inteiros do storage. O stream do storage é embrulhado num que
    // trava depois de ~1 MB lido; o cliente recebe os primeiros bytes mesmo assim (se o servidor lesse tudo antes de
    // responder, travaria na leitura e o cliente não receberia nada). Só então a trava é solta e o corpo é conferido.
    @Test
    void shouldStreamTenMegabytesWithoutReadingTheWholeObjectBeforeResponding() throws Exception {
        byte[] grande = new byte[10 * 1024 * 1024];
        new Random(7).nextBytes(grande);
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", false, T0, grande);
        CountDownLatch liberar = new CountDownLatch(1);
        AtomicLong lidoDoStorage = new AtomicLong();
        Mockito.doAnswer(chamada -> {
            @SuppressWarnings("unchecked")
            ResponseInputStream<GetObjectResponse> real = (ResponseInputStream<GetObjectResponse>) chamada.callRealMethod();
            InputStream travavel = new FilterInputStream(real) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (lidoDoStorage.get() >= 1024 * 1024) {
                        try {
                            if (!liberar.await(30, TimeUnit.SECONDS)) {
                                throw new IOException("trava não liberada");
                            }
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        }
                    }
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        lidoDoStorage.addAndGet(n);
                    }
                    return n;
                }
            };
            return new ResponseInputStream<>(real.response(), AbortableInputStream.create(travavel, real::abort));
        }).when(s3).getObject(any(Consumer.class));

        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/evidencias/" + id + "/conteudo?variante=original"))
                .header("Authorization", token(SUB_GESTOR, "GESTOR")).GET().build();
        HttpResponse<InputStream> resposta = HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream corpo = resposta.body()) {
            assertThat(resposta.statusCode()).isEqualTo(200);
            assertThat(resposta.headers().firstValueAsLong("Content-Length")).hasValue(grande.length);
            byte[] primeiros = CompletableFuture.supplyAsync(() -> {
                try {
                    return corpo.readNBytes(512 * 1024);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).get(15, TimeUnit.SECONDS);
            assertThat(primeiros).hasSize(512 * 1024).isEqualTo(java.util.Arrays.copyOf(grande, 512 * 1024));
            assertThat(lidoDoStorage.get()).as("bytes lidos do storage enquanto o cliente já recebia").isLessThan(2L * 1024 * 1024);

            liberar.countDown();
            byte[] resto = corpo.readAllBytes();

            assertThat(primeiros.length + resto.length).isEqualTo(grande.length);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(primeiros);
            digest.update(resto);
            assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(sha256(grande));
        } finally {
            liberar.countDown();
        }
    }

    // RN-79, RN-108: nenhuma rota de exclusão de evidência existe, nem no mapeamento do Spring nem em resposta a um DELETE.
    @Test
    void shouldNotExposeAnyEvidenceDeletionEndpoint() throws Exception {
        UUID id = inserir(chamado, atendimento, "DISPONIVEL", true, T0, png());

        for (RequestMappingInfo info : mapeamentos.getHandlerMethods().keySet()) {
            boolean daEvidencia = info.getPathPatternsCondition().getPatternValues().stream().anyMatch(p -> p.contains("evidencia"));
            if (daEvidencia) {
                assertThat(info.getMethodsCondition().getMethods()).as(info.toString()).doesNotContain(
                        org.springframework.web.bind.annotation.RequestMethod.DELETE);
            }
        }
        String autorizacao = token(SUB_GESTOR, "GESTOR");
        mvc.perform(delete("/api/evidencias/{id}", id).header(HttpHeaders.AUTHORIZATION, autorizacao))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/chamados/{c}/evidencias/{e}", chamado, id).header(HttpHeaders.AUTHORIZATION, autorizacao))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/chamados/{c}/evidencias", chamado).header(HttpHeaders.AUTHORIZATION, autorizacao))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/evidencias/{id}/conteudo", id).header(HttpHeaders.AUTHORIZATION, autorizacao))
                .andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("select count(*) from evidencia where id = ?", Long.class, id)).isEqualTo(1);
    }

    // O corpo do StreamingResponseBody é escrito por outra thread: os cabeçalhos de segurança precisam ir antes do controller,
    // senão o HeaderWriterFilter disputa a resposta com ela (ConcurrentModificationException na suíte completa). A corrida
    // não se reproduz de forma determinística; o teste confere a configuração que a impede.
    @Test
    void shouldWriteSecurityHeadersEagerlySoTheStreamingThreadDoesNotRace() {
        HeaderWriterFilter filtro = contexto.getBean("api", SecurityFilterChain.class).getFilters().stream()
                .filter(HeaderWriterFilter.class::isInstance).map(HeaderWriterFilter.class::cast)
                .findFirst().orElseThrow();
        assertThat(ReflectionTestUtils.getField(filtro, "shouldWriteHeadersEagerly")).isEqualTo(true);
    }

    // ---- apoio ----

    /** GET pelo MockMvc; o corpo é um StreamingResponseBody, escrito de forma assíncrona. */
    private MockHttpServletResponse baixar(String sub, String papel, String caminho, String... cabecalhos) throws Exception {
        var pedido = get(caminho).header(HttpHeaders.AUTHORIZATION, token(sub, papel));
        for (int i = 0; i < cabecalhos.length; i += 2) {
            pedido.header(cabecalhos[i], cabecalhos[i + 1]);
        }
        MvcResult resultado = mvc.perform(pedido).andReturn();
        if (resultado.getRequest().isAsyncStarted()) {
            resultado.getAsyncResult(10_000);
            resultado = mvc.perform(asyncDispatch(resultado)).andReturn();
        }
        return resultado.getResponse();
    }

    private UUID inserir(UUID doChamado, UUID doAtendimento, String estado, boolean comDerivados, Instant enviadaEm, byte[] original) {
        UUID id = UUID.randomUUID();
        String chave = ArmazenamentoTestHelper.original(doChamado, id, "png");
        String miniatura = null;
        String visualizacao = null;
        if (!estado.equals("EXPURGADA")) {
            storage.gravar(chave, original, "image/png");
        }
        if (comDerivados) {
            miniatura = ArmazenamentoTestHelper.miniatura(doChamado, id);
            visualizacao = ArmazenamentoTestHelper.visualizacao(doChamado, id);
            storage.gravar(miniatura, jpeg(320, 240), "image/jpeg");
            storage.gravar(visualizacao, jpeg(1280, 960), "image/jpeg");
        }
        jdbc.update("""
                insert into evidencia (id, created_at, updated_at, atendimento_id, chamado_id, nome, tipo_conteudo, tamanho,
                                       hash_sha256, chave_original, chave_miniatura, chave_visualizacao, estado, enviada_em,
                                       expurgada_em, version)
                values (?, now(), now(), ?, ?, 'foto.png', 'image/png', ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                id, doAtendimento, doChamado, original.length, sha256(original), chave, miniatura, visualizacao, estado,
                java.sql.Timestamp.from(enviadaEm),
                estado.equals("EXPURGADA") ? java.sql.Timestamp.from(Instant.parse("2026-11-16T11:00:00Z")) : null);
        return id;
    }

    private UUID atendimento(UUID doChamado, String estado) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version) "
                + "values (?, now(), now(), ?, ?, ?, 0)", id, doChamado, tecnico, estado);
        return id;
    }

    private static byte[] png() {
        return FotoTeste.png(FotoTeste.quadrantes());
    }

    private static byte[] jpeg(int largura, int altura) {
        try {
            BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            ImageIO.write(imagem, "jpeg", saida);
            return saida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int largura(byte[] imagem) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(imagem)).getWidth();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
