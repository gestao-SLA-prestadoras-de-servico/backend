package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PreferenciaNotificacaoTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext contexto;

    private UUID chamado;
    private String gestorB;
    private String subB;

    @BeforeEach
    void preparar() {
        chamado = ChamadoTeste.chamado(jdbc);
        subB = ChamadoTeste.usuario(jdbc, "GESTOR", null);
        gestorB = token(subB, "GESTOR");
    }

    // RF-56: o gestor ativa e desativa a própria preferência; cada mudança é auditada.
    @Test
    void shouldActivateAndDeactivateOwnPreference() throws Exception {
        ativar(token(SUB_GESTOR, "GESTOR")).andExpect(status().isNoContent());
        detalhe(token(SUB_GESTOR, "GESTOR")).andExpect(jsonPath("$.notificarConclusao").value(true));

        mvc.perform(delete(rota()).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))).andExpect(status().isNoContent());

        detalhe(token(SUB_GESTOR, "GESTOR")).andExpect(jsonPath("$.notificarConclusao").value(false));
        assertThat(jdbc.queryForList("""
                select dados->>'ativa' from auditoria where chamado_id = ? and operacao = 'ALTERACAO_PREFERENCIA_NOTIFICACAO'
                and entidade_tipo = 'PREFERENCIA_NOTIFICACAO' order by ocorrido_em, id""", String.class, chamado))
                .containsExactlyInAnyOrder("true", "false");
    }

    // Ativar duas vezes não gera erro, nem linha nem auditoria a mais; desativar o que não existe também não.
    @Test
    void shouldBeIdempotent() throws Exception {
        ativar(gestorB).andExpect(status().isNoContent());
        ativar(gestorB).andExpect(status().isNoContent());
        mvc.perform(delete(rota()).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from preferencia_notificacao where chamado_id = ?", Long.class, chamado)).isEqualTo(1);
        assertThat(auditorias()).isEqualTo(1);
    }

    // RN-40: a preferência é individual; a do gestor A não afeta a do B.
    @Test
    void shouldKeepPreferencesIndependentPerManager() throws Exception {
        ativar(token(SUB_GESTOR, "GESTOR")).andExpect(status().isNoContent());

        detalhe(gestorB).andExpect(jsonPath("$.notificarConclusao").value(false));
        assertThat(new ChamadoTestHelper(contexto).gestoresComPreferencia(chamado)).containsExactly(UUID.fromString(SUB_GESTOR));

        ativar(gestorB).andExpect(status().isNoContent());
        mvc.perform(delete(rota()).header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR"))).andExpect(status().isNoContent());

        detalhe(gestorB).andExpect(jsonPath("$.notificarConclusao").value(true));
        assertThat(new ChamadoTestHelper(contexto).gestoresComPreferencia(chamado)).containsExactly(UUID.fromString(subB));
    }

    @Test
    void shouldAnswer404ForUnknownChamadoAndDenyOtherProfiles() throws Exception {
        mvc.perform(put("/api/chamados/" + UUID.randomUUID() + "/preferencia-notificacao")
                .header(HttpHeaders.AUTHORIZATION, gestorB)).andExpect(status().isNotFound());
        UUID empresa = jdbc.queryForObject("select cliente_id from chamado where id = ?", UUID.class, chamado);
        for (String quem : List.of(token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE"),
                token(ChamadoTeste.usuario(jdbc, "TECNICO", null), "TECNICO"))) {
            ativar(quem).andExpect(status().isForbidden());
            mvc.perform(delete(rota()).header(HttpHeaders.AUTHORIZATION, quem)).andExpect(status().isForbidden());
        }
        assertThat(auditorias()).isZero();
    }

    // Só o gestor tem a preferência no detalhe; o cliente não recebe o campo.
    @Test
    void shouldNotExposePreferenceToClient() throws Exception {
        UUID empresa = jdbc.queryForObject("select cliente_id from chamado where id = ?", UUID.class, chamado);
        detalhe(token(ChamadoTeste.usuario(jdbc, "CLIENTE", empresa), "CLIENTE"))
                .andExpect(jsonPath("$.notificarConclusao").doesNotExist());
    }

    private ResultActions ativar(String quem) throws Exception {
        return mvc.perform(put(rota()).header(HttpHeaders.AUTHORIZATION, quem));
    }

    private ResultActions detalhe(String quem) throws Exception {
        return mvc.perform(get("/api/chamados/" + chamado).header(HttpHeaders.AUTHORIZATION, quem)).andExpect(status().isOk());
    }

    private String rota() {
        return "/api/chamados/" + chamado + "/preferencia-notificacao";
    }

    private long auditorias() {
        return jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'ALTERACAO_PREFERENCIA_NOTIFICACAO'",
                Long.class, chamado);
    }
}
