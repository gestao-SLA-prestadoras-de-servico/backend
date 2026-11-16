package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;
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

import com.gestaosla.backend.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PoliticaTest {

    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");
    private static final String POLITICA_PADRAO = "0199a000-0000-7000-8000-00000000c001";
    private static final String PRAZOS_V1 = """
            {"alta":{"resposta":60,"campo":300,"resolucao":900},"normal":{"resposta":300,"campo":900,"resolucao":1800}}""";
    private static final String PRAZOS_V2 = """
            {"alta":{"resposta":90,"campo":400,"resolucao":1000},"normal":{"resposta":400,"campo":1000,"resolucao":2000}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    // Seção 5.4: existe ao subir o sistema, sem ninguém cadastrar.
    @Test
    void shouldSeedDefaultPolicy() throws Exception {
        mvc.perform(get("/api/politicas/" + POLITICA_PADRAO + "/versoes").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].numero").value(1))
                .andExpect(jsonPath("$.content[0].prioridadePadrao").value("NORMAL"))
                .andExpect(jsonPath("$.content[0].prazos.alta.resposta").value(120))
                .andExpect(jsonPath("$.content[0].prazos.alta.campo").value(540))
                .andExpect(jsonPath("$.content[0].prazos.alta.resolucao").value(1080))
                .andExpect(jsonPath("$.content[0].prazos.normal.resposta").value(540))
                .andExpect(jsonPath("$.content[0].prazos.normal.campo").value(1620))
                .andExpect(jsonPath("$.content[0].prazos.normal.resolucao").value(2700));
        assertThat(jdbc.queryForObject("select nome from politica_sla where id = ?::uuid", String.class, POLITICA_PADRAO))
                .isEqualTo("Padrão");
    }

    @Test
    void shouldCreatePolicyWithFirstVersionAndAudit() throws Exception {
        String id = id(criar(nome(), "ALTA", PRAZOS_V1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versaoAtual.numero").value(1))
                .andExpect(jsonPath("$.versaoAtual.prioridadePadrao").value("ALTA"))
                .andExpect(jsonPath("$.versaoAtual.prazos.normal.resolucao").value(1800))
                .andExpect(jsonPath("$.versaoAtual.criadaPor").value(SUB_GESTOR)));

        assertThat(auditorias(id)).containsExactly(1);
    }

    // RN-04: versão nova não altera a anterior; não existe edição.
    @Test
    void shouldCreateNewVersionKeepingPreviousOneUnchanged() throws Exception {
        String id = id(criar(nome(), "NORMAL", PRAZOS_V1));
        Map<String, Object> v1Antes = jdbc.queryForMap("select * from politica_sla_versao where politica_id = ?::uuid", id);

        novaVersao(id, "ALTA", PRAZOS_V2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value(2))
                .andExpect(jsonPath("$.prazos.alta.resposta").value(90));

        assertThat(jdbc.queryForMap("select * from politica_sla_versao where politica_id = ?::uuid and numero = 1", id))
                .isEqualTo(v1Antes);
        mvc.perform(get("/api/politicas/" + id + "/versoes").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.content[*].numero").value(Matchers.contains(2, 1)))
                .andExpect(jsonPath("$.content[1].prazos.alta.resposta").value(60));
        assertThat(auditorias(id)).containsExactly(1, 2);
    }

    // Versões novas simultâneas: a trava na política faz cada uma numerar depois da anterior, sem 500 por número repetido.
    @Test
    void shouldNumberConcurrentVersionsSequentially() throws Exception {
        String id = id(criar(nome(), "NORMAL", PRAZOS_V1));
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(5)) {
            var inicio = new java.util.concurrent.CountDownLatch(1);
            var respostas = java.util.stream.IntStream.range(0, 5).mapToObj(i -> threads.submit(() -> {
                inicio.await();
                return novaVersao(id, "NORMAL", PRAZOS_V2).andReturn().getResponse().getStatus();
            })).toList();
            inicio.countDown();
            for (var resposta : respostas) {
                assertThat(resposta.get()).isEqualTo(201);
            }
        }
        assertThat(jdbc.queryForList("select numero from politica_sla_versao where politica_id = ?::uuid order by numero",
                Integer.class, id)).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void shouldListPoliciesWithCurrentVersion() throws Exception {
        String nome = nome();
        String id = id(criar(nome, "NORMAL", PRAZOS_V1));
        novaVersao(id, "ALTA", PRAZOS_V2);

        mvc.perform(get("/api/politicas").param("size", "100").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')].versaoAtual.numero".formatted(id)).value(Matchers.contains(2)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].nome".formatted(POLITICA_PADRAO)).value(Matchers.contains("Padrão")));
    }

    // RN-30/RN-32: em qualquer prioridade, resposta < campo < resolução.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"alta\":{\"resposta\":540,\"campo\":540,\"resolucao\":1080},\"normal\":{\"resposta\":540,\"campo\":1620,\"resolucao\":2700}}",
            "{\"alta\":{\"resposta\":120,\"campo\":1080,\"resolucao\":1080},\"normal\":{\"resposta\":540,\"campo\":1620,\"resolucao\":2700}}",
            "{\"alta\":{\"resposta\":120,\"campo\":540,\"resolucao\":1080},\"normal\":{\"resposta\":1620,\"campo\":540,\"resolucao\":2700}}",
            "{\"alta\":{\"resposta\":120,\"campo\":540,\"resolucao\":1080},\"normal\":{\"resposta\":540,\"campo\":2700,\"resolucao\":1620}}"})
    void shouldReturn422ForDeadlinesOutOfOrder(String prazos) throws Exception {
        String nome = nome();
        criar(nome, "NORMAL", prazos)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("ORDEM_PRAZOS_INVALIDA"));
        novaVersao(POLITICA_PADRAO, "NORMAL", prazos)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("ORDEM_PRAZOS_INVALIDA"));
        assertThat(jdbc.queryForObject("select count(*) from politica_sla where nome = ?", Integer.class, nome)).isZero();
    }

    @Test
    void shouldReturn400ForMissingOrNonPositiveDeadline() throws Exception {
        criar(nome(), "NORMAL", "{\"alta\":{\"resposta\":0,\"campo\":540,\"resolucao\":1080}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"))
                .andExpect(jsonPath("$.erros[*].campo").value(Matchers.containsInAnyOrder("prazos.alta.resposta", "prazos.normal")));
    }

    @Test
    void shouldReturn409ForDuplicateName() throws Exception {
        criar("Padrão", "NORMAL", PRAZOS_V1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NOME_POLITICA_JA_EXISTE"));
    }

    @Test
    void shouldReturn404ForUnknownPolicy() throws Exception {
        String desconhecida = UUID.randomUUID().toString();
        novaVersao(desconhecida, "NORMAL", PRAZOS_V1).andExpect(status().isNotFound());
        mvc.perform(get("/api/politicas/" + desconhecida + "/versoes").header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(status().isNotFound());
    }

    // RN-04 também para SQL direto.
    @Test
    void shouldRejectUpdateAndDeleteOfVersionInDatabase() {
        assertThatThrownBy(() -> jdbc.update("update politica_sla_versao set resposta_alta = 1 where politica_id = ?::uuid",
                POLITICA_PADRAO)).hasMessageContaining("não pode ser alterada");
        assertThatThrownBy(() -> jdbc.update("delete from politica_sla_versao where politica_id = ?::uuid", POLITICA_PADRAO))
                .hasMessageContaining("não pode ser alterada");
        assertThatThrownBy(() -> jdbc.execute("truncate politica_sla_versao cascade")).hasMessageContaining("não pode ser alterada");
    }

    @Test
    void shouldAllowOnlyManager() throws Exception {
        for (String papel : new String[] {"TECNICO", "CLIENTE"}) {
            String outro = token(usuario(papel), papel);
            mvc.perform(get("/api/politicas").header(HttpHeaders.AUTHORIZATION, outro)).andExpect(status().isForbidden());
            mvc.perform(get("/api/politicas/" + POLITICA_PADRAO + "/versoes").header(HttpHeaders.AUTHORIZATION, outro))
                    .andExpect(status().isForbidden());
        }
    }

    private ResultActions criar(String nome, String prioridadePadrao, String prazos) throws Exception {
        return mvc.perform(post("/api/politicas").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"%s\",\"prioridadePadrao\":\"%s\",\"prazos\":%s}".formatted(nome, prioridadePadrao, prazos)));
    }

    private ResultActions novaVersao(String id, String prioridadePadrao, String prazos) throws Exception {
        return mvc.perform(post("/api/politicas/" + id + "/versoes").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prioridadePadrao\":\"%s\",\"prazos\":%s}".formatted(prioridadePadrao, prazos)));
    }

    // Número de versão de cada registro de auditoria da política, em ordem.
    private List<Integer> auditorias(String politicaId) {
        return jdbc.queryForList("""
                select (dados->>'versao')::int from auditoria
                where operacao = 'ALTERACAO_POLITICA' and entidade_tipo = 'POLITICA_SLA' and entidade_id = ?::uuid
                  and ator_sub = ?::uuid and perfil = 'GESTOR'
                order by 1""", Integer.class, politicaId, SUB_GESTOR);
    }

    private String usuario(String perfil) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo)
                values (?, now(), now(), ?, 'Usuário', ?, ?, true)""", UUID.randomUUID(), sub, sub + "@x.com", perfil);
        return sub.toString();
    }

    private static String nome() {
        return "Política " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String id(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
