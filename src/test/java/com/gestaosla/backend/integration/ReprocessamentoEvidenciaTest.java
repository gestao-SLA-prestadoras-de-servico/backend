package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.FotoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;

import tools.jackson.databind.json.JsonMapper;

/** Reprocessamento manual da evidência em FALHA (evidencia-05). A ida até DISPONIVEL está no ProcessadorEvidenciasTest. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ReprocessamentoEvidenciaTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RelogioMutavel relogio;
    @Autowired
    private JsonMapper json;

    private UUID tecnico;
    private UUID chamado;
    private UUID atendimento;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "EM_ATENDIMENTO", tecnico);
        atendimento = UUID.randomUUID();
        jdbc.update("insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version) "
                + "values (?, now(), now(), ?, ?, 'EM_ANDAMENTO', 0)", atendimento, chamado, tecnico);
    }

    // RF-29, RN-121: FALHA -> 202, RECEBIDA, evento novo (eventoId diferente do da recepção) e auditoria, tudo junto.
    @Test
    void shouldMoveFailedEvidenceBackToReceivedWithNewEventAndAudit() throws Exception {
        UUID id = enviarEvidencia();
        List<Map<String, Object>> eventosAntes = eventos(id);
        assertThat(eventosAntes).hasSize(1);
        jdbc.update("update evidencia set estado = 'FALHA' where id = ?", id);
        long versaoAntes = versao(id);

        reprocessar(id, SUB_GESTOR, "GESTOR").andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.estado").value("RECEBIDA"));

        Map<String, Object> linha = jdbc.queryForMap("select * from evidencia where id = ?", id);
        assertThat(linha).containsEntry("estado", "RECEBIDA");
        assertThat(((Number) linha.get("version")).longValue()).isEqualTo(versaoAntes + 1);
        assertThat(((java.sql.Timestamp) linha.get("updated_at")).toInstant()).isEqualTo(AGORA);

        List<Map<String, Object>> eventos = eventos(id);
        assertThat(eventos).hasSize(2);
        // O relógio do teste é fixo: os dois eventos têm o mesmo criado_em, então o novo é o de outro evento_id.
        Object eventoOriginal = eventosAntes.get(0).get("evento_id");
        Map<String, Object> novo = eventos.stream().filter(e -> !e.get("evento_id").equals(eventoOriginal)).findFirst().orElseThrow();
        assertThat(novo).containsEntry("tipo", "PROCESSAR_EVIDENCIA").containsEntry("exchange", "evidencias")
                .containsEntry("routing_key", "processamento");
        Map<?, ?> payloadNovo = json.readValue(novo.get("payload").toString(), Map.class);
        Map<?, ?> payloadAntigo = json.readValue(eventosAntes.get(0).get("payload").toString(), Map.class);
        assertThat(payloadNovo.get("evidenciaId")).isEqualTo(id.toString());
        assertThat(payloadNovo.get("chamadoId")).isEqualTo(chamado.toString());
        assertThat(payloadNovo.get("chaveOriginal")).isEqualTo(payloadAntigo.get("chaveOriginal")).isEqualTo(linha.get("chave_original"));
        assertThat(payloadNovo.get("eventoId")).isEqualTo(novo.get("evento_id").toString());
        assertThat(novo.get("publicado_em")).isNull();

        List<Map<String, Object>> auditorias = auditorias(id, "REPROCESSAMENTO");
        assertThat(auditorias).hasSize(1);
        assertThat(auditorias.get(0)).containsEntry("entidade_tipo", "EVIDENCIA").containsEntry("chamado_id", chamado)
                .containsEntry("ator_sub", UUID.fromString(SUB_GESTOR)).containsEntry("perfil", "GESTOR");
        assertThat(json.readValue(auditorias.get(0).get("dados").toString(), Map.class)).isEqualTo(Map.of("de", "FALHA", "para", "RECEBIDA"));
    }

    // RF-29: só FALHA é reprocessada; nos outros estados, 422 e nada é gravado (estado, versão, auditoria, outbox).
    @Test
    void shouldRejectEveryStateOtherThanFailureAndRecordNothing() throws Exception {
        for (String estado : List.of("RECEBIDA", "PROCESSANDO", "DISPONIVEL", "EXPURGADA")) {
            UUID id = inserir(estado);
            Map<String, Object> antes = jdbc.queryForMap("select * from evidencia where id = ?", id);

            reprocessar(id, SUB_GESTOR, "GESTOR").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.codigo").value("REPROCESSAMENTO_NAO_PERMITIDO"));

            assertThat(jdbc.queryForMap("select * from evidencia where id = ?", id)).as(estado).isEqualTo(antes);
            assertThat(auditorias(id, "REPROCESSAMENTO")).as(estado).isEmpty();
            assertThat(eventos(id)).as(estado).isEmpty();
        }
    }

    @Test
    void shouldAnswer403ToTechnicianAndClientAnd401WithoutToken() throws Exception {
        UUID id = inserir("FALHA");
        String cliente = ChamadoTeste.usuario(jdbc, "CLIENTE", ChamadoTeste.cliente(jdbc));

        reprocessar(id, tecnico.toString(), "TECNICO").andExpect(status().isForbidden());
        reprocessar(id, cliente, "CLIENTE").andExpect(status().isForbidden());
        mvc.perform(post("/api/evidencias/{id}/reprocessamento", id)).andExpect(status().isUnauthorized());

        assertThat(estado(id)).isEqualTo("FALHA");
        assertThat(eventos(id)).isEmpty();
        assertThat(auditorias(id, "REPROCESSAMENTO")).isEmpty();
    }

    @Test
    void shouldAnswer404ForUnknownEvidence() throws Exception {
        reprocessar(UUID.randomUUID(), SUB_GESTOR, "GESTOR").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RECURSO_NAO_ENCONTRADO"));
    }

    // O segundo pedido seguido encontra a evidência já RECEBIDA: 422, e só um evento e uma auditoria.
    @Test
    void shouldRejectASecondRequestInARow() throws Exception {
        UUID id = inserir("FALHA");

        reprocessar(id, SUB_GESTOR, "GESTOR").andExpect(status().isAccepted());
        reprocessar(id, SUB_GESTOR, "GESTOR").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REPROCESSAMENTO_NAO_PERMITIDO"));

        assertThat(eventos(id)).hasSize(1);
        assertThat(auditorias(id, "REPROCESSAMENTO")).hasSize(1);
    }

    // Dois gestores ao mesmo tempo: o UPDATE condicional deixa passar um só.
    @Test
    void shouldLetOnlyOneOfTwoSimultaneousRequestsThrough() throws Exception {
        UUID id = inserir("FALHA");
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> respostas = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                respostas.add(pool.submit((Callable<Integer>) () -> {
                    largada.await();
                    return reprocessar(id, SUB_GESTOR, "GESTOR").andReturn().getResponse().getStatus();
                }));
            }
            largada.countDown();
            List<Integer> status = List.of(respostas.get(0).get(), respostas.get(1).get());
            assertThat(status).containsExactlyInAnyOrder(202, 422);
        } finally {
            pool.shutdownNow();
        }

        assertThat(eventos(id)).hasSize(1);
        assertThat(auditorias(id, "REPROCESSAMENTO")).hasSize(1);
        assertThat(estado(id)).isEqualTo("RECEBIDA");
    }

    // ---- apoio ----

    private ResultActions reprocessar(UUID id, String sub, String papel) throws Exception {
        return mvc.perform(post("/api/evidencias/{id}/reprocessamento", id).header(HttpHeaders.AUTHORIZATION, token(sub, papel)));
    }

    /** Pelo endpoint, para existir o evento original da recepção na outbox. */
    private UUID enviarEvidencia() throws Exception {
        String corpo = mvc.perform(multipart("/api/chamados/{id}/evidencias", chamado)
                .file(new MockMultipartFile("arquivo", "f.png", "image/png", FotoTeste.png(FotoTeste.quadrantes())))
                .header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO"))).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(corpo).get("id").asString());
    }

    private UUID inserir(String estado) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into evidencia (id, created_at, updated_at, atendimento_id, chamado_id, nome, tipo_conteudo, tamanho,
                                       hash_sha256, chave_original, estado, enviada_em, version)
                values (?, now(), now(), ?, ?, 'f.jpg', 'image/jpeg', 10, ?, ?, ?, now(), 3)""",
                id, atendimento, chamado, "0".repeat(64), "evidencias/" + chamado + "/" + id + "/original.jpg", estado);
        return id;
    }

    private String estado(UUID id) {
        return jdbc.queryForObject("select estado from evidencia where id = ?", String.class, id);
    }

    private long versao(UUID id) {
        return jdbc.queryForObject("select version from evidencia where id = ?", Long.class, id);
    }

    private List<Map<String, Object>> eventos(UUID id) {
        return jdbc.queryForList("select * from outbox_eventos where tipo = 'PROCESSAR_EVIDENCIA' and payload->>'evidenciaId' = ? "
                + "order by criado_em, id", id.toString());
    }

    private List<Map<String, Object>> auditorias(UUID id, String operacao) {
        return jdbc.queryForList("select * from auditoria where entidade_id = ? and operacao = ?", id, operacao);
    }
}
