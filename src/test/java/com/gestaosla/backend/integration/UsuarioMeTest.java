package com.gestaosla.backend.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UsuarioMeTest {

    // "id" do usuário "gestor" em infra/keycloak/realm-flowsla.json, gravado pela V2__cria_usuario.sql.
    private static final String SUB_GESTOR = "0199a000-0000-7000-8000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Test
    void shouldRecognizeInitialGestorBySub() throws Exception {
        mvc.perform(get("/api/usuarios/me").with(jwt().jwt(j -> j.subject(SUB_GESTOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(SUB_GESTOR))
                .andExpect(jsonPath("$.perfil").value("GESTOR"));
    }

    @Test
    void shouldReturn404WhenSubHasNoLocalRecord() throws Exception {
        mvc.perform(get("/api/usuarios/me").with(jwt().jwt(j -> j.subject("0199a000-0000-7000-8000-0000000000ff"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RECURSO_NAO_ENCONTRADO"));
    }
}
