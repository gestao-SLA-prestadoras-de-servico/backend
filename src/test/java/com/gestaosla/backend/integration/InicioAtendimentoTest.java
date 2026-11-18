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
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class InicioAtendimentoTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    // Segunda 09:00, NORMAL: o campo vence quinta 09:00.
    private static final Instant ABERTURA = em("2026-11-16T09:00");

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
    private UUID tecnico;
    private UUID chamado;

    @BeforeEach
    void preparar() {
        relogio.definir(em("2026-11-16T10:00"));
        helper = new ChamadoTestHelper(contexto);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "ATRIBUIDO", tecnico);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, ABERTURA));
    }

    // RF-22, RN-24: ATRIBUIDO → EM_ATENDIMENTO, atendimento novo EM_ANDAMENTO e o SLA de campo cumprido.
    @Test
    void shouldStartAttendanceFulfillingFieldClock() throws Exception {
        iniciar(tecnico).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EM_ATENDIMENTO"))
                .andExpect(jsonPath("$.relogios[1].tipo").value("CAMPO"))
                .andExpect(jsonPath("$.relogios[1].estado").value("CUMPRIDO"))
                .andExpect(jsonPath("$.relogios[1].cumpridoEm").value(em("2026-11-16T10:00").toString()))
                .andExpect(jsonPath("$.atendimentos.length()").value(1))
                .andExpect(jsonPath("$.atendimentos[0].estado").value("EM_ANDAMENTO"))
                .andExpect(jsonPath("$.atendimentos[0].iniciadoEm").value(em("2026-11-16T10:00").toString()));
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'MUDANCA_ESTADO' "
                + "and dados->>'para' = 'EM_ATENDIMENTO' and ator_sub = ?", Long.class, chamado, tecnico)).isEqualTo(1);
    }

    // Com o atendimento PENDENTE da reabertura, é ele que começa.
    @Test
    void shouldUsePendingAttendance() throws Exception {
        UUID pendente = helper.criarAtendimentoPendente(chamado, tecnico);

        iniciar(tecnico).andExpect(status().isOk())
                .andExpect(jsonPath("$.atendimentos.length()").value(1))
                .andExpect(jsonPath("$.atendimentos[0].id").value(pendente.toString()))
                .andExpect(jsonPath("$.atendimentos[0].estado").value("EM_ANDAMENTO"));
    }

    // RN-22: depois da reatribuição, o novo início não muda o SLA de campo, cumprido no primeiro.
    @Test
    void shouldNotChangeFieldClockOnStartAfterReassignment() throws Exception {
        iniciar(tecnico).andExpect(status().isOk());
        UUID outro = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        long version = jdbc.queryForObject("select version from chamado where id = ?", Long.class, chamado);
        mvc.perform(post("/api/chamados/" + chamado + "/atribuicao").header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"tecnicoSub\": \"" + outro + "\", \"version\": " + version + "}"))
                .andExpect(status().isOk());
        relogio.definir(em("2026-11-20T10:00"));

        iniciar(outro).andExpect(status().isOk())
                .andExpect(jsonPath("$.relogios[1].estado").value("CUMPRIDO"))
                .andExpect(jsonPath("$.relogios[1].cumpridoEm").value(em("2026-11-16T10:00").toString()));
        assertThat(helper.estadosDosAtendimentos(chamado)).containsExactly(EstadoAtendimento.INTERROMPIDO, EstadoAtendimento.EM_ANDAMENTO);
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'VIOLACAO' and payload->'chamado'->>'id' = ?",
                Long.class, chamado.toString())).isZero();
    }

    // RF-33: início depois do prazo de campo viola o relógio e grava o aviso.
    @Test
    void shouldViolateFieldClockWhenStartIsLate() throws Exception {
        relogio.definir(em("2026-11-19T10:00"));

        iniciar(tecnico).andExpect(status().isOk()).andExpect(jsonPath("$.relogios[1].estado").value("VIOLADO"));

        UUID eventoId = ChamadoTestHelper.eventoId(new Violacao(chamado, TipoRelogio.CAMPO, 1, ABERTURA, em("2026-11-19T09:00")));
        assertThat(jdbc.queryForObject("select payload->>'relogio' from outbox_eventos where evento_id = ?", String.class, eventoId))
                .isEqualTo("ATENDIMENTO_CAMPO");
    }

    // RN-126: técnico que não é o atribuído recebe 404, no início e na descrição.
    @Test
    void shouldAnswer404ToTechnicianNotAssigned() throws Exception {
        UUID outro = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));

        iniciar(outro).andExpect(status().isNotFound());
        descrever(outro, "Trocado o capacitor").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("ATRIBUIDO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EM_ATENDIMENTO", "RESOLVIDO", "ENCERRADO", "CANCELADO"})
    void shouldStartOnlyFromAssigned(String estado) throws Exception {
        jdbc.update("update chamado set estado = ? where id = ?", estado, chamado);

        iniciar(tecnico).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
        assertThat(helper.estadosDosAtendimentos(chamado)).isEmpty();
    }

    // RF-23: a descrição só com o atendimento EM_ANDAMENTO; pode ser reescrita.
    @Test
    void shouldRegisterDescriptionOnlyWhileInProgress() throws Exception {
        descrever(tecnico, "Antes de começar").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
        helper.criarAtendimentoPendente(chamado, tecnico);
        descrever(tecnico, "Ainda pendente").andExpect(status().isUnprocessableContent());

        iniciar(tecnico).andExpect(status().isOk());
        descrever(tecnico, "Trocado o capacitor").andExpect(status().isOk());
        descrever(tecnico, "Trocado o capacitor e limpo o filtro").andExpect(status().isOk())
                .andExpect(jsonPath("$.atendimentos[0].descricaoTecnica").value("Trocado o capacitor e limpo o filtro"));
        mvc.perform(put("/api/chamados/" + chamado + "/atendimento/descricao").header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"descricao\": \" \"}")).andExpect(status().isBadRequest());
    }

    @Test
    void shouldAllowOnlyTechnician() throws Exception {
        mvc.perform(post("/api/chamados/" + chamado + "/atendimento/inicio").header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("ATRIBUIDO");
    }

    private ResultActions iniciar(UUID quem) throws Exception {
        return mvc.perform(post("/api/chamados/" + chamado + "/atendimento/inicio")
                .header(HttpHeaders.AUTHORIZATION, token(quem.toString(), "TECNICO")));
    }

    private ResultActions descrever(UUID quem, String descricao) throws Exception {
        return mvc.perform(put("/api/chamados/" + chamado + "/atendimento/descricao").header(HttpHeaders.AUTHORIZATION, token(quem.toString(), "TECNICO"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"descricao\": \"" + descricao + "\"}"));
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
