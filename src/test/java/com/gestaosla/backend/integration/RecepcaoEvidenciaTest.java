package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.FotoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class RecepcaoEvidenciaTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private JsonMapper json;

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
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "EM_ATENDIMENTO", tecnico);
        atendimento = atendimento(chamado, tecnico, "EM_ANDAMENTO");
    }

    // RF-24, RF-25, RN-77, RN-83, RN-85, RN-113, RN-121: 202 e, na mesma transação, linha, auditoria e evento.
    @Test
    void shouldReceiveEvidenceStoreItAndRecordRowAuditAndEvent() throws Exception {
        byte[] enviado = FotoTeste.jpegComExif(1, false);

        String corpo = enviar(tecnico, "foto-da-obra.jpg", "image/jpeg", enviado).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("RECEBIDA")).andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(corpo).get("id").asString());
        Map<String, Object> linha = jdbc.queryForMap("select * from evidencia where id = ?", id);
        assertThat(linha).containsEntry("estado", "RECEBIDA").containsEntry("atendimento_id", atendimento)
                .containsEntry("chamado_id", chamado).containsEntry("nome", "foto-da-obra.jpg")
                .containsEntry("tipo_conteudo", "image/jpeg");
        assertThat(linha.get("chave_miniatura")).isNull();
        assertThat(((java.sql.Timestamp) linha.get("enviada_em")).toInstant()).isEqualTo(AGORA);
        String chave = (String) linha.get("chave_original");
        assertThat(chave).isEqualTo("evidencias/" + chamado + "/" + id + "/original.jpg");
        byte[] gravado = storage.ler(chave);
        assertThat(linha.get("hash_sha256")).isEqualTo(sha256(gravado));
        assertThat(((Number) linha.get("tamanho")).longValue()).isEqualTo(gravado.length);
        assertThat(gravado).isNotEqualTo(enviado); // regravado, não repassado

        Map<String, Object> auditoria = jdbc.queryForMap("select * from auditoria where entidade_id = ?", id);
        assertThat(auditoria).containsEntry("operacao", "ENVIO_EVIDENCIA").containsEntry("entidade_tipo", "EVIDENCIA")
                .containsEntry("chamado_id", chamado).containsEntry("ator_sub", tecnico);
        assertThat(auditoria.get("dados").toString()).contains(sha256(gravado)).doesNotContain("foto-da-obra");

        Map<String, Object> evento = jdbc.queryForMap("select * from outbox_eventos where tipo = 'PROCESSAR_EVIDENCIA' "
                + "and payload->>'evidenciaId' = ?", id.toString());
        assertThat(evento).containsEntry("exchange", "evidencias").containsEntry("routing_key", "processamento");
        Map<String, Object> payload = json.readValue(evento.get("payload").toString(), Map.class);
        assertThat(payload.keySet()).containsExactlyInAnyOrder("evidenciaId", "chamadoId", "chaveOriginal", "eventoId");
        assertThat(payload).containsEntry("chaveOriginal", chave).containsEntry("chamadoId", chamado.toString());
        assertThat(evento.get("payload").toString().length()).isLessThan(500);
    }

    // RN-76: foto de celular com GPS e orientação 6 sai em pé e sem nenhum EXIF, lida do objeto gravado no MinIO.
    @Test
    void shouldStorePhoneUprightWithoutAnyExif() throws Exception {
        byte[] foto = FotoTeste.jpegComExif(6, true);

        UUID id = enviarOk(foto, "celular.jpg");

        byte[] gravado = storage.ler(chaveDe(id));
        BufferedImage imagem = ImageIO.read(new ByteArrayInputStream(gravado));
        assertThat(imagem.getWidth()).isEqualTo(FotoTeste.ALTURA);
        assertThat(imagem.getHeight()).isEqualTo(FotoTeste.LARGURA);
        // Rotação de 90° horário: o azul (baixo-esquerda) vai para o topo-esquerda; o vermelho, para o topo-direita.
        assertThat(imagem.getRGB(8, 12) & 0xFF).as("topo-esquerda azul").isGreaterThan(200);
        assertThat((imagem.getRGB(24, 12) >> 16) & 0xFF).as("topo-direita vermelho").isGreaterThan(200);
        Metadata metadados = ImageMetadataReader.readMetadata(new ByteArrayInputStream(gravado));
        assertThat(metadados.getDirectoriesOfType(com.drew.metadata.exif.GpsDirectory.class)).isEmpty();
        assertThat(metadados.getDirectoriesOfType(com.drew.metadata.exif.ExifIFD0Directory.class)).isEmpty();
        assertThat(new String(gravado, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    // RN-75: o PDF renomeado não passa; a extensão e o Content-Type do cliente não decidem.
    @Test
    void shouldRejectPdfRenamedToJpgAndStoreNothing() throws Exception {
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

        enviar(tecnico, "foto.jpg", "image/jpeg", pdf).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("FORMATO_NAO_SUPORTADO"));

        nadaFoiGravado();
    }

    @Test
    void shouldRejectCorruptedImage() throws Exception {
        byte[] corrompida = java.util.Arrays.copyOf(FotoTeste.jpegComExif(1, false), 40);

        enviar(tecnico, "foto.jpg", "image/jpeg", corrompida).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("IMAGEM_INVALIDA"));

        nadaFoiGravado();
    }

    // RN-75: o cabeçalho declara 10 bilhões de pixels; é recusado sem decodificar (senão o teste estouraria a memória).
    @Test
    void shouldRejectHugeDimensionsBeforeDecoding() throws Exception {
        long inicio = System.nanoTime();

        enviar(tecnico, "grande.png", "image/png", FotoTeste.pngCabecalhoDeclarando(100_000, 100_000))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.codigo").value("IMAGEM_MUITO_GRANDE"));

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
        nadaFoiGravado();
    }

    // RN-75: acima de 10 MB, pela configuração real do multipart (servidor de verdade, não o MockMvc): 413 e nada gravado.
    @Test
    void shouldRejectFileOverTenMegabytesWith413AndStoreNothing() throws Exception {
        byte[] grande = new byte[10 * 1024 * 1024 + 100 * 1024];
        System.arraycopy(FotoTeste.jpegComExif(1, false), 0, grande, 0, 100);

        HttpResponse<String> resposta = enviarPorHttp(tecnico, grande);

        assertThat(resposta.statusCode()).isEqualTo(413);
        assertThat(json.readTree(resposta.body()).get("codigo").asString()).isEqualTo("ARQUIVO_MUITO_GRANDE");
        nadaFoiGravado();
    }

    // Defesa do serviço, caso o limite do multipart seja afrouxado: o MockMvc não aplica o limite do servidor.
    @Test
    void shouldRejectOversizedFileInTheServiceToo() throws Exception {
        enviar(tecnico, "grande.jpg", "image/jpeg", new byte[10 * 1024 * 1024 + 1]).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.codigo").value("ARQUIVO_MUITO_GRANDE"));

        nadaFoiGravado();
    }

    // Decisão do usuário: o WebP é aceito e gravado como JPEG.
    @Test
    void shouldAcceptWebpAndStoreItAsJpeg() throws Exception {
        byte[] webp = getClass().getResourceAsStream("/evidencia/vermelho-azul.webp").readAllBytes();

        UUID id = enviarOk(webp, "foto.webp");

        assertThat(chaveDe(id)).endsWith("/original.jpg");
        assertThat(jdbc.queryForObject("select tipo_conteudo from evidencia where id = ?", String.class, id)).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new ByteArrayInputStream(storage.ler(chaveDe(id)))).getWidth()).isEqualTo(40);
    }

    @Test
    void shouldKeepPngAsPng() throws Exception {
        UUID id = enviarOk(FotoTeste.png(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)), "foto.png");

        assertThat(chaveDe(id)).endsWith("/original.png");
        assertThat(jdbc.queryForObject("select tipo_conteudo from evidencia where id = ?", String.class, id)).isEqualTo("image/png");
    }

    @Test
    void shouldKeepOnlyTheBaseNameOfTheUploadedFileName() throws Exception {
        UUID id = enviarOk(FotoTeste.jpegComExif(1, false), "C:\\Users\\fulano\\fotos\\" + "a".repeat(300) + ".jpg");

        String nome = jdbc.queryForObject("select nome from evidencia where id = ?", String.class, id);
        assertThat(nome).hasSize(255).doesNotContain("\\").startsWith("aaa");
    }

    // RN-74: só o técnico atribuído; o outro técnico recebe 404 (RN-126), gestor e cliente, 403 pela rota.
    @Test
    void shouldAnswer404ToTechnicianNotAssignedAnd403ToOtherRoles() throws Exception {
        UUID outro = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        byte[] foto = FotoTeste.jpegComExif(1, false);

        enviar(outro, "foto.jpg", "image/jpeg", foto).andExpect(status().isNotFound());
        enviar(UUID.randomUUID(), tecnico, chamado, "foto.jpg", "image/jpeg", foto, "GESTOR").andExpect(status().isForbidden());
        enviar(UUID.randomUUID(), tecnico, chamado, "foto.jpg", "image/jpeg", foto, "CLIENTE").andExpect(status().isForbidden());
        mvc.perform(multipart("/api/chamados/{id}/evidencias", chamado).file(new MockMultipartFile("arquivo", "f.jpg", "image/jpeg", foto)))
                .andExpect(status().isUnauthorized());

        nadaFoiGravado();
    }

    @Test
    void shouldAnswer404ForUnknownChamado() throws Exception {
        mvc.perform(multipart("/api/chamados/{id}/evidencias", UUID.randomUUID())
                .file(new MockMultipartFile("arquivo", "f.jpg", "image/jpeg", FotoTeste.jpegComExif(1, false)))
                .header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO"))).andExpect(status().isNotFound());
    }

    // RN-71: sem atendimento EM_ANDAMENTO (PENDENTE, ATRIBUIDO, sem atendimento ou chamado fora de EM_ATENDIMENTO), 422.
    @Test
    void shouldRejectWhenThereIsNoAttendanceInProgress() throws Exception {
        byte[] foto = FotoTeste.jpegComExif(1, false);
        UUID pendente = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "ATRIBUIDO", tecnico);
        atendimento(pendente, tecnico, "PENDENTE");
        UUID semAtendimento = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "ATRIBUIDO", tecnico);
        UUID resolvido = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "RESOLVIDO", tecnico);
        atendimento(resolvido, tecnico, "CONCLUIDO");

        for (UUID alvo : List.of(pendente, semAtendimento, resolvido)) {
            enviar(UUID.randomUUID(), tecnico, alvo, "foto.jpg", "image/jpeg", foto, "TECNICO")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
        }
        assertThat(jdbc.queryForObject("select count(*) from evidencia where chamado_id = any(?)", Long.class,
                (Object) List.of(pendente, semAtendimento, resolvido).toArray(UUID[]::new))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'PROCESSAR_EVIDENCIA' and payload->>'chamadoId' = any(?)",
                Long.class, (Object) List.of(pendente, semAtendimento, resolvido).stream().map(UUID::toString).toArray(String[]::new))).isZero();
    }

    // RN-112: com a transação falhando (aqui, o INSERT da outbox), o objeto continua no storage e nada fica no banco.
    @Test
    void shouldLeaveOrphanObjectAndNothingInTheDatabaseWhenTransactionFails() throws Exception {
        jdbc.execute("create function falha_teste_evidencia() returns trigger language plpgsql as $$ begin "
                + "raise exception 'falha simulada'; end; $$");
        jdbc.execute("create trigger falha_teste_evidencia before insert on outbox_eventos for each row "
                + "when (new.tipo = 'PROCESSAR_EVIDENCIA') execute function falha_teste_evidencia()");
        try {
            enviar(tecnico, "foto.jpg", "image/jpeg", FotoTeste.jpegComExif(1, false)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.codigo").value("ERRO_INTERNO"));
        } finally {
            jdbc.execute("drop trigger falha_teste_evidencia on outbox_eventos");
            jdbc.execute("drop function falha_teste_evidencia()");
        }

        assertThat(storage.listar("evidencias/" + chamado + "/")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from evidencia where chamado_id = ?", Long.class, chamado)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'ENVIO_EVIDENCIA'",
                Long.class, chamado)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'PROCESSAR_EVIDENCIA' and payload->>'chamadoId' = ?",
                Long.class, chamado.toString())).isZero();
    }

    // A porta do chamado: o detalhe passa a trazer a contagem de evidências por atendimento.
    @Test
    void shouldShowEvidenceCountPerAttendanceInChamadoDetail() throws Exception {
        enviarOk(FotoTeste.jpegComExif(1, false), "a.jpg");
        enviarOk(FotoTeste.jpegComExif(3, false), "b.jpg");

        mvc.perform(get("/api/chamados/" + chamado).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.atendimentos[0].id").value(atendimento.toString()))
                .andExpect(jsonPath("$.atendimentos[0].evidencias").value(2));
    }

    @Test
    void shouldRejectChangingHashOrOriginalKeyEvenBySql() throws Exception {
        UUID id = enviarOk(FotoTeste.jpegComExif(1, false), "a.jpg");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update evidencia set hash_sha256 = ? where id = ?", "0".repeat(64), id))
                .hasMessageContaining("RN-77");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update evidencia set chave_original = 'x' where id = ?", id))
                .hasMessageContaining("RN-77");
        assertThat(jdbc.update("update evidencia set estado = 'PROCESSANDO', chave_miniatura = 'm' where id = ?", id)).isEqualTo(1);
    }

    private UUID enviarOk(byte[] bytes, String nome) throws Exception {
        String corpo = enviar(tecnico, nome, "application/octet-stream", bytes).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(corpo).get("id").asString());
    }

    private ResultActions enviar(UUID quem, String nome, String contentType, byte[] bytes) throws Exception {
        return enviar(UUID.randomUUID(), quem, chamado, nome, contentType, bytes, "TECNICO");
    }

    private ResultActions enviar(UUID ignorado, UUID quem, UUID alvo, String nome, String contentType, byte[] bytes, String papel)
            throws Exception {
        return mvc.perform(multipart("/api/chamados/{id}/evidencias", alvo).file(new MockMultipartFile("arquivo", nome, contentType, bytes))
                .header(HttpHeaders.AUTHORIZATION, token(quem.toString(), papel)));
    }

    /** Multipart pelo servidor real, para valer o limite configurado em spring.servlet.multipart. */
    private HttpResponse<String> enviarPorHttp(UUID quem, byte[] bytes) throws Exception {
        String limite = "----teste" + UUID.randomUUID();
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        corpo.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"grande.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        corpo.write(bytes);
        corpo.write(("\r\n--" + limite + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/chamados/" + chamado + "/evidencias"))
                .header("Authorization", token(quem.toString(), "TECNICO")).header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray())).build();
        return HttpClient.newHttpClient().send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    private void nadaFoiGravado() {
        assertThat(storage.listar("evidencias/" + chamado + "/")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from evidencia where chamado_id = ?", Long.class, chamado)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'ENVIO_EVIDENCIA'",
                Long.class, chamado)).isZero();
    }

    private String chaveDe(UUID id) {
        return jdbc.queryForObject("select chave_original from evidencia where id = ?", String.class, id);
    }

    private UUID atendimento(UUID alvo, UUID tecnicoSub, String estado) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version) "
                + "values (?, now(), now(), ?, ?, ?, 0)", id, alvo, tecnicoSub, estado);
        return id;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
