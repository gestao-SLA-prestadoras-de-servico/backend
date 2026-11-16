package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class PosicionamentoChamadoTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    // Segunda 09:00, NORMAL: a resposta vence terça 09:00.
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
        relogio.definir(em("2026-11-16T10:00"));
        chamado = ChamadoTeste.chamado(jdbc);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, ABERTURA));
    }

    // RF-16, RN-23, RN-33, RN-53: grava o texto, cumpre a resposta e grava o e-mail ao cliente com o texto.
    @Test
    void shouldRegisterFirstPositionFulfillingResponseAndWritingEmail() throws Exception {
        posicionar(GESTOR, "Recebemos o chamado e enviaremos um técnico amanhã.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posicionamento").value("Recebemos o chamado e enviaremos um técnico amanhã."))
                .andExpect(jsonPath("$.posicionadoEm").value(em("2026-11-16T10:00").toString()))
                .andExpect(jsonPath("$.relogios[0].tipo").value("RESPOSTA"))
                .andExpect(jsonPath("$.relogios[0].estado").value("CUMPRIDO"))
                .andExpect(jsonPath("$.relogios[0].cumpridoEm").value(em("2026-11-16T10:00").toString()));

        Map<String, Object> email = jdbc.queryForMap("""
                select routing_key, payload->>'textoPosicionamento' texto, payload->'destinatarios'->>0 para, payload->'chamado'->>'numero' numero
                from outbox_eventos where tipo = 'POSICIONAMENTO' and payload->'chamado'->>'id' = ?""", chamado.toString());
        assertThat(email).containsEntry("routing_key", "email").containsEntry("para", "c@x.com")
                .containsEntry("texto", "Recebemos o chamado e enviaremos um técnico amanhã.");
        assertThat(email.get("numero")).isNotNull();
        assertThat(jdbc.queryForList("select evento from chamado_historico where chamado_id = ?", String.class, chamado))
                .containsExactly("POSICIONAMENTO");
        assertThat(jdbc.queryForObject("select dados::text from auditoria where chamado_id = ? and operacao = 'PRIMEIRO_POSICIONAMENTO'",
                String.class, chamado)).isEqualTo("{}");
    }

    // Só uma vez: a segunda responde 422 e não grava outro e-mail.
    @Test
    void shouldRejectSecondPosition() throws Exception {
        posicionar(GESTOR, "Primeiro").andExpect(status().isOk());

        posicionar(GESTOR, "Segundo").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("POSICIONAMENTO_JA_REGISTRADO"));
        assertThat(jdbc.queryForObject("select posicionamento from chamado where id = ?", String.class, chamado)).isEqualTo("Primeiro");
        assertThat(emails()).isEqualTo(1);
    }

    // Depois do prazo, a resposta fica violada e o aviso de violação é gravado.
    @Test
    void shouldViolateResponseWhenPositionIsLate() throws Exception {
        relogio.definir(em("2026-11-17T10:00"));

        posicionar(GESTOR, "Atrasado").andExpect(status().isOk())
                .andExpect(jsonPath("$.relogios[0].estado").value("VIOLADO"))
                .andExpect(jsonPath("$.relogios[0].violadoEm").value(em("2026-11-17T09:00").toString()));
        UUID eventoId = ChamadoTestHelper.eventoId(new Violacao(chamado, TipoRelogio.RESPOSTA, 1, ABERTURA, em("2026-11-17T09:00")));
        assertThat(jdbc.queryForObject("select tipo from outbox_eventos where evento_id = ?", String.class, eventoId)).isEqualTo("VIOLACAO");
    }

    // RN-52, RN-113: se a transação falha depois de gravar o e-mail, o e-mail não existe.
    @Test
    void shouldNotWriteEmailWhenTransactionRollsBack() throws Exception {
        jdbc.execute("""
                create function falha_posicionamento() returns trigger language plpgsql as $$
                begin raise exception 'falha simulada'; end; $$""");
        jdbc.execute("""
                create trigger falha_posicionamento before insert on chamado_historico
                for each row when (new.evento = 'POSICIONAMENTO') execute function falha_posicionamento()""");
        try {
            posicionar(GESTOR, "Não deve sair").andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("drop trigger falha_posicionamento on chamado_historico");
            jdbc.execute("drop function falha_posicionamento()");
        }

        assertThat(emails()).isZero();
        assertThat(jdbc.queryForObject("select posicionamento from chamado where id = ?", String.class, chamado)).isNull();
        assertThat(jdbc.queryForObject("select cumprido_em from relogio_sla where chamado_id = ? and tipo = 'RESPOSTA'",
                Instant.class, chamado)).isNull();
    }

    // Em qualquer estado não final: os eventos podem vir fora de ordem.
    @ParameterizedTest
    @ValueSource(strings = {"ATRIBUIDO", "EM_ATENDIMENTO", "RESOLVIDO"})
    void shouldAcceptPositionInAnyNonFinalState(String estado) throws Exception {
        jdbc.update("update chamado set estado = ? where id = ?", estado, chamado);

        posicionar(GESTOR, "Texto").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENCERRADO", "CANCELADO"})
    void shouldRejectPositionInFinalState(String estado) throws Exception {
        jdbc.update("update chamado set estado = ? where id = ?", estado, chamado);

        posicionar(GESTOR, "Texto").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
        assertThat(emails()).isZero();
    }

    @Test
    void shouldValidateText() throws Exception {
        posicionar(GESTOR, " ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACAO"));
        posicionar(GESTOR, "x".repeat(4001)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/chamados/" + UUID.randomUUID() + "/posicionamento").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"texto\": \"t\"}")).andExpect(status().isNotFound());
    }

    @Test
    void shouldAllowOnlyManager() throws Exception {
        UUID empresa = jdbc.queryForObject("select cliente_id from chamado where id = ?", UUID.class, chamado);
        for (String quem : List.of(token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE"),
                token(ChamadoTeste.usuario(jdbc, "TECNICO", null), "TECNICO"))) {
            posicionar(quem, "Texto").andExpect(status().isForbidden());
        }
        assertThat(emails()).isZero();
    }

    private ResultActions posicionar(String quem, String texto) throws Exception {
        return mvc.perform(post("/api/chamados/" + chamado + "/posicionamento").header(HttpHeaders.AUTHORIZATION, quem)
                .contentType(MediaType.APPLICATION_JSON).content("{\"texto\": \"" + texto + "\"}"));
    }

    private long emails() {
        return jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'POSICIONAMENTO' and payload->'chamado'->>'id' = ?",
                Long.class, chamado.toString());
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
