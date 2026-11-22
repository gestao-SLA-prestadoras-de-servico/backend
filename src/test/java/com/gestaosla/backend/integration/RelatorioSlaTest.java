package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.relatorio.internal.RelatorioSlaTestHelper;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RelatorioSlaTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext contexto;
    @Autowired private MockMvc mvc;

    private RelatorioSlaTestHelper relatorio;

    @BeforeEach
    void setUp() {
        relatorio = new RelatorioSlaTestHelper(contexto);
    }

    @Test
    void shouldAggregateFrozenAssessmentsAndApplyFilters() {
        UUID clienteA = cliente();
        UUID clienteB = cliente();
        UUID tecnico = UUID.randomUUID();
        Instant congelada = instanteUnico();
        apuracao(clienteA, tecnico, "RESPOSTA", "CUMPRIDO", congelada);
        apuracao(clienteA, tecnico, "CAMPO", "CUMPRIDO", congelada);
        apuracao(clienteA, tecnico, "RESOLUCAO", "VIOLADO", congelada);
        apuracao(clienteB, UUID.randomUUID(), "RESPOSTA", "VIOLADO", congelada);
        UUID cancelado = ChamadoTeste.chamado(jdbc, clienteA, "CANCELADO", tecnico);
        assertThat(jdbc.queryForObject("select count(*) from apuracao_sla where chamado_id = ?", Long.class, cancelado))
                .isZero();

        var geral = relatorio.gerar(congelada, congelada, null, null);
        var resposta = geral.geral().stream().filter(r -> r.tipo().equals("RESPOSTA")).findFirst().orElseThrow();
        assertThat(resposta.metricas().cumpridos()).isEqualTo(1);
        assertThat(resposta.metricas().violados()).isEqualTo(1);
        assertThat(resposta.metricas().percentual()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(geral.porCliente()).hasSize(2);
        assertThat(geral.porTecnico()).allSatisfy(g -> assertThat(g.relogios()).noneMatch(r -> r.tipo().equals("RESPOSTA")));

        var filtrado = relatorio.gerar(congelada, congelada, clienteA, tecnico);
        assertThat(filtrado.geral()).hasSize(3);
        assertThat(filtrado.porCliente()).extracting("clienteId").containsExactly(clienteA);
        assertThat(filtrado.porTecnico()).extracting("tecnicoSub").containsExactly(tecnico);
    }

    @Test
    void shouldServeReportToManagerAndRejectInvalidPeriod() throws Exception {
        Instant congelada = Instant.parse("2026-11-18T12:00:00Z");
        UUID cliente = cliente();
        apuracao(cliente, UUID.randomUUID(), "CAMPO", "CUMPRIDO", congelada);
        String gestor = token(SUB_GESTOR, "GESTOR");

        mvc.perform(get("/api/relatorios/sla").param("inicio", congelada.toString()).param("fim", congelada.toString())
                        .param("clienteId", cliente.toString())
                        .header(HttpHeaders.AUTHORIZATION, gestor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geral[0].tipo").value("CAMPO"))
                .andExpect(jsonPath("$.geral[0].metricas.cumpridos").value(1));

        mvc.perform(get("/api/relatorios/sla").param("inicio", "2026-11-19T12:00:00Z")
                        .param("fim", congelada.toString()).header(HttpHeaders.AUTHORIZATION, gestor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldExportCsvWithExcelEncodingAndSameFilteredNumbersAsScreen() throws Exception {
        Instant congelada = Instant.parse("2026-11-18T12:00:00Z");
        UUID cliente = cliente();
        UUID tecnico = tecnico("Ana Técnica");
        apuracao(cliente, tecnico, "CAMPO", "CUMPRIDO", congelada);
        apuracao(cliente, tecnico, "RESOLUCAO", "VIOLADO", congelada);
        String gestor = token(SUB_GESTOR, "GESTOR");

        var tela = mvc.perform(get("/api/relatorios/sla").param("inicio", congelada.toString())
                        .param("fim", congelada.toString()).param("clienteId", cliente.toString())
                        .param("tecnicoSub", tecnico.toString()).header(HttpHeaders.AUTHORIZATION, gestor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        MvcResult resposta = mvc.perform(get("/api/relatorios/sla.csv").param("inicio", congelada.toString())
                        .param("fim", congelada.toString()).param("clienteId", cliente.toString())
                        .param("tecnicoSub", tecnico.toString()).header(HttpHeaders.AUTHORIZATION, gestor))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("relatorio-sla_2026-11-18_a_2026-11-18.csv")))
                .andReturn();
        byte[] bytes = resposta.getResponse().getContentAsByteArray();
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(resposta.getResponse().getContentType()).contains("text/csv").contains("charset=UTF-8");
        assertThat(csv).contains("Seção;Cliente;Cliente UUID;Técnico;Técnico UUID;Relógio;Cumpridos;Violados;Percentual")
                .contains("geral;;;;;CAMPO;1;0;100,00")
                .contains("cliente;Relatório;" + cliente + ";;;CAMPO;1;0;100,00")
                .contains("tecnico;;;Ana Técnica;" + tecnico + ";CAMPO;1;0;100,00")
                .contains("RESOLUCAO;0;1;0,00");
        assertThat(tela).contains("\"cumpridos\":1", "\"violados\":1", "\"percentual\":100.00");
    }

    @Test
    void shouldExportPdfWithFiltersGenerationAndAllReportSections() throws Exception {
        Instant congelada = Instant.parse("2026-11-18T12:00:00Z");
        UUID cliente = cliente();
        UUID tecnico = tecnico("Ana Técnica");
        apuracao(cliente, tecnico, "CAMPO", "CUMPRIDO", congelada);
        UUID tecnicoSemCadastro = UUID.randomUUID();
        apuracao(cliente, tecnicoSemCadastro, "CAMPO", "VIOLADO", congelada);
        String gestor = token(SUB_GESTOR, "GESTOR");

        MvcResult resposta = mvc.perform(get("/api/relatorios/sla.pdf").param("inicio", congelada.toString())
                        .param("fim", congelada.toString()).param("clienteId", cliente.toString())
                        .param("tecnicoSub", tecnico.toString()).header(HttpHeaders.AUTHORIZATION, gestor))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("relatorio-sla_2026-11-18_a_2026-11-18.pdf")))
                .andReturn();
        byte[] bytes = resposta.getResponse().getContentAsByteArray();
        assertThat(resposta.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(bytes).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        try (PdfReader leitor = new PdfReader(bytes)) {
            String texto = new PdfTextExtractor(leitor).getTextFromPage(1);
            assertThat(texto).contains("Relatório de cumprimento de SLA", "Período:", "Cliente: Relatório",
                    cliente.toString(), "Técnico: Ana Técnica", tecnico.toString(), "Nome não localizado",
                    tecnicoSemCadastro.toString(), "Gerado em:", "Resultado geral", "Por cliente", "Por técnico",
                    "CAMPO", "100.00%", "Cumpridos", "Violados");
        }
    }

    @Test
    void shouldRequireAuthenticationForReport() throws Exception {
        mvc.perform(get("/api/relatorios/sla").param("inicio", "2026-11-18T00:00:00Z")
                        .param("fim", "2026-11-18T23:59:59Z"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnFriendlyEmptyResult() {
        var resultado = relatorio.gerar(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-02T00:00:00Z"),
                null, null);
        assertThat(resultado.geral()).isEmpty();
        assertThat(resultado.mensagemSemResultados()).contains("Nenhuma apuração encontrada");
    }

    private UUID cliente() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (id, created_at, updated_at, razao_social, email_contato, logradouro, numero,
                    bairro, cidade, uf, cep, ativo, version)
                values (?, now(), now(), 'Relatório', 'r@x.com', 'Rua A', '1', 'Centro', 'Fortaleza', 'CE', '60000000', true, 0)
                """, id);
        return id;
    }

    private UUID tecnico(String nome) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id, version)
                values (?, now(), now(), ?, ?, ?, 'TECNICO', true, null, 0)
                """, UUID.randomUUID(), sub, nome, sub + "@flowsla.local");
        return sub;
    }

    private Instant instanteUnico() {
        long variacao = UUID.randomUUID().getMostSignificantBits() & 0xFFFFF;
        return Instant.ofEpochMilli(1_700_000_000_000L + variacao);
    }

    private void apuracao(UUID cliente, UUID tecnico, String tipo, String resultado, Instant congelada) {
        UUID chamado = ChamadoTeste.chamado(jdbc, cliente, "ENCERRADO", tecnico);
        jdbc.update("""
                insert into apuracao_sla (id, chamado_id, tipo, cliente_id, prioridade, tecnico_sub, prazo_minutos,
                    vence_em, resultado, violou_em_ciclo_anterior, congelada_em)
                values (?, ?, ?, ?, 'ALTA', ?, 60, ?, ?, false, ?)
                """, UUID.randomUUID(), chamado, tipo, cliente, tecnico, Timestamp.from(congelada), resultado,
                Timestamp.from(congelada));
    }
}
