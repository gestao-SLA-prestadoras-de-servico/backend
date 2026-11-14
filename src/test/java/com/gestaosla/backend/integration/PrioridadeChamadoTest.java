package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class PrioridadeChamadoTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    // Segunda, 09:00. NORMAL: resposta terça 09:00, campo quinta 09:00, resolução segunda seguinte 09:00.
    // ALTA: resposta segunda 11:00, campo terça 09:00, resolução quarta 09:00.
    private static final Instant ABERTURA = em("2026-11-16T09:00");
    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");

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

    private UUID chamado;

    @BeforeEach
    void preparar() {
        relogio.definir(em("2026-11-16T10:30"));
        chamado = ChamadoTeste.chamado(jdbc);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, ABERTURA));
    }

    // RF-15: funciona antes de RESOLVIDO (decisão da task: não em RESOLVIDO).
    @ParameterizedTest
    @ValueSource(strings = {"ABERTO", "ATRIBUIDO", "EM_ATENDIMENTO"})
    void shouldChangePriorityBeforeResolved(String estado) throws Exception {
        jdbc.update("update chamado set estado = ? where id = ?", estado, chamado);

        alterar(GESTOR, "ALTA").andExpect(status().isOk())
                .andExpect(jsonPath("$.prioridade").value("ALTA"))
                .andExpect(jsonPath("$.prioridadeDefinida").value("ALTA"))
                .andExpect(jsonPath("$.prioridadeSugerida").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVIDO", "ENCERRADO", "CANCELADO"})
    void shouldRejectPriorityChangeFromResolvedOn(String estado) throws Exception {
        jdbc.update("update chamado set estado = ? where id = ?", estado, chamado);

        alterar(GESTOR, "ALTA").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
        assertThat(jdbc.queryForObject("select prioridade_definida from chamado where id = ?", String.class, chamado)).isNull();
        assertThat(venceEm(TipoRelogio.CAMPO)).isEqualTo(em("2026-11-19T09:00"));
    }

    // RN-17, RN-18: com a resposta cumprida, só campo e resolução são recalculados, do marco inicial.
    @Test
    void shouldRecalculateOnlyRunningClocks() throws Exception {
        transacao.executeWithoutResult(s -> relogios.cumprir(chamado, TipoRelogio.RESPOSTA, em("2026-11-16T10:00")));

        alterar(GESTOR, "ALTA").andExpect(status().isOk())
                .andExpect(jsonPath("$.relogios[0].tipo").value("RESPOSTA"))
                .andExpect(jsonPath("$.relogios[0].prazoMinutos").value(540))
                .andExpect(jsonPath("$.relogios[1].prazoMinutos").value(540))
                .andExpect(jsonPath("$.relogios[2].prazoMinutos").value(1080));

        assertThat(venceEm(TipoRelogio.RESPOSTA)).isEqualTo(em("2026-11-17T09:00"));
        assertThat(venceEm(TipoRelogio.CAMPO)).isEqualTo(em("2026-11-17T09:00"));
        assertThat(venceEm(TipoRelogio.RESOLUCAO)).isEqualTo(em("2026-11-18T09:00"));
    }

    // RN-18, RN-69: relógio já violado não volta a correr nem muda de prazo.
    @Test
    void shouldKeepViolatedClockViolated() throws Exception {
        relogio.definir(em("2026-11-17T10:00"));
        transacao.executeWithoutResult(s -> relogios.cumprir(chamado, TipoRelogio.RESPOSTA, em("2026-11-17T10:00")));

        alterar(GESTOR, "ALTA").andExpect(status().isOk())
                .andExpect(jsonPath("$.relogios[0].estado").value("VIOLADO"))
                .andExpect(jsonPath("$.relogios[0].venceEm").value(em("2026-11-17T09:00").toString()));
        assertThat(jdbc.queryForObject("select violado_em from relogio_sla where chamado_id = ? and tipo = 'RESPOSTA'",
                Instant.class, chamado)).isEqualTo(em("2026-11-17T09:00"));
    }

    // RF-33: se o recálculo vence um prazo na hora, o aviso de violação é gravado na outbox.
    @Test
    void shouldWriteViolationNoticeWhenRecalculatedDeadlineAlreadyPassed() throws Exception {
        relogio.definir(em("2026-11-16T12:00")); // ALTA: resposta venceria às 11:00

        alterar(GESTOR, "ALTA").andExpect(status().isOk())
                .andExpect(jsonPath("$.relogios[0].estado").value("VIOLADO"))
                .andExpect(jsonPath("$.comViolacao").value(true));

        UUID eventoId = ChamadoTestHelper.eventoId(new Violacao(chamado, TipoRelogio.RESPOSTA, 1, ABERTURA, em("2026-11-16T11:00")));
        Map<String, Object> evento = jdbc.queryForMap("select tipo, payload->>'relogio' relogio from outbox_eventos where evento_id = ?",
                eventoId);
        assertThat(evento).containsEntry("tipo", "VIOLACAO").containsEntry("relogio", "RESPOSTA");
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where payload->'chamado'->>'id' = ?", Long.class,
                chamado.toString())).isEqualTo(1);
    }

    // Mesma prioridade efetiva: nada muda, nem auditoria.
    @Test
    void shouldIgnoreSamePriority() throws Exception {
        long versao = jdbc.queryForObject("select version from chamado where id = ?", Long.class, chamado);

        alterar(GESTOR, "NORMAL").andExpect(status().isOk()).andExpect(jsonPath("$.prioridade").value("NORMAL"));
        alterar(GESTOR, "ALTA").andExpect(status().isOk());
        alterar(GESTOR, "ALTA").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select version from chamado where id = ?", Long.class, chamado)).isEqualTo(versao + 1);
        assertThat(auditorias()).hasSize(1);
    }

    // RN-121: a auditoria registra a anterior e a nova; o histórico só o gestor vê.
    @Test
    void shouldAuditPreviousAndNewPriority() throws Exception {
        alterar(GESTOR, "ALTA").andExpect(status().isOk());
        relogio.avancar(Duration.ofMinutes(1));
        alterar(GESTOR, "NORMAL").andExpect(status().isOk());

        assertThat(auditorias()).extracting(l -> l.get("anterior"), l -> l.get("nova"), l -> l.get("ator_sub"))
                .containsExactly(
                        tuple("NORMAL", "ALTA", UUID.fromString(SUB_GESTOR)),
                        tuple("ALTA", "NORMAL", UUID.fromString(SUB_GESTOR)));
        assertThat(jdbc.queryForList("""
                select visivel_cliente or visivel_tecnico from chamado_historico
                where chamado_id = ? and evento = 'ALTERACAO_PRIORIDADE'""", Boolean.class, chamado))
                .containsExactly(false, false);
    }

    @Test
    void shouldAllowOnlyManager() throws Exception {
        UUID empresa = jdbc.queryForObject("select cliente_id from chamado where id = ?", UUID.class, chamado);
        for (String quem : List.of(token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE"),
                token(ChamadoTeste.usuario(jdbc, "TECNICO", null), "TECNICO"))) {
            alterar(quem, "ALTA").andExpect(status().isForbidden());
        }
        assertThat(auditorias()).isEmpty();
        mvc.perform(put("/api/chamados/" + UUID.randomUUID() + "/prioridade").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prioridade\": \"ALTA\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/chamados/" + chamado + "/prioridade").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    private ResultActions alterar(String quem, String prioridade) throws Exception {
        return mvc.perform(put("/api/chamados/" + chamado + "/prioridade").header(HttpHeaders.AUTHORIZATION, quem)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prioridade\": \"" + prioridade + "\"}"));
    }

    private Instant venceEm(TipoRelogio tipo) {
        return jdbc.queryForObject("select vence_em from relogio_sla where chamado_id = ? and tipo = ? and ativo", Instant.class,
                chamado, tipo.name());
    }

    private List<Map<String, Object>> auditorias() {
        return jdbc.queryForList("""
                select dados->>'anterior' anterior, dados->>'nova' nova, ator_sub from auditoria
                where chamado_id = ? and operacao = 'ALTERACAO_PRIORIDADE' order by ocorrido_em, id""", chamado);
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
