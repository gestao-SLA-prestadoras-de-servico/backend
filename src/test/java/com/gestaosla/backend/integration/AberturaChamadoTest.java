package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.sla.api.CalendarioJanela;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class AberturaChamadoTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    // Segunda-feira, 09:00. Padrão NORMAL: resposta 540, campo 1620, resolução 2700 minutos de janela.
    private static final Instant SEGUNDA = em("2026-11-16T09:00");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private JsonMapper json;

    // Falha simulada no cálculo dos prazos: o calendário é chamado por RelogiosSla.iniciarRelogios.
    @MockitoSpyBean
    private CalendarioJanela calendario;

    private UUID empresa;
    private String cliente;

    @BeforeEach
    void preparar() {
        relogio.definir(SEGUNDA);
        empresa = ChamadoTeste.cliente(jdbc);
        cliente = token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE");
    }

    // RF-12, RF-30, RN-14: número legível e os três prazos gravados, pela prioridade padrão da versão.
    @Test
    void shouldOpenChamadoWithNumberAndThreeDeadlines() throws Exception {
        UUID contrato = ChamadoTeste.contrato(jdbc, empresa);

        String corpo = abrir(cliente, contrato, null)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/chamados/")))
                .andExpect(jsonPath("$.numero").isNumber())
                .andExpect(jsonPath("$.estado").value("ABERTO"))
                .andExpect(jsonPath("$.contratoId").value(contrato.toString()))
                .andExpect(jsonPath("$.prioridade").value("NORMAL"))
                .andExpect(jsonPath("$.prioridadeDefinida").doesNotExist())
                .andExpect(jsonPath("$.relogios.length()").value(3))
                .andExpect(jsonPath("$.relogios[0].tipo").value("RESPOSTA"))
                .andExpect(jsonPath("$.relogios[0].venceEm").value(em("2026-11-17T09:00").toString()))
                .andExpect(jsonPath("$.relogios[1].venceEm").value(em("2026-11-19T09:00").toString()))
                .andExpect(jsonPath("$.relogios[2].venceEm").value(em("2026-11-23T09:00").toString()))
                .andExpect(jsonPath("$.atendimentos.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(corpo).get("id").asString());
        assertThat(jdbc.queryForList("select vence_em from relogio_sla where chamado_id = ? order by vence_em", Instant.class, id))
                .containsExactly(em("2026-11-17T09:00"), em("2026-11-19T09:00"), em("2026-11-23T09:00"));
        assertThat(jdbc.queryForObject("select politica_versao_id from chamado where id = ?", UUID.class, id))
                .isEqualTo(ChamadoTeste.VERSAO_PADRAO);
        assertThat(jdbc.queryForObject("select aberto_em from chamado where id = ?", Instant.class, id)).isEqualTo(SEGUNDA);
        assertThat(jdbc.queryForObject("""
                select count(*) from auditoria where entidade_id = ? and operacao = 'ABERTURA_CHAMADO' and entidade_tipo = 'CHAMADO'""",
                Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForList("select evento from chamado_historico where chamado_id = ?", String.class, id))
                .containsExactly("ABERTURA");
    }

    // RN-126: contrato de outra empresa responde como inexistente.
    @Test
    void shouldAnswer404ForContractOfAnotherCompany() throws Exception {
        UUID deOutra = ChamadoTeste.contrato(jdbc, ChamadoTeste.cliente(jdbc));

        abrir(cliente, deOutra, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("RECURSO_NAO_ENCONTRADO"));
        abrir(cliente, UUID.randomUUID(), null).andExpect(status().isNotFound());
        assertThat(chamadosDe(deOutra)).isZero();
    }

    // RN-01: contrato vencido ou ainda não iniciado não recebe chamado.
    @Test
    void shouldRejectContractOutsideValidity() throws Exception {
        UUID vencido = contrato("2025-01-01", "2026-11-15", ChamadoTeste.POLITICA_PADRAO);
        UUID futuro = contrato("2026-11-17", "2027-12-31", ChamadoTeste.POLITICA_PADRAO);

        for (UUID contrato : List.of(vencido, futuro)) {
            abrir(cliente, contrato, null).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.codigo").value("CONTRATO_FORA_VIGENCIA"));
            assertThat(chamadosDe(contrato)).isZero();
        }
        // O último dia da vigência ainda vale (RN-01).
        abrir(cliente, contrato("2025-01-01", "2026-11-16", ChamadoTeste.POLITICA_PADRAO), null).andExpect(status().isCreated());
    }

    // RN-03: contrato sem política não recebe chamado.
    @Test
    void shouldRejectContractWithoutPolicy() throws Exception {
        UUID semPolitica = contrato("2025-01-01", "2027-12-31", null);

        abrir(cliente, semPolitica, null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("CONTRATO_SEM_POLITICA"));
        assertThat(chamadosDe(semPolitica)).isZero();
    }

    // RN-15: a prioridade sugerida fica registrada, mas não muda nenhum prazo.
    @Test
    void shouldKeepSuggestedPriorityWithoutChangingDeadlines() throws Exception {
        UUID contrato = ChamadoTeste.contrato(jdbc, empresa);

        abrir(cliente, contrato, "ALTA")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prioridadeSugerida").value("ALTA"))
                .andExpect(jsonPath("$.prioridade").value("NORMAL"))
                .andExpect(jsonPath("$.relogios[0].prazoMinutos").value(540))
                .andExpect(jsonPath("$.relogios[0].venceEm").value(em("2026-11-17T09:00").toString()));
    }

    // RN-61: aberto fora da janela, o prazo começa a correr na próxima abertura (segunda 08:00).
    @Test
    void shouldStartCountingAtNextWindowOpening() throws Exception {
        relogio.definir(em("2026-11-14T10:00")); // sábado

        abrir(cliente, ChamadoTeste.contrato(jdbc, empresa), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.abertoEm").value(em("2026-11-14T10:00").toString()))
                .andExpect(jsonPath("$.relogios[0].venceEm").value(em("2026-11-16T17:00").toString()));
    }

    // RN-111: falha no cálculo dos prazos desfaz tudo: chamado, auditoria e histórico.
    @Test
    void shouldRollBackEverythingWhenDeadlineCalculationFails() throws Exception {
        UUID contrato = ChamadoTeste.contrato(jdbc, empresa);
        long auditoriasAntes = jdbc.queryForObject("select count(*) from auditoria where operacao = 'ABERTURA_CHAMADO'", Long.class);
        long historicoAntes = jdbc.queryForObject("select count(*) from chamado_historico", Long.class);
        doThrow(new IllegalStateException("falha simulada")).when(calendario).adicionarMinutos(any(), anyLong());

        abrir(cliente, contrato, null).andExpect(status().isInternalServerError());

        assertThat(chamadosDe(contrato)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auditoria where operacao = 'ABERTURA_CHAMADO'", Long.class))
                .isEqualTo(auditoriasAntes);
        assertThat(jdbc.queryForObject("select count(*) from chamado_historico", Long.class)).isEqualTo(historicoAntes);
    }

    // RN-14: aberturas simultâneas recebem números distintos e crescentes.
    @Test
    void shouldGiveUniqueIncreasingNumbersToConcurrentOpenings() throws Exception {
        UUID contrato = ChamadoTeste.contrato(jdbc, empresa);
        long maiorAntes = jdbc.queryForObject("select coalesce(max(numero), 0) from chamado", Long.class);
        ExecutorService threads = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Integer>> aberturas = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                aberturas.add(() -> abrir(cliente, contrato, null).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> resultado : threads.invokeAll(aberturas)) {
                assertThat(resultado.get()).isEqualTo(201);
            }
        } finally {
            threads.shutdown();
        }

        List<Long> numeros = jdbc.queryForList("select numero from chamado where contrato_id = ?", Long.class, contrato);
        // Entre aberturas simultâneas a ordem não é definida; crescente quer dizer: depois de todos os anteriores.
        assertThat(numeros).hasSize(10).doesNotHaveDuplicates().allMatch(n -> n > maiorAntes);
    }

    @Test
    void shouldValidateBody() throws Exception {
        mvc.perform(post("/api/chamados").header(HttpHeaders.AUTHORIZATION, cliente)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titulo\": \"\", \"descricao\": \"d\", \"local\": \"l\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"));
    }

    @Test
    void shouldAllowOnlyClientToOpen() throws Exception {
        UUID contrato = ChamadoTeste.contrato(jdbc, empresa);

        abrir(token(SUB_GESTOR, "GESTOR"), contrato, null).andExpect(status().isForbidden());
        abrir(token(ChamadoTeste.usuario(jdbc, "TECNICO", null), "TECNICO"), contrato, null).andExpect(status().isForbidden());
        assertThat(chamadosDe(contrato)).isZero();
    }

    private ResultActions abrir(String quem, UUID contrato, String sugerida) throws Exception {
        String prioridade = sugerida == null ? "" : ", \"prioridadeSugerida\": \"" + sugerida + "\"";
        return mvc.perform(post("/api/chamados").header(HttpHeaders.AUTHORIZATION, quem)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"contratoId": "%s", "titulo": "Ar-condicionado parado", "descricao": "Não liga desde ontem",
                         "local": "Sala 3"%s}""".formatted(contrato, prioridade)));
    }

    private UUID contrato(String inicio, String fim, UUID politica) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into contrato (id, created_at, updated_at, cliente_id, codigo, inicio_vigencia, fim_vigencia, politica_id)
                values (?, now(), now(), ?, ?, ?::date, ?::date, ?)""", id, empresa, "CT-" + id, inicio, fim, politica);
        return id;
    }

    private long chamadosDe(UUID contrato) {
        return jdbc.queryForObject("select count(*) from chamado where contrato_id = ?", Long.class, contrato);
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
