package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.sla.api.RelogiosSla;

/** A porta {@link ChamadosDoTecnico} implementada pelo chamado: carga do técnico e transferência por inativação. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class TransferenciaTecnicoTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");
    private static final Ator GESTOR = Ator.usuario(UUID.fromString(SUB_GESTOR), Papel.GESTOR);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private ChamadosDoTecnico porta;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private RelogiosSla relogios;

    @Autowired
    private ContratoConsulta contratos;

    // A inativação pela API desabilita a conta no Keycloak; aqui só importa a parte local.
    @MockitoBean
    private KeycloakAdminClient keycloak;

    private UUID tecnico;
    private UUID substituto;
    private UUID empresa;

    @BeforeEach
    void preparar() {
        relogio.definir(AGORA);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        substituto = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        empresa = ChamadoTeste.cliente(jdbc);
    }

    // [autenticação - 05]: a carga de GET /tecnicos conta ATRIBUIDO, EM_ATENDIMENTO e RESOLVIDO.
    @Test
    void shouldShowRealLoadInTechnicianListing() throws Exception {
        for (String estado : List.of("ATRIBUIDO", "EM_ATENDIMENTO", "RESOLVIDO", "ENCERRADO", "CANCELADO")) {
            ChamadoTeste.chamado(jdbc, empresa, estado, tecnico);
        }

        assertThat(porta.contarNaoFinalizados(tecnico)).isEqualTo(3);
        mvc.perform(get("/api/tecnicos?size=100").header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.sub == '" + tecnico + "')].chamadosNaoFinalizados", contains(3)))
                .andExpect(jsonPath("$.content[?(@.sub == '" + substituto + "')].chamadosNaoFinalizados", contains(0)));
    }

    // RN-134, RN-135: cada estado segue a sua regra; o substituto é avisado, o inativado não recebe remoção.
    @Test
    void shouldTransferEachStateAccordingToRn134() {
        UUID atribuido = ChamadoTeste.chamado(jdbc, empresa, "ATRIBUIDO", tecnico);
        UUID emAtendimento = ChamadoTeste.chamado(jdbc, empresa, "EM_ATENDIMENTO", tecnico);
        UUID resolvido = ChamadoTeste.chamado(jdbc, empresa, "RESOLVIDO", tecnico);
        UUID encerrado = ChamadoTeste.chamado(jdbc, empresa, "ENCERRADO", tecnico);
        ChamadoTestHelper helper = new ChamadoTestHelper(contexto);
        UUID atendimento = helper.criarAtendimentoPendente(emAtendimento, tecnico);
        jdbc.update("update atendimento set estado = 'EM_ANDAMENTO' where id = ?", atendimento);

        transacao.executeWithoutResult(s -> porta.transferir(tecnico, substituto, GESTOR));

        assertThat(estadoETecnico(atribuido)).containsExactly("ATRIBUIDO", substituto.toString());
        assertThat(estadoETecnico(emAtendimento)).containsExactly("ATRIBUIDO", substituto.toString());
        assertThat(estadoETecnico(resolvido)).containsExactly("RESOLVIDO", substituto.toString());
        assertThat(estadoETecnico(encerrado)).containsExactly("ENCERRADO", tecnico.toString());
        assertThat(helper.estadosDosAtendimentos(emAtendimento)).containsExactly(EstadoAtendimento.INTERROMPIDO);
        for (UUID chamado : List.of(atribuido, emAtendimento, resolvido)) {
            assertThat(jdbc.queryForObject("""
                    select count(*) from auditoria where chamado_id = ? and operacao = 'TRANSFERENCIA_POR_INATIVACAO'
                    and dados->>'anterior' = ? and dados->>'novo' = ?""", Long.class, chamado, tecnico.toString(), substituto.toString()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForList("select payload->'destinatarios'->>0 from outbox_eventos where tipo = 'ATRIBUICAO' "
                    + "and payload->'chamado'->>'id' = ?", String.class, chamado.toString())).containsExactly(substituto.toString());
        }
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'REMOCAO' and payload->'destinatarios'->>0 = ?",
                Long.class, tecnico.toString())).isZero();
        assertThat(porta.contarNaoFinalizados(tecnico)).isZero();
        assertThat(porta.contarNaoFinalizados(substituto)).isEqualTo(3);
    }

    // [chamado - 13]: o modal do gestor mostra quantos chamados passam ao substituto, por estado.
    @Test
    void shouldCountTicketsToTransferByState() throws Exception {
        for (String estado : List.of("ATRIBUIDO", "EM_ATENDIMENTO", "EM_ATENDIMENTO", "ENCERRADO", "CANCELADO")) {
            ChamadoTeste.chamado(jdbc, empresa, estado, tecnico);
        }

        aTransferir(idDe(tecnico)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ATRIBUIDO").value(1))
                .andExpect(jsonPath("$.EM_ATENDIMENTO").value(2))
                .andExpect(jsonPath("$.RESOLVIDO").value(0))
                .andExpect(jsonPath("$.ENCERRADO").doesNotExist());
        aTransferir(UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get("/api/usuarios/" + idDe(tecnico) + "/chamados-a-transferir")
                .header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO"))).andExpect(status().isForbidden());
    }

    // RN-133 a RN-135 pela API: inativar o técnico com um chamado em cada estado transfere os três, sem tocar nos relógios.
    @Test
    void shouldTransferAllTicketsWhenTechnicianIsInactivatedWithoutChangingClocks() throws Exception {
        List<UUID> chamados = List.of("ATRIBUIDO", "EM_ATENDIMENTO", "RESOLVIDO").stream()
                .map(estado -> ChamadoTeste.chamado(jdbc, empresa, estado, tecnico)).toList();
        chamados.forEach(chamado -> transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, AGORA.minusSeconds(86_400))));
        List<Map<String, Object>> relogiosAntes = relogiosDe(chamados);

        mvc.perform(post("/api/usuarios/" + idDe(tecnico) + "/inativacao").header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"substitutoId\": \"" + idDe(substituto) + "\"}"))
                .andExpect(status().isNoContent());

        verify(keycloak).desabilitar(tecnico);
        assertThat(chamados).allSatisfy(c -> assertThat(estadoETecnico(c).get(1)).isEqualTo(substituto.toString()));
        assertThat(chamados).extracting(c -> estadoETecnico(c).get(0)).containsExactly("ATRIBUIDO", "ATRIBUIDO", "RESOLVIDO");
        assertThat(relogiosDe(chamados)).isEqualTo(relogiosAntes);
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'ATRIBUICAO' "
                + "and payload->'destinatarios'->>0 = ?", Long.class, substituto.toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'REMOCAO' and payload->'destinatarios'->>0 = ?",
                Long.class, tecnico.toString())).isZero();
        aTransferir(idDe(tecnico)).andExpect(jsonPath("$.ATRIBUIDO").value(0)).andExpect(jsonPath("$.RESOLVIDO").value(0));
    }

    private ResultActions aTransferir(UUID usuarioId) throws Exception {
        return mvc.perform(get("/api/usuarios/" + usuarioId + "/chamados-a-transferir")
                .header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")));
    }

    private UUID idDe(UUID sub) {
        return jdbc.queryForObject("select id from usuario where sub = ?", UUID.class, sub);
    }

    private List<Map<String, Object>> relogiosDe(List<UUID> chamados) {
        return jdbc.queryForList("""
                select chamado_id, tipo, ciclo, ativo, marco_inicial, prazo_minutos, vence_em, cumprido_em, violado_em, version
                from relogio_sla where chamado_id = any(?) order by chamado_id, tipo, ciclo""", (Object) chamados.toArray(UUID[]::new));
    }

    private List<String> estadoETecnico(UUID chamado) {
        return jdbc.queryForObject("select estado, tecnico_sub::text from chamado where id = ?",
                (rs, i) -> List.of(rs.getString(1), rs.getString(2)), chamado);
    }
}
