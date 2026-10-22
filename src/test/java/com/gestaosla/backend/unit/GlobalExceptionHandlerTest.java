package com.gestaosla.backend.unit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.gestaosla.backend.shared.internal.GlobalExceptionHandler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrosController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void shouldReturn400WithFieldErrorsOnValidation() throws Exception {
        problema(mvc.perform(post("/validacao").contentType(MediaType.APPLICATION_JSON).content("{\"titulo\":\"\"}")),
                400, "VALIDACAO")
                .andExpect(jsonPath("$.erros[0].campo").value("titulo"));
    }

    @Test
    void shouldReturn400OnMalformedBody() throws Exception {
        problema(mvc.perform(post("/validacao").contentType(MediaType.APPLICATION_JSON).content("{")), 400, "VALIDACAO");
    }

    @Test
    void shouldReturn401WhenNotAuthenticated() throws Exception {
        problema(mvc.perform(get("/nao-autenticado")), 401, "NAO_AUTENTICADO");
    }

    @Test
    void shouldReturn403WhenAccessDenied() throws Exception {
        problema(mvc.perform(get("/acesso-negado")), 403, "ACESSO_NEGADO");
    }

    @Test
    void shouldReturn404WithoutRevealingResourceOfAnotherClient() throws Exception {
        problema(mvc.perform(get("/outro-cliente")), 404, "RECURSO_NAO_ENCONTRADO")
                .andExpect(jsonPath("$.detail").value("Recurso não encontrado."));
    }

    @Test
    void shouldReturn404OnUnknownRoute() throws Exception {
        problema(mvc.perform(get("/rota-inexistente")), 404, "RECURSO_NAO_ENCONTRADO");
    }

    @Test
    void shouldReturn409WithClearMessageOnOptimisticLock() throws Exception {
        problema(mvc.perform(get("/conflito")), 409, "CONFLITO_VERSAO")
                .andExpect(jsonPath("$.detail").value(
                        "O registro foi alterado por outra operação. Recarregue os dados e tente novamente."));
    }

    @Test
    void shouldReturn422WithBusinessCode() throws Exception {
        problema(mvc.perform(get("/regra-negocio")), 422, "TRANSICAO_INVALIDA")
                .andExpect(jsonPath("$.detail").value("Chamado ENCERRADO não aceita transição."));
    }

    @Test
    void shouldReturn405AsInvalidRequest() throws Exception {
        problema(mvc.perform(post("/conflito")), 405, "REQUISICAO_INVALIDA");
    }

    @Test
    void shouldReturn500WithoutLeakingInternalMessage() throws Exception {
        problema(mvc.perform(get("/inesperado")), 500, "ERRO_INTERNO")
                .andExpect(jsonPath("$.detail").value("Erro interno. Tente novamente mais tarde."));
    }

    private static ResultActions problema(ResultActions resposta, int status, String codigo) throws Exception {
        return resposta
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.codigo").value(codigo));
    }

    record Pedido(@NotBlank String titulo) {
    }

    @RestController
    static class ErrosController {

        @PostMapping("/validacao")
        void validacao(@Valid @RequestBody Pedido pedido) {
        }

        @GetMapping("/nao-autenticado")
        void naoAutenticado() {
            throw new InsufficientAuthenticationException("sem token");
        }

        @GetMapping("/acesso-negado")
        void acessoNegado() {
            throw new AccessDeniedException("sem papel");
        }

        @GetMapping("/outro-cliente")
        void outroCliente() {
            throw new RecursoNaoEncontradoException();
        }

        @GetMapping("/conflito")
        void conflito() {
            throw new ObjectOptimisticLockingFailureException("Chamado", 1L);
        }

        @GetMapping("/regra-negocio")
        void regraNegocio() {
            throw new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA, "Chamado ENCERRADO não aceita transição.");
        }

        @GetMapping("/inesperado")
        void inesperado() {
            throw new IllegalStateException("senha do banco no stack trace");
        }
    }
}
