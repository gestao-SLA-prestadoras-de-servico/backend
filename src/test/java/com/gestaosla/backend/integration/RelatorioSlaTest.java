package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.relatorio.internal.RelatorioSlaTestHelper;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RelatorioSlaTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext contexto;

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
        Instant congelada = Instant.parse("2026-11-18T12:00:00Z");
        apuracao(clienteA, tecnico, "RESPOSTA", "CUMPRIDO", congelada);
        apuracao(clienteA, tecnico, "CAMPO", "CUMPRIDO", congelada);
        apuracao(clienteA, tecnico, "RESOLUCAO", "VIOLADO", congelada);
        apuracao(clienteB, UUID.randomUUID(), "RESPOSTA", "VIOLADO", congelada);

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
