package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ContratoTest {

    private static final String GESTOR = token(SUB_GESTOR, "GESTOR");
    private static final LocalDate HOJE = LocalDate.of(2026, 6, 15);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Value("${app.fuso-horario}")
    private ZoneId fuso;

    @BeforeEach
    void meioDia() {
        relogio.definir(instante(HOJE, 12, 0, 0));
    }

    @Test
    void shouldCreateContract() throws Exception {
        UUID cliente = cliente(true);

        criar(cliente, "CT-" + UUID.randomUUID(), "2026-01-01", "2026-12-31")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clienteId").value(cliente.toString()))
                .andExpect(jsonPath("$.inicioVigencia").value("2026-01-01"))
                .andExpect(jsonPath("$.fimVigencia").value("2026-12-31"));
    }

    @Test
    void shouldReturn400WhenEndIsBeforeStart() throws Exception {
        criar(cliente(true), "CT-" + UUID.randomUUID(), "2026-02-01", "2026-01-31")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"))
                .andExpect(jsonPath("$.erros[0].campo").value("fimVigencia"));
    }

    @Test
    void shouldReturn409ForDuplicateCode() throws Exception {
        String codigo = "CT-" + UUID.randomUUID();
        criar(cliente(true), codigo, "2026-01-01", "2026-12-31").andExpect(status().isCreated());

        criar(cliente(true), codigo, "2026-01-01", "2026-12-31")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CODIGO_CONTRATO_JA_EXISTE"));
    }

    @Test
    void shouldReturn422ForUnknownOrInactiveClient() throws Exception {
        criar(UUID.randomUUID(), "CT-" + UUID.randomUUID(), "2026-01-01", "2026-12-31")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_INEXISTENTE"));
        criar(cliente(false), "CT-" + UUID.randomUUID(), "2026-01-01", "2026-12-31")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_INATIVO"));
    }

    // RN-06: vários contratos do mesmo cliente ao mesmo tempo.
    @Test
    void shouldListFilteringByClientAndInForce() throws Exception {
        UUID cliente = cliente(true);
        String vigente1 = id(criar(cliente, "A-" + UUID.randomUUID(), "2026-01-01", "2026-12-31"));
        String vigente2 = id(criar(cliente, "B-" + UUID.randomUUID(), "2026-06-15", "2026-06-15"));
        String vencido = id(criar(cliente, "C-" + UUID.randomUUID(), "2025-01-01", "2026-06-14"));
        String futuro = id(criar(cliente, "D-" + UUID.randomUUID(), "2026-06-16", "2027-06-15"));
        criar(cliente(true), "E-" + UUID.randomUUID(), "2026-01-01", "2026-12-31");

        listar("clienteId", cliente.toString())
                .andExpect(jsonPath("$.content[*].id").value(Matchers.contains(vigente1, vigente2, vencido, futuro)))
                .andExpect(jsonPath("$.page.totalElements").value(4));
        listar("clienteId", cliente.toString(), "vigente", "true")
                .andExpect(jsonPath("$.content[*].id").value(Matchers.contains(vigente1, vigente2)));
        listar("clienteId", cliente.toString(), "vigente", "false")
                .andExpect(jsonPath("$.content[*].id").value(Matchers.contains(vencido, futuro)));
    }

    @Test
    void shouldShowClientOnlyInForceContractsOfOwnCompany() throws Exception {
        UUID empresa = cliente(true);
        String vigente = id(criar(empresa, "A-" + UUID.randomUUID(), "2026-01-01", "2026-12-31"));
        criar(empresa, "B-" + UUID.randomUUID(), "2025-01-01", "2025-12-31");
        criar(cliente(true), "C-" + UUID.randomUUID(), "2026-01-01", "2026-12-31");

        // Parâmetro de outro cliente é ignorado: a empresa vem do usuário autenticado (RN-126).
        mvc.perform(get("/api/me/contratos").param("clienteId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, token(usuarioCliente(empresa), "CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(Matchers.contains(vigente)))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    // O contrato vale até 23:59:59 do último dia, no fuso do sistema.
    @Test
    void shouldStayInForceUntilEndOfLastDayInSystemTimeZone() throws Exception {
        UUID empresa = cliente(true);
        String id = id(criar(empresa, "CT-" + UUID.randomUUID(), "2026-01-01", HOJE.toString()));
        String cliente = token(usuarioCliente(empresa), "CLIENTE");

        relogio.definir(instante(HOJE, 23, 59, 59));
        mvc.perform(get("/api/me/contratos").header(HttpHeaders.AUTHORIZATION, cliente))
                .andExpect(jsonPath("$.content[*].id").value(Matchers.contains(id)));

        relogio.definir(instante(HOJE.plusDays(1), 0, 0, 0));
        mvc.perform(get("/api/me/contratos").header(HttpHeaders.AUTHORIZATION, cliente))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void shouldAllowOnlyManagerOnContractsAndOnlyClientOnMyContracts() throws Exception {
        String tecnico = token(usuario("TECNICO", null), "TECNICO");
        String cliente = token(usuarioCliente(cliente(true)), "CLIENTE");

        for (String naoGestor : new String[] {tecnico, cliente}) {
            mvc.perform(get("/api/contratos").header(HttpHeaders.AUTHORIZATION, naoGestor)).andExpect(status().isForbidden());
            mvc.perform(post("/api/contratos").header(HttpHeaders.AUTHORIZATION, naoGestor)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/me/contratos").header(HttpHeaders.AUTHORIZATION, tecnico)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/contratos").header(HttpHeaders.AUTHORIZATION, GESTOR)).andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectEndBeforeStartInDatabase() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into contrato (id, created_at, updated_at, cliente_id, codigo, inicio_vigencia, fim_vigencia)
                values (?, now(), now(), ?, ?, date '2026-02-01', date '2026-01-31')""",
                UUID.randomUUID(), cliente(true), "CT-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("contrato_vigencia_ck");
    }

    private ResultActions criar(UUID clienteId, String codigo, String inicio, String fim) throws Exception {
        return mvc.perform(post("/api/contratos").header(HttpHeaders.AUTHORIZATION, GESTOR)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"clienteId":"%s","codigo":"%s","inicioVigencia":"%s","fimVigencia":"%s"}"""
                        .formatted(clienteId, codigo, inicio, fim)));
    }

    private ResultActions listar(String... parametros) throws Exception {
        var requisicao = get("/api/contratos").header(HttpHeaders.AUTHORIZATION, GESTOR);
        for (int i = 0; i < parametros.length; i += 2) {
            requisicao.param(parametros[i], parametros[i + 1]);
        }
        return mvc.perform(requisicao).andExpect(status().isOk());
    }

    private static String id(ResultActions resposta) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Instant instante(LocalDate dia, int hora, int minuto, int segundo) {
        return LocalDateTime.of(dia.getYear(), dia.getMonth(), dia.getDayOfMonth(), hora, minuto, segundo).atZone(fuso).toInstant();
    }

    private UUID cliente(boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (id, created_at, updated_at, razao_social, email_contato, logradouro, numero, bairro,
                                     cidade, uf, cep, ativo, version)
                values (?, now(), now(), 'Cliente', 'c@x.com', 'Rua A', '1', 'Centro', 'Fortaleza', 'CE', '60000000', ?, 0)""",
                id, ativo);
        return id;
    }

    private String usuarioCliente(UUID empresa) {
        return usuario("CLIENTE", empresa);
    }

    private String usuario(String perfil, UUID empresa) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id)
                values (?, now(), now(), ?, 'Usuário', ?, ?, true, ?)""", UUID.randomUUID(), sub, sub + "@x.com", perfil, empresa);
        return sub.toString();
    }
}
