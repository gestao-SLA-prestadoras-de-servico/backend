package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class CancelamentoChamadoTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");
    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private ApplicationContext contexto;

    private UUID empresa;
    private UUID tecnico;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        empresa = ChamadoTeste.cliente(jdbc);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
    }

    // RF-18, RN-19, RN-100: permitido antes da resolução; grava motivo e estado_final_em; audita o motivo.
    @ParameterizedTest
    @ValueSource(strings = {"ABERTO", "ATRIBUIDO", "EM_ATENDIMENTO"})
    void shouldCancelBeforeResolution(String estado) throws Exception {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, estado, estado.equals("ABERTO") ? null : tecnico);

        cancelar(chamado, "Cliente desistiu do serviço").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADO"))
                .andExpect(jsonPath("$.estadoFinalEm").value(AGORA.toString()));

        assertThat(jdbc.queryForObject("select motivo_cancelamento from chamado where id = ?", String.class, chamado))
                .isEqualTo("Cliente desistiu do serviço");
        assertThat(jdbc.queryForObject("""
                select dados->>'motivo' from auditoria where chamado_id = ? and operacao = 'CANCELAMENTO'""", String.class, chamado))
                .isEqualTo("Cliente desistiu do serviço");
        assertThat(jdbc.queryForObject("""
                select count(*) from auditoria where chamado_id = ? and operacao = 'MUDANCA_ESTADO' and dados->>'para' = 'CANCELADO'""",
                Long.class, chamado)).isEqualTo(1);
        // Decisão da task: o técnico não é avisado do cancelamento.
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where payload->'chamado'->>'id' = ?", Long.class,
                chamado.toString())).isZero();
    }

    // O atendimento em andamento vira INTERROMPIDO, sem ser apagado.
    @Test
    void shouldInterruptCurrentAttendance() throws Exception {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, "EM_ATENDIMENTO", tecnico);
        ChamadoTestHelper helper = new ChamadoTestHelper(contexto);
        UUID atendimento = helper.criarAtendimentoPendente(chamado, tecnico);
        jdbc.update("update atendimento set estado = 'EM_ANDAMENTO' where id = ?", atendimento);

        cancelar(chamado, "Duplicado").andExpect(status().isOk());

        assertThat(helper.estadosDosAtendimentos(chamado)).containsExactly(EstadoAtendimento.INTERROMPIDO);
    }

    // RN-19: em RESOLVIDO ou depois, não cancela.
    @ParameterizedTest
    @ValueSource(strings = {"RESOLVIDO", "ENCERRADO", "CANCELADO"})
    void shouldRejectCancellationFromResolvedOn(String estado) throws Exception {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, estado, tecnico);

        cancelar(chamado, "Tarde demais").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("CANCELAMENTO_NAO_PERMITIDO"));
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo(estado);
    }

    // RN-19: chamado cancelado não tem apuração de SLA.
    @Test
    void shouldHaveNoSlaAssessment() throws Exception {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, "ABERTO", null);

        cancelar(chamado, "Aberto por engano").andExpect(status().isOk());

        mvc.perform(get("/api/chamados/" + chamado + "/sla").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.apurado").value(false));
    }

    @Test
    void shouldRequireReasonAndManager() throws Exception {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, "ABERTO", null);

        cancelar(chamado, " ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACAO"));
        for (String quem : List.of(token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE"), token(tecnico.toString(), "TECNICO"))) {
            mvc.perform(post("/api/chamados/" + chamado + "/cancelamento").header(HttpHeaders.AUTHORIZATION, quem)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"m\"}")).andExpect(status().isForbidden());
        }
        cancelar(UUID.randomUUID(), "m").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("ABERTO");
    }

    private ResultActions cancelar(UUID chamado, String motivo) throws Exception {
        return mvc.perform(post("/api/chamados/" + chamado + "/cancelamento").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"" + motivo + "\"}"));
    }
}
