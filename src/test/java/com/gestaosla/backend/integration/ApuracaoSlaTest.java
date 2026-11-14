package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.sla.api.ApuracaoChamado;
import com.gestaosla.backend.sla.api.ApuracaoSla;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.ResultadoApuracao;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ApuracaoSlaTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    private static final UUID VERSAO_PADRAO = UUID.fromString("0199a000-0000-7000-8000-00000000c002");
    // Sexta-feira, 16:30. ALTA: resposta vence segunda 09:30; campo, segunda 16:30; resolução, terça 16:30.
    private static final Instant ABERTURA = em("2026-11-13T16:30");
    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");

    @Autowired
    private RelogiosSla relogios;

    @Autowired
    private ApuracaoSla apuracoes;

    @Autowired
    private ContratoConsulta contratos;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private MockMvc mvc;

    // RF-36: por relógio, prazo, vencimento, cumprimento, violação e resultado; e cliente, prioridade e técnico.
    @Test
    void shouldFreezeEveryClockWithCallerData() {
        UUID cliente = cliente();
        UUID tecnico = UUID.randomUUID();
        UUID chamado = abrir();
        cumprir(chamado, TipoRelogio.RESPOSTA, "2026-11-16T09:00");
        cumprir(chamado, TipoRelogio.CAMPO, "2026-11-17T09:00"); // atrasado: violou às 16:30 de segunda
        cumprir(chamado, TipoRelogio.RESOLUCAO, "2026-11-17T10:00");
        relogio.definir(em("2026-11-17T12:00"));

        List<Violacao> novas = congelar(chamado, cliente, tecnico);

        assertThat(novas).isEmpty();
        ApuracaoChamado apuracao = apuracoes.consultarApuracao(chamado).orElseThrow();
        assertThat(apuracao.clienteId()).isEqualTo(cliente);
        assertThat(apuracao.prioridade()).isEqualTo(Prioridade.ALTA);
        assertThat(apuracao.tecnicoSub()).isEqualTo(tecnico);
        assertThat(apuracao.congeladaEm()).isEqualTo(em("2026-11-17T12:00"));
        assertThat(apuracao.relogios()).extracting(ApuracaoChamado.Relogio::tipo, ApuracaoChamado.Relogio::prazoMinutos,
                ApuracaoChamado.Relogio::venceEm, ApuracaoChamado.Relogio::cumpridoEm, ApuracaoChamado.Relogio::violadoEm,
                ApuracaoChamado.Relogio::resultado).containsExactly(
                tuple(TipoRelogio.RESPOSTA, 120, em("2026-11-16T09:30"), em("2026-11-16T09:00"), null, ResultadoApuracao.CUMPRIDO),
                tuple(TipoRelogio.CAMPO, 540, em("2026-11-16T16:30"), em("2026-11-17T09:00"), em("2026-11-16T16:30"), ResultadoApuracao.VIOLADO),
                tuple(TipoRelogio.RESOLUCAO, 1080, em("2026-11-17T16:30"), em("2026-11-17T10:00"), null, ResultadoApuracao.CUMPRIDO));
    }

    // Sem o evento: dentro do prazo conta CUMPRIDO; com o prazo vencido, é violado na hora e devolvido ao chamador.
    @Test
    void shouldFreezeClockWithoutEventAsFulfilledInsideDeadlineAndViolatedAfterIt() {
        UUID cliente = cliente();
        UUID dentro = abrir();
        cumprir(dentro, TipoRelogio.RESPOSTA, "2026-11-16T09:00");
        cumprir(dentro, TipoRelogio.RESOLUCAO, "2026-11-16T10:00");
        relogio.definir(em("2026-11-16T11:00"));
        congelar(dentro, cliente, null);
        UUID vencido = abrir();
        cumprir(vencido, TipoRelogio.RESPOSTA, "2026-11-16T09:00");
        cumprir(vencido, TipoRelogio.RESOLUCAO, "2026-11-16T10:00");
        relogio.definir(em("2026-11-17T10:00"));

        List<Violacao> novas = congelar(vencido, cliente, null);

        assertThat(resultado(dentro, TipoRelogio.CAMPO)).isEqualTo(ResultadoApuracao.CUMPRIDO);
        assertThat(resultado(vencido, TipoRelogio.CAMPO)).isEqualTo(ResultadoApuracao.VIOLADO);
        assertThat(novas).extracting(Violacao::tipo, Violacao::violadaEm).containsExactly(tuple(TipoRelogio.CAMPO, em("2026-11-16T16:30")));
    }

    // RN-69 em todos os ciclos: violado no ciclo 1 e cumprido no prazo no ciclo 2 fica VIOLADO, com o indicador.
    @Test
    void shouldCountViolationOfPreviousCycle() {
        UUID chamado = abrir();
        cumprir(chamado, TipoRelogio.RESPOSTA, "2026-11-16T09:00");
        cumprir(chamado, TipoRelogio.CAMPO, "2026-11-17T09:00");
        cumprir(chamado, TipoRelogio.RESOLUCAO, "2026-11-17T10:00");
        emTransacao(() -> relogios.reiniciarParaReabertura(chamado, versaoPadrao(), Prioridade.ALTA, em("2026-11-18T10:00")));
        cumprir(chamado, TipoRelogio.CAMPO, "2026-11-18T15:00");
        cumprir(chamado, TipoRelogio.RESOLUCAO, "2026-11-19T09:00");
        relogio.definir(em("2026-11-19T12:00"));

        congelar(chamado, cliente(), null);

        assertThat(apuracoes.consultarApuracao(chamado).orElseThrow().relogios())
                .extracting(ApuracaoChamado.Relogio::tipo, ApuracaoChamado.Relogio::violadoEm, ApuracaoChamado.Relogio::resultado,
                        ApuracaoChamado.Relogio::violouEmCicloAnterior)
                .containsExactly(
                        tuple(TipoRelogio.RESPOSTA, null, ResultadoApuracao.CUMPRIDO, false),
                        tuple(TipoRelogio.CAMPO, null, ResultadoApuracao.VIOLADO, true),
                        tuple(TipoRelogio.RESOLUCAO, null, ResultadoApuracao.CUMPRIDO, false));
    }

    // RN-68: congelar de novo não muda nada, e nem SQL direto altera a apuração.
    @Test
    void shouldNeverChangeFrozenAssessment() {
        UUID chamado = abrir();
        relogio.definir(em("2026-11-16T09:00"));
        congelar(chamado, cliente(), null);
        List<Map<String, Object>> antes = linhas(chamado);

        relogio.definir(em("2026-11-30T09:00"));
        assertThat(congelar(chamado, cliente(), UUID.randomUUID())).isEmpty();

        assertThat(linhas(chamado)).isEqualTo(antes);
        assertThatThrownBy(() -> jdbc.update("update apuracao_sla set resultado = 'CUMPRIDO' where chamado_id = ?", chamado))
                .hasMessageContaining("não pode ser alterada");
        assertThatThrownBy(() -> jdbc.update("delete from apuracao_sla where chamado_id = ?", chamado))
                .hasMessageContaining("não pode ser alterada");
        assertThatThrownBy(() -> jdbc.execute("truncate apuracao_sla")).hasMessageContaining("não pode ser alterada");
    }

    // Alterar a política depois não muda a apuração: ela guarda os prazos, não aponta para a versão.
    @Test
    void shouldKeepAssessmentWhenPolicyChangesLater() {
        UUID politica = UUID.randomUUID();
        UUID versao = UUID.randomUUID();
        jdbc.update("insert into politica_sla (id, created_at, updated_at, nome) values (?, now(), now(), ?)", politica, "Apuração " + politica);
        inserirVersao(politica, versao, 1, 60);
        UUID chamado = UUID.randomUUID();
        emTransacao(() -> relogios.iniciarRelogios(chamado, contratos.versaoDaPolitica(versao), Prioridade.ALTA, ABERTURA));
        relogio.definir(em("2026-11-16T08:30"));
        congelar(chamado, cliente(), null);
        List<Map<String, Object>> antes = linhas(chamado);

        inserirVersao(politica, UUID.randomUUID(), 2, 300);

        assertThat(linhas(chamado)).isEqualTo(antes);
        assertThat(apuracoes.consultarApuracao(chamado).orElseThrow().relogios().get(0).prazoMinutos()).isEqualTo(60);
    }

    @Test
    void shouldRefuseToFreezeChamadoWithoutClocks() {
        assertThatThrownBy(() -> congelar(UUID.randomUUID(), cliente(), null)).isInstanceOf(IllegalStateException.class);
    }

    // ---- GET /api/chamados/{id}/sla ----

    @Test
    void shouldShowAssessmentToManagerOwnerClientAndResponsibleTechnician() throws Exception {
        UUID empresa = cliente();
        String tecnicoSub = usuario("TECNICO", null);
        UUID chamado = abrir();
        cumprir(chamado, TipoRelogio.RESPOSTA, "2026-11-16T10:00");
        relogio.definir(em("2026-11-16T11:00"));
        congelar(chamado, empresa, UUID.fromString(tecnicoSub));

        for (String quem : List.of(GESTOR, token(usuario("CLIENTE", empresa), "CLIENTE"), token(tecnicoSub, "TECNICO"))) {
            mvc.perform(get("/api/chamados/" + chamado + "/sla").header(HttpHeaders.AUTHORIZATION, quem))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.apurado").value(true))
                    .andExpect(jsonPath("$.prioridade").value("ALTA"))
                    .andExpect(jsonPath("$.relogios[0].tipo").value("RESPOSTA"))
                    .andExpect(jsonPath("$.relogios[0].resultado").value("VIOLADO"))
                    .andExpect(jsonPath("$.relogios[0].prazoMinutos").value(120))
                    .andExpect(jsonPath("$.relogios[1].resultado").value("CUMPRIDO"));
        }
    }

    // RN-126: chamado de outra empresa, ou de outro técnico, responde 404, nunca 403.
    @Test
    void shouldHideAssessmentFromOtherClientsAndTechnicians() throws Exception {
        UUID chamado = abrir();
        relogio.definir(em("2026-11-16T09:00"));
        congelar(chamado, cliente(), UUID.randomUUID());

        for (String quem : List.of(token(usuario("CLIENTE", cliente()), "CLIENTE"), token(usuario("TECNICO", null), "TECNICO"))) {
            mvc.perform(get("/api/chamados/" + chamado + "/sla").header(HttpHeaders.AUTHORIZATION, quem))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("RECURSO_NAO_ENCONTRADO"));
        }
    }

    // RN-19: cancelado (ou ainda não encerrado) não tem apuração.
    @Test
    void shouldAnswerWithoutAssessmentForCancelledOrOpenChamado() throws Exception {
        UUID semApuracao = abrir();

        mvc.perform(get("/api/chamados/" + semApuracao + "/sla").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apurado").value(false))
                .andExpect(jsonPath("$.relogios").doesNotExist());
        mvc.perform(get("/api/chamados/" + semApuracao + "/sla").header(HttpHeaders.AUTHORIZATION, token(usuario("CLIENTE", cliente()), "CLIENTE")))
                .andExpect(status().isNotFound());
    }

    private UUID abrir() {
        UUID chamado = UUID.randomUUID();
        emTransacao(() -> relogios.iniciarRelogios(chamado, versaoPadrao(), Prioridade.ALTA, ABERTURA));
        return chamado;
    }

    private void cumprir(UUID chamado, TipoRelogio tipo, String quando) {
        emTransacao(() -> relogios.cumprir(chamado, tipo, em(quando)));
    }

    private List<Violacao> congelar(UUID chamado, UUID cliente, UUID tecnico) {
        return emTransacao(() -> apuracoes.congelarApuracao(chamado, cliente, Prioridade.ALTA, tecnico));
    }

    private ResultadoApuracao resultado(UUID chamado, TipoRelogio tipo) {
        return apuracoes.consultarApuracao(chamado).orElseThrow().relogios().stream()
                .filter(r -> r.tipo() == tipo).findFirst().orElseThrow().resultado();
    }

    private List<Map<String, Object>> linhas(UUID chamado) {
        return jdbc.queryForList("select * from apuracao_sla where chamado_id = ? order by tipo", chamado);
    }

    private void inserirVersao(UUID politica, UUID versao, int numero, int respostaAlta) {
        jdbc.update("""
                insert into politica_sla_versao (id, politica_id, numero, resposta_alta, campo_alta, resolucao_alta,
                    resposta_normal, campo_normal, resolucao_normal, prioridade_padrao, criada_em)
                values (?, ?, ?, ?, 600, 1200, 540, 1620, 2700, 'NORMAL', now())""", versao, politica, numero, respostaAlta);
    }

    private PoliticaDoContrato versaoPadrao() {
        return contratos.versaoDaPolitica(VERSAO_PADRAO);
    }

    private <T> T emTransacao(Supplier<T> acao) {
        return transacao.execute(status -> acao.get());
    }

    private UUID cliente() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (id, created_at, updated_at, razao_social, email_contato, logradouro, numero, bairro,
                                     cidade, uf, cep, ativo, version)
                values (?, now(), now(), 'Cliente', 'c@x.com', 'Rua A', '1', 'Centro', 'Fortaleza', 'CE', '60000000', true, 0)""", id);
        return id;
    }

    private String usuario(String perfil, UUID empresa) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id)
                values (?, now(), now(), ?, 'Usuário', ?, ?, true, ?)""", UUID.randomUUID(), sub, sub + "@x.com", perfil, empresa);
        return sub.toString();
    }

    private static Instant em(String local) {
        return LocalDateTime.parse(local).atZone(FORTALEZA).toInstant();
    }
}
