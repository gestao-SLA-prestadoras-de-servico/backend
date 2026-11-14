package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import com.gestaosla.backend.sla.api.ApuracaoSla;
import com.gestaosla.backend.sla.api.RelogiosSla;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ConsultaChamadoTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");
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
    private ApuracaoSla apuracoes;

    @Autowired
    private ContratoConsulta contratos;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private ApplicationContext contexto;

    // Empresa com três chamados: aberto (NORMAL padrão), atribuído ao técnico com ALTA definida, em atendimento violado.
    private UUID empresa;
    private String tecnicoSub;
    private UUID aberto;
    private UUID atribuido;
    private UUID violado;
    private String cliente;
    private String tecnico;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        empresa = ChamadoTeste.cliente(jdbc);
        tecnicoSub = ChamadoTeste.usuario(jdbc, "TECNICO", null);
        aberto = ChamadoTeste.chamado(jdbc, empresa, "ABERTO", null);
        atribuido = ChamadoTeste.chamado(jdbc, empresa, "ATRIBUIDO", UUID.fromString(tecnicoSub));
        jdbc.update("update chamado set prioridade_definida = 'ALTA' where id = ?", atribuido);
        violado = ChamadoTeste.chamado(jdbc, empresa, "EM_ATENDIMENTO", null);
        relogioSla(aberto, "RESOLUCAO", AGORA.plusSeconds(7_200), false);
        relogioSla(atribuido, "RESOLUCAO", AGORA.plusSeconds(3_600), false);
        relogioSla(violado, "RESPOSTA", AGORA.minusSeconds(3_600), true);
        cliente = token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE");
        tecnico = token(tecnicoSub, "TECNICO");
    }

    // RF-14: o gestor filtra por estado, cliente, técnico, prioridade (efetiva) e "com violação".
    @Test
    void shouldFilterListingForManager() throws Exception {
        listar(GESTOR, "clienteId=" + empresa).andExpect(jsonPath("$.page.totalElements").value(3));
        listar(GESTOR, "clienteId=" + empresa + "&estado=ATRIBUIDO").andExpect(ids(atribuido));
        listar(GESTOR, "clienteId=" + empresa + "&prioridade=ALTA").andExpect(ids(atribuido));
        listar(GESTOR, "clienteId=" + empresa + "&prioridade=NORMAL").andExpect(ids(aberto, violado));
        listar(GESTOR, "tecnicoSub=" + tecnicoSub).andExpect(ids(atribuido));
        listar(GESTOR, "clienteId=" + empresa + "&comViolacao=true").andExpect(ids(violado));
        listar(GESTOR, "clienteId=" + empresa + "&comViolacao=false").andExpect(ids(aberto, atribuido));
    }

    // Ordenação por número (padrão, mais novo primeiro) e pelo prazo correndo mais próximo; sem prazo correndo, por último.
    @Test
    void shouldSortByNumberAndByNearestDeadline() throws Exception {
        listar(GESTOR, "clienteId=" + empresa)
                .andExpect(jsonPath("$.content[0].id").value(violado.toString()))
                .andExpect(jsonPath("$.content[2].id").value(aberto.toString()));
        listar(GESTOR, "clienteId=" + empresa + "&sort=proximoVencimento,asc")
                .andExpect(jsonPath("$.content[0].id").value(atribuido.toString()))
                .andExpect(jsonPath("$.content[0].proximoVencimento").value(AGORA.plusSeconds(3_600).toString()))
                .andExpect(jsonPath("$.content[1].id").value(aberto.toString()))
                .andExpect(jsonPath("$.content[2].id").value(violado.toString()));
    }

    // RN-126: o cliente só vê os da própria empresa, mesmo pedindo outro cliente na URL; o de outra empresa é 404.
    @Test
    void shouldIsolateClientsFromEachOther() throws Exception {
        UUID outraEmpresa = ChamadoTeste.cliente(jdbc);
        UUID deOutra = ChamadoTeste.chamado(jdbc, outraEmpresa, "ABERTO", null);

        listar(cliente, "clienteId=" + outraEmpresa).andExpect(ids(aberto, atribuido, violado));
        for (String rota : List.of("", "/historico", "/sla")) {
            mvc.perform(get("/api/chamados/" + deOutra + rota).header(HttpHeaders.AUTHORIZATION, cliente))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("RECURSO_NAO_ENCONTRADO"));
        }
        detalhar(cliente, aberto).andExpect(status().isOk()).andExpect(jsonPath("$.tecnicoSub").doesNotExist());
    }

    // RF-04, RF-19: o técnico vê só os atribuídos a ele agora; removido por reatribuição, perde o acesso na hora.
    @Test
    void shouldShowTechnicianOnlyCurrentlyAssignedChamados() throws Exception {
        listar(tecnico, "").andExpect(ids(atribuido));
        detalhar(tecnico, atribuido).andExpect(status().isOk());
        detalhar(tecnico, aberto).andExpect(status().isNotFound());

        jdbc.update("update chamado set tecnico_sub = ? where id = ?", UUID.randomUUID(), atribuido);

        detalhar(tecnico, atribuido).andExpect(status().isNotFound());
        listar(tecnico, "").andExpect(jsonPath("$.page.totalElements").value(0));
    }

    // RF-04: a resposta para o técnico não tem nenhum campo de contrato ou política.
    @Test
    void shouldNotExposeContractOrPolicyToTechnician() throws Exception {
        for (ResultActions resposta : List.of(detalhar(tecnico, atribuido), listar(tecnico, ""))) {
            String json = resposta.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json).doesNotContainIgnoringCase("contrato").doesNotContainIgnoringCase("politica")
                    .doesNotContain("prioridadePadrao");
        }
        detalhar(GESTOR, atribuido).andExpect(jsonPath("$.contratoId").exists()).andExpect(jsonPath("$.politicaVersaoId").exists());
    }

    // GET /chamados/{id}: os três relógios, os atendimentos e a contagem de evidências (0 sem o módulo evidencia).
    @Test
    void shouldDetailClocksAttendancesAndEvidenceSummary() throws Exception {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, "ATRIBUIDO", UUID.fromString(tecnicoSub));
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, AGORA));
        UUID atendimento = new ChamadoTestHelper(contexto).criarAtendimentoPendente(chamado, UUID.fromString(tecnicoSub));

        for (String quem : List.of(GESTOR, cliente, tecnico)) {
            detalhar(quem, chamado).andExpect(status().isOk())
                    .andExpect(jsonPath("$.relogios.length()").value(3))
                    .andExpect(jsonPath("$.relogios[*].tipo", containsInAnyOrder("RESPOSTA", "CAMPO", "RESOLUCAO")))
                    .andExpect(jsonPath("$.relogios[0].estado").value("CORRENDO"))
                    .andExpect(jsonPath("$.atendimentos.length()").value(1))
                    .andExpect(jsonPath("$.atendimentos[0].id").value(atendimento.toString()))
                    .andExpect(jsonPath("$.atendimentos[0].estado").value("PENDENTE"))
                    .andExpect(jsonPath("$.atendimentos[0].evidencias").value(0));
        }
        listar(GESTOR, "clienteId=" + empresa).andExpect(jsonPath("$.content[0].relogios").doesNotExist());
    }

    // O histórico é filtrado pelo perfil: a alteração de prioridade é só do gestor.
    @Test
    void shouldFilterHistoryByProfile() throws Exception {
        evento(atribuido, "ABERTURA", true, true);
        evento(atribuido, "MUDANCA_ESTADO", true, true);
        evento(atribuido, "ALTERACAO_PRIORIDADE", false, false);

        historico(GESTOR, atribuido).andExpect(jsonPath("$[*].evento", containsInAnyOrder("ABERTURA", "MUDANCA_ESTADO",
                "ALTERACAO_PRIORIDADE")));
        historico(cliente, atribuido).andExpect(jsonPath("$[*].evento", containsInAnyOrder("ABERTURA", "MUDANCA_ESTADO")));
        historico(tecnico, atribuido).andExpect(jsonPath("$[*].evento", containsInAnyOrder("ABERTURA", "MUDANCA_ESTADO")));
    }

    @Test
    void shouldCountByStateWithinUserScope() throws Exception {
        mvc.perform(get("/api/chamados/contagem").header(HttpHeaders.AUTHORIZATION, cliente))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ABERTO").value(1))
                .andExpect(jsonPath("$.ATRIBUIDO").value(1))
                .andExpect(jsonPath("$.EM_ATENDIMENTO").value(1))
                .andExpect(jsonPath("$.RESOLVIDO").value(0))
                .andExpect(jsonPath("$.ENCERRADO").value(0))
                .andExpect(jsonPath("$.CANCELADO").value(0));
        mvc.perform(get("/api/chamados/contagem").header(HttpHeaders.AUTHORIZATION, tecnico))
                .andExpect(jsonPath("$.ATRIBUIDO").value(1))
                .andExpect(jsonPath("$.ABERTO").value(0));
    }

    // RF-37: a apuração para quem tem acesso ao chamado; sem apuração, apurado false; sem acesso, 404.
    @Test
    void shouldShowSlaResultToWhoeverCanSeeChamado() throws Exception {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, "RESOLVIDO", UUID.fromString(tecnicoSub));
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, AGORA));

        for (String quem : List.of(GESTOR, cliente, tecnico)) {
            sla(quem, chamado).andExpect(status().isOk()).andExpect(jsonPath("$.apurado").value(false));
        }

        relogio.definir(AGORA.plusSeconds(60));
        transacao.executeWithoutResult(s -> apuracoes.congelarApuracao(chamado, empresa, Prioridade.NORMAL,
                UUID.fromString(tecnicoSub)));

        for (String quem : List.of(GESTOR, cliente, tecnico)) {
            sla(quem, chamado).andExpect(status().isOk())
                    .andExpect(jsonPath("$.apurado").value(true))
                    .andExpect(jsonPath("$.prioridade").value("NORMAL"))
                    .andExpect(jsonPath("$.relogios.length()").value(3));
        }
        String outroTecnico = token(ChamadoTeste.usuario(jdbc, "TECNICO", null), "TECNICO");
        String outroCliente = token(ChamadoTeste.usuario(jdbc, "CLIENTE", ChamadoTeste.cliente(jdbc)), "CLIENTE");
        for (String quem : List.of(outroTecnico, outroCliente)) {
            sla(quem, chamado).andExpect(status().isNotFound());
        }
    }

    private ResultActions listar(String quem, String filtros) throws Exception {
        return mvc.perform(get("/api/chamados?" + filtros).header(HttpHeaders.AUTHORIZATION, quem)).andExpect(status().isOk());
    }

    private ResultActions detalhar(String quem, UUID chamado) throws Exception {
        return mvc.perform(get("/api/chamados/" + chamado).header(HttpHeaders.AUTHORIZATION, quem));
    }

    private ResultActions historico(String quem, UUID chamado) throws Exception {
        return mvc.perform(get("/api/chamados/" + chamado + "/historico").header(HttpHeaders.AUTHORIZATION, quem))
                .andExpect(status().isOk());
    }

    private ResultActions sla(String quem, UUID chamado) throws Exception {
        return mvc.perform(get("/api/chamados/" + chamado + "/sla").header(HttpHeaders.AUTHORIZATION, quem));
    }

    private static org.springframework.test.web.servlet.ResultMatcher ids(UUID... chamados) {
        return jsonPath("$.content[*].id", containsInAnyOrder(java.util.Arrays.stream(chamados).map(UUID::toString).toArray()));
    }

    private void relogioSla(UUID chamado, String tipo, Instant venceEm, boolean violado) {
        jdbc.update("""
                insert into relogio_sla (id, created_at, updated_at, chamado_id, tipo, ciclo, ativo, marco_inicial,
                                         prazo_minutos, vence_em, violado_em, version)
                values (?, now(), now(), ?, ?, 1, true, ?, 60, ?, ?, 0)""",
                UUID.randomUUID(), chamado, tipo, Timestamp.from(AGORA.minusSeconds(86_400)), Timestamp.from(venceEm),
                violado ? Timestamp.from(venceEm) : null);
    }

    private void evento(UUID chamado, String evento, boolean cliente, boolean tecnico) {
        jdbc.update("""
                insert into chamado_historico (id, chamado_id, evento, ator_sub, ator_papel, ocorrido_em, visivel_cliente, visivel_tecnico)
                values (?, ?, ?, ?, 'GESTOR', now(), ?, ?)""", UUID.randomUUID(), chamado, evento, UUID.fromString(SUB_GESTOR),
                cliente, tecnico);
    }
}
