package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prazos;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PoliticaContratoTest {

    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");
    private static final UUID POLITICA_PADRAO = UUID.fromString("0199a000-0000-7000-8000-00000000c001");
    private static final UUID VERSAO_PADRAO = UUID.fromString("0199a000-0000-7000-8000-00000000c002");
    private static final String PRAZOS = """
            {"alta":{"resposta":60,"campo":300,"resolucao":900},"normal":{"resposta":300,"campo":900,"resolucao":1800}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContratoConsulta consulta;

    @Test
    void shouldLinkPolicyAndExposeItsCurrentVersion() throws Exception {
        UUID contrato = contrato();

        vincular(contrato, POLITICA_PADRAO).andExpect(status().isOk())
                .andExpect(jsonPath("$.politicaId").value(POLITICA_PADRAO.toString()));

        PoliticaDoContrato vigente = consulta.versaoVigenteDaPolitica(contrato);
        assertThat(vigente.politicaId()).isEqualTo(POLITICA_PADRAO);
        assertThat(vigente.versaoId()).isEqualTo(VERSAO_PADRAO);
        assertThat(vigente.prioridadePadrao()).isEqualTo(Prioridade.NORMAL);
        assertThat(vigente.prazos(Prioridade.ALTA)).isEqualTo(new Prazos(120, 540, 1080));
        assertThat(vigente.prazos(Prioridade.NORMAL)).isEqualTo(new Prazos(540, 1620, 2700));
    }

    // RN-02 e RN-05: trocar substitui a política (nunca duas) e não mexe na versão que um chamado já guardou.
    @Test
    void shouldReplacePolicyAffectingOnlyLaterLookups() throws Exception {
        UUID contrato = contrato();
        vincular(contrato, POLITICA_PADRAO);
        PoliticaDoContrato guardadaNaAbertura = consulta.versaoVigenteDaPolitica(contrato);
        Map<String, Object> versaoAntes = versao(guardadaNaAbertura.versaoId());
        UUID ouro = politica();

        vincular(contrato, ouro).andExpect(status().isOk()).andExpect(jsonPath("$.politicaId").value(ouro.toString()));

        assertThat(jdbc.queryForList("select politica_id from contrato where id = ?", UUID.class, contrato)).containsExactly(ouro);
        PoliticaDoContrato depois = consulta.versaoVigenteDaPolitica(contrato);
        assertThat(depois.politicaId()).isEqualTo(ouro);
        assertThat(depois.prazos(Prioridade.ALTA)).isEqualTo(new Prazos(60, 300, 900));
        assertThat(versao(guardadaNaAbertura.versaoId())).isEqualTo(versaoAntes);
    }

    // O contrato aponta para a política: a versão nova vale para os próximos chamados, sem novo vínculo.
    @Test
    void shouldFollowNewVersionOfLinkedPolicy() throws Exception {
        UUID contrato = contrato();
        UUID politica = politica();
        vincular(contrato, politica);
        UUID v1 = consulta.versaoVigenteDaPolitica(contrato).versaoId();

        mvc.perform(post("/api/politicas/" + politica + "/versoes").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prioridadePadrao\":\"ALTA\",\"prazos\":" + PRAZOS + "}"))
                .andExpect(status().isCreated());

        PoliticaDoContrato v2 = consulta.versaoVigenteDaPolitica(contrato);
        assertThat(v2.numero()).isEqualTo(2);
        assertThat(v2.versaoId()).isNotEqualTo(v1);
        assertThat(v2.prioridadePadrao()).isEqualTo(Prioridade.ALTA);
    }

    @Test
    void shouldAuditEachChangeButNotRelinkingSamePolicy() throws Exception {
        UUID contrato = contrato();
        UUID ouro = politica();

        vincular(contrato, POLITICA_PADRAO);
        vincular(contrato, POLITICA_PADRAO).andExpect(status().isOk());
        vincular(contrato, ouro);

        assertThat(jdbc.queryForList("""
                select concat(dados->>'politicaAnterior', '>', dados->>'politicaNova') from auditoria
                where operacao = 'ALTERACAO_POLITICA' and entidade_tipo = 'CONTRATO' and entidade_id = ?
                  and ator_sub = ?::uuid and perfil = 'GESTOR'
                order by ocorrido_em, dados->>'politicaAnterior' nulls first""", String.class, contrato, SUB_GESTOR))
                .containsExactly(">" + POLITICA_PADRAO, POLITICA_PADRAO + ">" + ouro);
    }

    // RN-03: contrato sem política não recebe chamados; o chamado descobre pela consulta.
    @Test
    void shouldRejectLookupOfContractWithoutPolicy() {
        UUID contrato = contrato();

        assertThatThrownBy(() -> consulta.versaoVigenteDaPolitica(contrato))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo(CodigoErro.CONTRATO_SEM_POLITICA);
        assertThatThrownBy(() -> consulta.versaoVigenteDaPolitica(UUID.randomUUID()))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void shouldReturn404ForUnknownContractAnd422ForUnknownPolicy() throws Exception {
        vincular(UUID.randomUUID(), POLITICA_PADRAO).andExpect(status().isNotFound());
        UUID contrato = contrato();
        vincular(contrato, UUID.randomUUID())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("POLITICA_INEXISTENTE"));
        mvc.perform(put("/api/contratos/" + contrato + "/politica").header(HttpHeaders.AUTHORIZATION, GESTOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erros[0].campo").value("politicaId"));
    }

    @Test
    void shouldShowPolicyInContractListing() throws Exception {
        UUID contrato = contrato();
        vincular(contrato, POLITICA_PADRAO);
        UUID cliente = jdbc.queryForObject("select cliente_id from contrato where id = ?", UUID.class, contrato);

        mvc.perform(get("/api/contratos").param("clienteId", cliente.toString()).header(HttpHeaders.AUTHORIZATION, GESTOR))
                .andExpect(jsonPath("$.content[0].politicaId").value(POLITICA_PADRAO.toString()));
    }

    @Test
    void shouldAllowOnlyManagerToLinkPolicy() throws Exception {
        UUID contrato = contrato();
        for (String papel : List.of("TECNICO", "CLIENTE")) {
            mvc.perform(put("/api/contratos/" + contrato + "/politica").header(HttpHeaders.AUTHORIZATION, token(usuario(papel), papel))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"politicaId\":\"" + POLITICA_PADRAO + "\"}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select politica_id from contrato where id = ?", UUID.class, contrato)).isNull();
    }

    private ResultActions vincular(UUID contrato, UUID politica) throws Exception {
        return mvc.perform(put("/api/contratos/" + contrato + "/politica").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"politicaId\":\"" + politica + "\"}"));
    }

    private Map<String, Object> versao(UUID id) {
        return jdbc.queryForMap("select * from politica_sla_versao where id = ?", id);
    }

    private UUID politica() throws Exception {
        String corpo = mvc.perform(post("/api/politicas").header(HttpHeaders.AUTHORIZATION, GESTOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Ouro %s\",\"prioridadePadrao\":\"NORMAL\",\"prazos\":%s}"
                                .formatted(UUID.randomUUID(), PRAZOS)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(corpo, "$.id"));
    }

    private UUID contrato() {
        UUID cliente = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (id, created_at, updated_at, razao_social, email_contato, logradouro, numero, bairro,
                                     cidade, uf, cep, ativo, version)
                values (?, now(), now(), 'Cliente', 'c@x.com', 'Rua A', '1', 'Centro', 'Fortaleza', 'CE', '60000000', true, 0)""",
                cliente);
        UUID contrato = UUID.randomUUID();
        jdbc.update("""
                insert into contrato (id, created_at, updated_at, cliente_id, codigo, inicio_vigencia, fim_vigencia)
                values (?, now(), now(), ?, ?, date '2026-01-01', date '2026-12-31')""", contrato, cliente, "CT-" + contrato);
        return contrato;
    }

    private String usuario(String perfil) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo)
                values (?, now(), now(), ?, 'Usuário', ?, ?, true)""", UUID.randomUUID(), sub, sub + "@x.com", perfil);
        return sub.toString();
    }
}
