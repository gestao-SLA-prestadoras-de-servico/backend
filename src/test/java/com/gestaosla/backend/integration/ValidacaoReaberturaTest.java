package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;
import com.gestaosla.backend.sla.api.RelogiosSla;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ValidacaoReaberturaTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    private static final Instant ABERTURA = em("2026-11-16T09:00");
    private static final Instant RESOLUCAO = em("2026-11-17T10:00");
    private static final Instant AGORA = em("2026-11-18T11:00");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private RelogiosSla relogios;

    @Autowired
    private ContratoConsulta contratos;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private ApplicationContext contexto;

    private UUID empresa;
    private UUID cliente;
    private UUID tecnico;
    private UUID chamado;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        empresa = ChamadoTeste.cliente(jdbc);
        cliente = UUID.fromString(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa));
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        chamado = ChamadoTeste.chamado(jdbc, empresa, "RESOLVIDO", tecnico);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, ABERTURA));
        // Ciclo resolvido: campo e resolução cumpridos; o aviso da validação automática já tinha saído.
        jdbc.update("update relogio_sla set cumprido_em = ? where chamado_id = ? and tipo in ('CAMPO', 'RESOLUCAO')",
                Timestamp.from(RESOLUCAO), chamado);
        jdbc.update("update chamado set resolvido_em = ?, aviso_enviado_em = ? where id = ?", Timestamp.from(RESOLUCAO),
                Timestamp.from(AGORA), chamado);
        UUID atendimento = new ChamadoTestHelper(contexto).criarAtendimentoPendente(chamado, tecnico);
        jdbc.update("update atendimento set estado = 'CONCLUIDO', encerrado_em = ? where id = ?", Timestamp.from(RESOLUCAO), atendimento);
    }

    // RF-20: o cliente valida; ENCERRADO com estado_final_em e a apuração congelada.
    @Test
    void shouldCloseTicketFreezingAssessment() throws Exception {
        validar(cliente, version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENCERRADO"))
                .andExpect(jsonPath("$.estadoFinalEm").value(AGORA.toString()));

        assertThat(jdbc.queryForObject("select count(*) from apuracao_sla where chamado_id = ?", Long.class, chamado)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'MUDANCA_ESTADO' "
                + "and dados->>'para' = 'ENCERRADO' and ator_sub = ?", Long.class, chamado, cliente)).isEqualTo(1);
    }

    // RN-91, RN-126: cliente de outra empresa não enxerga o chamado.
    @Test
    void shouldReturn404ToClientOfAnotherCompany() throws Exception {
        UUID outro = UUID.fromString(ChamadoTeste.usuario(jdbc, "CLIENTE", ChamadoTeste.cliente(jdbc)));

        validar(outro, version()).andExpect(status().isNotFound());
        reabrir(outro, "Voltou a falhar", version()).andExpect(status().isNotFound());

        assertThat(estado()).isEqualTo("RESOLVIDO");
    }

    // RN-91: só o cliente valida ou reabre.
    @Test
    void shouldForbidManagerAndTechnician() throws Exception {
        String corpo = "{\"version\": " + version() + "}";
        mvc.perform(post(rota("validacao")).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))
                .contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isForbidden());
        mvc.perform(post(rota("reabertura")).header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO"))
                .contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isForbidden());
    }

    // RN-10: só RESOLVIDO é validado.
    @Test
    void shouldRejectValidationOutsideResolved() throws Exception {
        jdbc.update("update chamado set estado = 'EM_ATENDIMENTO' where id = ?", chamado);

        validar(cliente, version()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
    }

    // RF-21, RN-93 a RN-97: volta a ATRIBUIDO com o mesmo técnico, atendimento novo, ciclo novo de campo e resolução.
    @Test
    void shouldReopenToSameTechnicianRestartingFieldAndResolutionClocks() throws Exception {
        reabrir(cliente, "A impressora voltou a travar", version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ATRIBUIDO"))
                .andExpect(jsonPath("$.resolvidoEm").doesNotExist());

        assertThat(jdbc.queryForObject("select tecnico_sub from chamado where id = ?", UUID.class, chamado)).isEqualTo(tecnico);
        assertThat(jdbc.queryForObject("select aviso_enviado_em from chamado where id = ?", Timestamp.class, chamado)).isNull();
        assertThat(new ChamadoTestHelper(contexto).estadosDosAtendimentos(chamado))
                .containsExactly(EstadoAtendimento.CONCLUIDO, EstadoAtendimento.PENDENTE);
        List<Map<String, Object>> ativos = jdbc.queryForList(
                "select tipo, ciclo, marco_inicial from relogio_sla where chamado_id = ? and ativo order by tipo", chamado);
        assertThat(ativos).extracting(r -> r.get("tipo") + ":" + r.get("ciclo"))
                .containsExactlyInAnyOrder("RESPOSTA:1", "CAMPO:2", "RESOLUCAO:2");
        assertThat(ativos).filteredOn(r -> !"RESPOSTA".equals(r.get("tipo")))
                .allSatisfy(r -> assertThat(((Timestamp) r.get("marco_inicial")).toInstant()).isEqualTo(AGORA));
        assertThat(ativos).filteredOn(r -> "RESPOSTA".equals(r.get("tipo")))
                .allSatisfy(r -> assertThat(((Timestamp) r.get("marco_inicial")).toInstant()).isEqualTo(ABERTURA));
        // RF-54: o técnico recebe a REABERTURA.
        assertThat(jdbc.queryForList("select payload->'destinatarios'->>0 from outbox_eventos where tipo = 'REABERTURA' "
                + "and payload->'chamado'->>'id' = ?", String.class, chamado.toString())).containsExactly(tecnico.toString());
    }

    // RN-121, RN-123: quem, qual chamado, quando, justificativa e transição.
    @Test
    void shouldAuditReopening() throws Exception {
        reabrir(cliente, "A impressora voltou a travar", version()).andExpect(status().isOk());

        Map<String, Object> auditoria = jdbc.queryForMap("""
                select ator_sub, perfil, entidade_id, ocorrido_em, dados->>'justificativa' as justificativa,
                       dados->>'de' as de, dados->>'para' as para
                from auditoria where chamado_id = ? and operacao = 'REABERTURA'""", chamado);
        assertThat(auditoria).containsEntry("ator_sub", cliente).containsEntry("perfil", "CLIENTE")
                .containsEntry("entidade_id", chamado).containsEntry("justificativa", "A impressora voltou a travar")
                .containsEntry("de", "RESOLVIDO").containsEntry("para", "ATRIBUIDO");
        assertThat(((Timestamp) auditoria.get("ocorrido_em")).toInstant()).isEqualTo(AGORA);
        mvc.perform(get(rota("historico")).header(HttpHeaders.AUTHORIZATION, token(cliente.toString(), "CLIENTE")))
                .andExpect(jsonPath("$[?(@.evento == 'REABERTURA')].dados.justificativa").value("A impressora voltou a travar"));
    }

    // RN-99: ENCERRADO não reabre.
    @Test
    void shouldRejectReopeningClosedTicket() throws Exception {
        validar(cliente, version()).andExpect(status().isOk());

        reabrir(cliente, "Voltou a falhar", version()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REABERTURA_NAO_PERMITIDA"));
        assertThat(estado()).isEqualTo("ENCERRADO");
    }

    // RN-98: justificativa obrigatória.
    @Test
    void shouldRequireJustification() throws Exception {
        reabrir(cliente, " ", version()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACAO"));
        assertThat(estado()).isEqualTo("RESOLVIDO");
    }

    // RN-117: versão lida desatualizada responde 409 sem gravar.
    @Test
    void shouldReturn409ForStaleVersion() throws Exception {
        long lida = version();
        jdbc.update("update chamado set version = version + 1 where id = ?", chamado);

        validar(cliente, lida).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLITO_VERSAO"));
        reabrir(cliente, "Voltou a falhar", lida).andExpect(status().isConflict());
        assertThat(estado()).isEqualTo("RESOLVIDO");
    }

    // Validação e reabertura ao mesmo tempo, com a mesma versão lida: só uma vence, a outra recebe 409.
    @Test
    void shouldLetOnlyOneOfConcurrentValidationAndReopeningWin() throws Exception {
        long lida = version();
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> acoes = List.of(
                    () -> {
                        largada.await();
                        return validar(cliente, lida).andReturn().getResponse().getStatus();
                    },
                    () -> {
                        largada.await();
                        return reabrir(cliente, "Voltou a falhar", lida).andReturn().getResponse().getStatus();
                    });
            List<Future<Integer>> futuros = acoes.stream().map(threads::submit).toList();
            largada.countDown();
            List<Integer> statusRecebidos = futuros.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(statusRecebidos).containsExactlyInAnyOrder(200, 409);
        } finally {
            threads.shutdownNow();
        }
        assertThat(estado()).isIn("ENCERRADO", "ATRIBUIDO");
    }

    private ResultActions validar(UUID sub, long version) throws Exception {
        return mvc.perform(post(rota("validacao")).header(HttpHeaders.AUTHORIZATION, token(sub.toString(), "CLIENTE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\": " + version + "}"));
    }

    private ResultActions reabrir(UUID sub, String justificativa, long version) throws Exception {
        return mvc.perform(post(rota("reabertura")).header(HttpHeaders.AUTHORIZATION, token(sub.toString(), "CLIENTE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justificativa\": \"" + justificativa + "\", \"version\": " + version + "}"));
    }

    private long version() {
        return jdbc.queryForObject("select version from chamado where id = ?", Long.class, chamado);
    }

    private String estado() {
        return jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado);
    }

    private String rota(String acao) {
        return "/api/chamados/" + chamado + "/" + acao;
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
