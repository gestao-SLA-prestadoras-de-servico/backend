package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;
import com.gestaosla.backend.sla.api.RelogiosSla;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ConclusaoAtendimentoTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    // Segunda 09:00, NORMAL: a resolução vence dias úteis depois; a conclusão de terça está no prazo.
    private static final Instant ABERTURA = em("2026-11-16T09:00");
    private static final Instant CONCLUSAO = em("2026-11-17T10:00");

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

    private UUID tecnico;
    private UUID chamado;
    private UUID atendimento;

    @BeforeEach
    void preparar() {
        relogio.definir(CONCLUSAO);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "EM_ATENDIMENTO", tecnico);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, ABERTURA));
        atendimento = new ChamadoTestHelper(contexto).criarAtendimentoPendente(chamado, tecnico);
        jdbc.update("update atendimento set estado = 'EM_ANDAMENTO', iniciado_em = ? where id = ?",
                java.sql.Timestamp.from(ABERTURA.plusSeconds(3600)), atendimento);
    }

    // RF-27, RN-25, RN-73: com evidência ainda RECEBIDA e descrição, o atendimento conclui e o chamado é resolvido.
    @Test
    void shouldConcludeWithReceivedEvidenceResolvingTicketAndFulfillingResolutionClock() throws Exception {
        evidencia("RECEBIDA");
        descrever("Troca do capacitor");

        concluir(tecnico).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RESOLVIDO"))
                .andExpect(jsonPath("$.resolvidoEm").value(CONCLUSAO.toString()))
                .andExpect(jsonPath("$.relogios[2].tipo").value("RESOLUCAO"))
                .andExpect(jsonPath("$.relogios[2].estado").value("CUMPRIDO"))
                .andExpect(jsonPath("$.relogios[2].cumpridoEm").value(CONCLUSAO.toString()))
                .andExpect(jsonPath("$.atendimentos[0].estado").value("CONCLUIDO"))
                .andExpect(jsonPath("$.atendimentos[0].encerradoEm").value(CONCLUSAO.toString()));
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'MUDANCA_ESTADO' "
                + "and dados->>'para' = 'RESOLVIDO' and ator_sub = ?", Long.class, chamado, tecnico)).isEqualTo(1);
    }

    // RN-33, RF-52: o cliente recebe o e-mail RESOLUCAO no e-mail de contato.
    @Test
    void shouldSendResolutionEmailToClient() throws Exception {
        evidencia("DISPONIVEL");
        descrever("Troca do capacitor");

        concluir(tecnico).andExpect(status().isOk());

        assertThat(jdbc.queryForList("select payload->'destinatarios'->>0 from outbox_eventos where tipo = 'RESOLUCAO' "
                + "and payload->'chamado'->>'id' = ?", String.class, chamado.toString())).containsExactly("c@x.com");
    }

    // RN-36: só os gestores com a preferência ativa recebem CONCLUSAO.
    @Test
    void shouldNotifyOnlyManagersWithPreference() throws Exception {
        evidencia("RECEBIDA");
        descrever("Troca do capacitor");
        String outroGestor = ChamadoTeste.usuario(jdbc, "GESTOR", null);
        mvc.perform(put("/api/chamados/" + chamado + "/preferencia-notificacao")
                .header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))).andExpect(status().isNoContent());

        concluir(tecnico).andExpect(status().isOk());

        List<String> destinatarios = jdbc.queryForList("select jsonb_array_elements_text(payload->'destinatarios') from outbox_eventos "
                + "where tipo = 'CONCLUSAO' and payload->'chamado'->>'id' = ?", String.class, chamado.toString());
        assertThat(destinatarios).containsExactly(SUB_GESTOR).doesNotContain(outroGestor);
    }

    // RN-36: sem nenhum gestor com a preferência, nenhum evento CONCLUSAO.
    @Test
    void shouldNotWriteConclusionEventWithoutPreference() throws Exception {
        evidencia("RECEBIDA");
        descrever("Troca do capacitor");

        concluir(tecnico).andExpect(status().isOk());

        assertThat(eventos("CONCLUSAO")).isZero();
    }

    // RN-72, RN-118: sem evidência, 422 e nada muda.
    @Test
    void shouldRejectWithoutEvidence() throws Exception {
        descrever("Troca do capacitor");

        concluir(tecnico).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("EVIDENCIA_OBRIGATORIA"));

        assertNadaMudou();
    }

    // Evidência de outro atendimento (anterior, interrompido) não vale para o corrente.
    @Test
    void shouldIgnoreEvidenceOfAnotherAttendance() throws Exception {
        UUID anterior = UUID.randomUUID();
        jdbc.update("""
                insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version)
                values (?, now(), now(), ?, ?, 'INTERROMPIDO', 0)""", anterior, chamado, tecnico);
        evidencia("DISPONIVEL", anterior);
        descrever("Troca do capacitor");

        concluir(tecnico).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("EVIDENCIA_OBRIGATORIA"));
    }

    // Decisão da [chamado - 11]: a descrição técnica também é obrigatória.
    @Test
    void shouldRejectWithoutTechnicalDescription() throws Exception {
        evidencia("RECEBIDA");

        concluir(tecnico).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("DESCRICAO_TECNICA_OBRIGATORIA"));

        assertNadaMudou();
    }

    // RN-126: outro técnico não enxerga o chamado.
    @Test
    void shouldReturn404ToAnotherTechnician() throws Exception {
        evidencia("RECEBIDA");
        descrever("Troca do capacitor");

        concluir(UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null))).andExpect(status().isNotFound());

        assertNadaMudou();
    }

    // Só o técnico conclui.
    @Test
    void shouldForbidManager() throws Exception {
        mvc.perform(post(rota()).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))).andExpect(status().isForbidden());
    }

    // Atendimento ainda PENDENTE (chamado ATRIBUIDO): 422.
    @Test
    void shouldRejectWhenAttendanceIsNotInProgress() throws Exception {
        jdbc.update("update atendimento set estado = 'PENDENTE' where id = ?", atendimento);
        jdbc.update("update chamado set estado = 'ATRIBUIDO' where id = ?", chamado);

        concluir(tecnico).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
    }

    private void assertNadaMudou() {
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("EM_ATENDIMENTO");
        assertThat(new ChamadoTestHelper(contexto).estadosDosAtendimentos(chamado)).containsExactly(EstadoAtendimento.EM_ANDAMENTO);
        assertThat(eventos("RESOLUCAO")).isZero();
    }

    private void descrever(String texto) {
        jdbc.update("update atendimento set descricao_tecnica = ? where id = ?", texto, atendimento);
    }

    private void evidencia(String estado) {
        evidencia(estado, atendimento);
    }

    private void evidencia(String estado, UUID doAtendimento) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into evidencia (id, created_at, updated_at, atendimento_id, chamado_id, tipo_conteudo, tamanho, hash_sha256,
                                       chave_original, estado, enviada_em, version)
                values (?, now(), now(), ?, ?, 'image/jpeg', 10, ?, ?, ?, now(), 0)""",
                id, doAtendimento, chamado, "0".repeat(64), "chamados/" + chamado + "/" + id, estado);
    }

    private long eventos(String tipo) {
        return jdbc.queryForObject("select count(*) from outbox_eventos where tipo = ? and payload->'chamado'->>'id' = ?",
                Long.class, tipo, chamado.toString());
    }

    private ResultActions concluir(UUID tecnicoSub) throws Exception {
        return mvc.perform(post(rota()).header(HttpHeaders.AUTHORIZATION, token(tecnicoSub.toString(), "TECNICO")));
    }

    private String rota() {
        return "/api/chamados/" + chamado + "/atendimento/conclusao";
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
