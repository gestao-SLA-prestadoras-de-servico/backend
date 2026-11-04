package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static com.gestaosla.backend.TokenTeste.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UsuarioMeTest {

    @Autowired
    private MockMvc mvc;

    // SUB_GESTOR é o "id" do usuário "gestor" em infra/keycloak/realm-flowsla.json, gravado pela V2__cria_usuario.sql.
    @Test
    void shouldRecognizeInitialGestorBySub() throws Exception {
        mvc.perform(get("/api/usuarios/me").header(HttpHeaders.AUTHORIZATION, token(SUB_GESTOR, "GESTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(SUB_GESTOR))
                .andExpect(jsonPath("$.perfil").value("GESTOR"));
    }
}
