package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

/**
 * O contrato versionado em docs/openapi.json é o que o frontend usa para gerar o cliente HTTP. Este teste o gera a partir
 * do código e falha se o arquivo do repositório estiver diferente: o arquivo é reescrito para o diff entrar no PR.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpenApiContratoTest {

    private static final Path CONTRATO = Path.of("docs/openapi.json");

    @Autowired
    private MockMvc mvc;

    private String gerado;

    @BeforeEach
    void gerar() throws Exception {
        gerado = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8) + "\n";
    }

    @Test
    void shouldMatchVersionedContract() throws Exception {
        String versionado = Files.exists(CONTRATO) ? Files.readString(CONTRATO) : "";
        if (!gerado.equals(versionado)) {
            Files.writeString(CONTRATO, gerado);
            fail("docs/openapi.json estava desatualizado e foi regenerado. Confira o diff e commite junto com a mudança.");
        }
    }

    @Test
    void shouldListErrorResponsesOnEveryOperation() {
        List<Map<String, Object>> respostas = JsonPath.read(gerado, "$.paths.*.*.responses");
        assertThat(respostas).isNotEmpty().allSatisfy(r -> assertThat(r).containsKeys("401", "403", "500"));
        assertThat(JsonPath.<Map<String, Object>>read(gerado, "$.paths['/api/clientes/{id}'].put.responses"))
                .containsKeys("200", "400", "404", "409");
        assertThat(JsonPath.<String>read(gerado, "$.components.responses.ConflitoVersao.content['application/problem+json'].examples.CONFLITO_VERSAO.value.codigo"))
                .isEqualTo("CONFLITO_VERSAO");
    }

    @Test
    void shouldAuthenticateWithKeycloakPkceAndTagByModule() {
        assertThat(JsonPath.<String>read(gerado, "$.components.securitySchemes.keycloak.flows.authorizationCode.authorizationUrl"))
                .isEqualTo("http://localhost:8081/realms/flowsla/protocol/openid-connect/auth");
        assertThat(JsonPath.<List<String>>read(gerado, "$.tags[*].name")).contains("Identidade", "Cadastro");
        assertThat(JsonPath.<List<String>>read(gerado, "$.paths['/api/clientes'].post.tags")).containsExactly("Cadastro");
    }
}
