package com.gestaosla.backend.shared.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gestaosla.backend.shared.api.RespostasErro;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Contrato OpenAPI do app-principal: login no Keycloak (Authorization Code + PKCE), uma tag por módulo e as respostas de
 * erro em Problem Details (docs/erros-api.md). Endpoints referenciam as respostas pelos nomes de {@link RespostasErro}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String NAO_AUTENTICADO = "NaoAutenticado";
    private static final String ACESSO_NEGADO = "AcessoNegado";
    private static final String ERRO_INTERNO = "ErroInterno";
    private static final String KEYCLOAK = "keycloak";

    @Bean
    OpenAPI openApi(@Value("${app.openapi.keycloak-realm-url}") String realm) {
        String oidc = realm + "/protocol/openid-connect";
        return new OpenAPI()
                .info(new Info().title("FlowSLA — app-principal").version("v1")
                        .description("API REST do app-principal. Todo erro sai como Problem Details (RFC 9457) com o campo "
                                + "`codigo`; o catálogo está em docs/erros-api.md."))
                .servers(List.of(new Server().url("/")))
                .tags(List.of(
                        new Tag().name("Identidade").description("Usuários, perfis e autenticação"),
                        new Tag().name("Cadastro").description("Clientes, contratos e políticas de SLA")))
                .addSecurityItem(new SecurityRequirement().addList(KEYCLOAK))
                .components(new Components()
                        .addSecuritySchemes(KEYCLOAK, new SecurityScheme().type(SecurityScheme.Type.OAUTH2)
                                .description("Login no Keycloak (realm flowsla). No Swagger UI: Authorize → client flowsla-swagger.")
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(oidc + "/auth")
                                        .tokenUrl(oidc + "/token")
                                        .scopes(new Scopes().addString("openid", "Identidade do usuário")))))
                        .addSchemas("Problema", problema())
                        .addResponses(RespostasErro.VALIDACAO, erro("Requisição inválida", 400, "VALIDACAO", "Um ou mais campos são inválidos.",
                                List.of(Map.of("campo", "emailContato", "mensagem", "must be a well-formed email address"))))
                        .addResponses(NAO_AUTENTICADO, erro("Sem token, token inválido ou usuário inativo", 401, "NAO_AUTENTICADO",
                                "Autenticação necessária.", null))
                        .addResponses(ACESSO_NEGADO, erro("Papel sem permissão para a operação", 403, "ACESSO_NEGADO",
                                "Sem permissão para esta operação.", null))
                        .addResponses(RespostasErro.NAO_ENCONTRADO, erro("Recurso inexistente ou de outro cliente", 404, "RECURSO_NAO_ENCONTRADO",
                                "Recurso não encontrado.", null))
                        .addResponses(RespostasErro.CONFLITO_VERSAO, erro("Registro alterado por outra operação (RN-117)", 409, "CONFLITO_VERSAO",
                                "O registro foi alterado por outra operação. Recarregue os dados e tente novamente.", null))
                        .addResponses(RespostasErro.CONFLITO, erro("Conflito com registro existente", 409, "USUARIO_JA_EXISTE",
                                "Já existe uma conta com este username ou e-mail.", null))
                        .addResponses(RespostasErro.REGRA_NEGOCIO, erro("Regra de negócio violada (ver catálogo)", 422, "TRANSICAO_INVALIDA",
                                "Chamado ENCERRADO não aceita transição.", null))
                        .addResponses(RespostasErro.SERVICO_INDISPONIVEL, erro("Serviço externo fora do ar ou lento (RN-137)", 503,
                                "SERVICO_INDISPONIVEL", "Serviço temporariamente indisponível. Tente novamente em instantes.", null))
                        .addResponses(ERRO_INTERNO, erro("Erro inesperado", 500, "ERRO_INTERNO",
                                "Erro interno. Tente novamente mais tarde.", null)));
    }

    // Respostas que qualquer endpoint autenticado pode dar; 400 só onde há corpo ou parâmetros.
    @Bean
    GlobalOpenApiCustomizer errosComuns() {
        return api -> api.getPaths().values().forEach(caminho -> caminho.readOperations().forEach(operacao -> {
            if (operacao.getRequestBody() != null || (operacao.getParameters() != null && !operacao.getParameters().isEmpty())) {
                adicionar(operacao, "400", RespostasErro.VALIDACAO);
            }
            adicionar(operacao, "401", NAO_AUTENTICADO);
            adicionar(operacao, "403", ACESSO_NEGADO);
            adicionar(operacao, "500", ERRO_INTERNO);
        }));
    }

    private static void adicionar(Operation operacao, String status, String resposta) {
        operacao.getResponses().putIfAbsent(status, new ApiResponse().$ref("#/components/responses/" + resposta));
    }

    @SuppressWarnings("rawtypes")
    private static Schema problema() {
        return new ObjectSchema()
                .description("Problem Details (RFC 9457) com o código estável do erro")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Bad Request"))
                .addProperty("status", new IntegerSchema().example(400))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().example("/api/clientes"))
                .addProperty("codigo", new StringSchema().description("Ver docs/erros-api.md"))
                .addProperty("erros", new ArraySchema().description("Só em VALIDACAO: um item por campo")
                        .items(new ObjectSchema().addProperty("campo", new StringSchema()).addProperty("mensagem", new StringSchema())));
    }

    private static ApiResponse erro(String descricao, int status, String codigo, String detalhe, Object erros) {
        Map<String, Object> exemplo = new LinkedHashMap<>();
        exemplo.put("type", "about:blank");
        exemplo.put("status", status);
        exemplo.put("detail", detalhe);
        exemplo.put("codigo", codigo);
        if (erros != null) {
            exemplo.put("erros", erros);
        }
        return new ApiResponse().description(descricao).content(new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problema")).addExamples(codigo,
                        new Example().value(exemplo))));
    }
}
