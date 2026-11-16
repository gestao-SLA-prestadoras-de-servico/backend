package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
class AtribuicaoChamadoTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");
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

    @Autowired
    private ApplicationContext contexto;

    private ChamadoTestHelper helper;
    private UUID chamado;
    private UUID tecnicoA;
    private UUID tecnicoB;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        helper = new ChamadoTestHelper(contexto);
        chamado = ChamadoTeste.chamado(jdbc);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, AGORA.minusSeconds(3_600)));
        tecnicoA = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        tecnicoB = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
    }

    // ABERTO → ATRIBUIDO; o técnico recebe ATRIBUICAO; auditoria e histórico de atribuição; RN-26: nenhum relógio muda.
    @Test
    void shouldAssignOpenChamado() throws Exception {
        List<Map<String, Object>> relogiosAntes = relogiosDoChamado();

        atribuir(tecnicoA, version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ATRIBUIDO"))
                .andExpect(jsonPath("$.tecnicoSub").value(tecnicoA.toString()));

        assertThat(relogiosDoChamado()).isEqualTo(relogiosAntes);
        assertThat(avisos("ATRIBUICAO")).containsExactly(tecnicoA.toString());
        assertThat(avisos("REMOCAO")).isEmpty();
        assertThat(operacoes()).containsExactlyInAnyOrder("MUDANCA_ESTADO", "ATRIBUICAO");
        assertThat(eventos()).containsExactlyInAnyOrder("MUDANCA_ESTADO", "ATRIBUICAO");
        assertThat(helper.estadosDosAtendimentos(chamado)).isEmpty();
    }

    // Em ATRIBUIDO só troca o técnico; o anterior recebe REMOCAO (RN-37) e o novo, ATRIBUICAO (RN-35).
    @Test
    void shouldReassignKeepingStateAndNotifyBothTechnicians() throws Exception {
        atribuir(tecnicoA, version()).andExpect(status().isOk());
        List<Map<String, Object>> relogiosAntes = relogiosDoChamado();

        atribuir(tecnicoB, version()).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ATRIBUIDO"))
                .andExpect(jsonPath("$.tecnicoSub").value(tecnicoB.toString()));

        assertThat(relogiosDoChamado()).isEqualTo(relogiosAntes);
        assertThat(avisos("ATRIBUICAO")).containsExactlyInAnyOrder(tecnicoA.toString(), tecnicoB.toString());
        assertThat(avisos("REMOCAO")).containsExactly(tecnicoA.toString());
        Map<String, Object> reatribuicao = jdbc.queryForMap("""
                select dados->>'anterior' anterior, dados->>'novo' novo from auditoria
                where chamado_id = ? and operacao = 'REATRIBUICAO'""", chamado);
        assertThat(reatribuicao).containsEntry("anterior", tecnicoA.toString()).containsEntry("novo", tecnicoB.toString());
    }

    // RN-21: em EM_ATENDIMENTO, volta para ATRIBUIDO e o atendimento é interrompido, sem apagar nada.
    @Test
    void shouldReturnToAssignedAndInterruptAttendance() throws Exception {
        atribuir(tecnicoA, version()).andExpect(status().isOk());
        UUID atendimento = helper.criarAtendimentoPendente(chamado, tecnicoA);
        jdbc.update("update atendimento set estado = 'EM_ANDAMENTO' where id = ?", atendimento);
        jdbc.update("update chamado set estado = 'EM_ATENDIMENTO', version = version + 1 where id = ?", chamado);
        List<Map<String, Object>> relogiosAntes = relogiosDoChamado();

        atribuir(tecnicoB, version()).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ATRIBUIDO"));

        assertThat(helper.estadosDosAtendimentos(chamado)).containsExactly(EstadoAtendimento.INTERROMPIDO);
        assertThat(jdbc.queryForObject("select encerrado_em from atendimento where id = ?", Instant.class, atendimento)).isEqualTo(AGORA);
        assertThat(relogiosDoChamado()).isEqualTo(relogiosAntes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVIDO", "ENCERRADO", "CANCELADO"})
    void shouldRejectAssignmentFromResolvedOn(String estado) throws Exception {
        jdbc.update("update chamado set estado = ? where id = ?", estado, chamado);

        atribuir(tecnicoA, version()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
        assertThat(avisos("ATRIBUICAO")).isEmpty();
    }

    // Técnico inativo, sem papel de técnico ou igual ao atual: 422.
    @Test
    void shouldRejectInvalidTechnician() throws Exception {
        jdbc.update("update usuario set ativo = false where sub = ?", tecnicoB);
        atribuir(tecnicoB, version()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REATRIBUICAO_NAO_PERMITIDA"));
        atribuir(UUID.fromString(SUB_GESTOR), version()).andExpect(status().isUnprocessableContent());
        atribuir(UUID.randomUUID(), version()).andExpect(status().isUnprocessableContent());

        atribuir(tecnicoA, version()).andExpect(status().isOk());
        atribuir(tecnicoA, version()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("REATRIBUICAO_NAO_PERMITIDA"));
    }

    // RN-117: versão lida desatualizada responde 409 sem gravar.
    @Test
    void shouldRejectStaleVersion() throws Exception {
        long lida = version();
        atribuir(tecnicoA, lida).andExpect(status().isOk());

        atribuir(tecnicoB, lida).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLITO_VERSAO"));
        assertThat(jdbc.queryForObject("select tecnico_sub from chamado where id = ?", UUID.class, chamado)).isEqualTo(tecnicoA);
    }

    // RN-117: duas atribuições ao mesmo tempo, com a mesma versão lida: uma vence, a outra recebe 409.
    @Test
    void shouldRejectSecondOfConcurrentAssignments() throws Exception {
        long lida = version();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<Integer> statusRecebidos = new ArrayList<>();
        try {
            List<Callable<Integer>> chamadas = List.of(
                    () -> atribuir(tecnicoA, lida).andReturn().getResponse().getStatus(),
                    () -> atribuir(tecnicoB, lida).andReturn().getResponse().getStatus());
            for (Future<Integer> f : threads.invokeAll(chamadas)) {
                statusRecebidos.add(f.get());
            }
        } finally {
            threads.shutdown();
        }

        assertThat(statusRecebidos).containsExactlyInAnyOrder(200, 409);
        assertThat(avisos("ATRIBUICAO")).hasSize(1);
    }

    @Test
    void shouldAllowOnlyManager() throws Exception {
        UUID empresa = jdbc.queryForObject("select cliente_id from chamado where id = ?", UUID.class, chamado);
        for (String quem : List.of(token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE"), token(tecnicoA.toString(), "TECNICO"))) {
            mvc.perform(post("/api/chamados/" + chamado + "/atribuicao").header(HttpHeaders.AUTHORIZATION, quem)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"tecnicoSub\": \"" + tecnicoA + "\", \"version\": 0}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/chamados/" + chamado + "/atribuicao").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tecnicoSub\": \"" + tecnicoA + "\"}")).andExpect(status().isBadRequest());
        assertThat(avisos("ATRIBUICAO")).isEmpty();
    }

    private ResultActions atribuir(UUID tecnico, long version) throws Exception {
        return mvc.perform(post("/api/chamados/" + chamado + "/atribuicao").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tecnicoSub\": \"" + tecnico + "\", \"version\": " + version + "}"));
    }

    private long version() {
        return jdbc.queryForObject("select version from chamado where id = ?", Long.class, chamado);
    }

    private List<Map<String, Object>> relogiosDoChamado() {
        return jdbc.queryForList("select tipo, ciclo, ativo, prazo_minutos, vence_em, cumprido_em, violado_em, version "
                + "from relogio_sla where chamado_id = ? order by tipo, ciclo", chamado);
    }

    private List<String> avisos(String tipo) {
        return jdbc.queryForList("""
                select payload->'destinatarios'->>0 from outbox_eventos
                where tipo = ? and payload->'chamado'->>'id' = ? order by criado_em, id""", String.class, tipo, chamado.toString());
    }

    private List<String> operacoes() {
        return jdbc.queryForList("select operacao from auditoria where chamado_id = ? order by ocorrido_em, id", String.class, chamado);
    }

    private List<String> eventos() {
        return jdbc.queryForList("select evento from chamado_historico where chamado_id = ? order by ocorrido_em, id", String.class, chamado);
    }
}
