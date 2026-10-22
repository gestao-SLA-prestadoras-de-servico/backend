package com.gestaosla.backend.shared.internal;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;

/**
 * Qualquer erro da API sai como Problem Details (RFC 9457) com o campo {@code codigo} ({@link CodigoErro}).
 * Os erros do próprio Spring MVC vêm da superclasse; aqui só se acrescenta o código.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler
    ResponseEntity<ProblemDetail> regraNegocio(RegraNegocioException ex) {
        return problema(HttpStatus.UNPROCESSABLE_CONTENT, ex.getCodigo(), ex.getMessage());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> naoEncontrado(RecursoNaoEncontradoException ex) {
        return problema(HttpStatus.NOT_FOUND, CodigoErro.RECURSO_NAO_ENCONTRADO, ex.getMessage());
    }

    // Cobre ObjectOptimisticLockingFailureException (RN-117).
    @ExceptionHandler
    ResponseEntity<ProblemDetail> conflitoVersao(OptimisticLockingFailureException ex) {
        return problema(HttpStatus.CONFLICT, CodigoErro.CONFLITO_VERSAO,
                "O registro foi alterado por outra operação. Recarregue os dados e tente novamente.");
    }

    // Chegam aqui também os erros dos filtros de segurança (SegurancaErrosConfig).
    @ExceptionHandler
    ResponseEntity<ProblemDetail> naoAutenticado(AuthenticationException ex) {
        return problema(HttpStatus.UNAUTHORIZED, CodigoErro.NAO_AUTENTICADO, "Autenticação necessária.");
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> acessoNegado(AccessDeniedException ex) {
        return problema(HttpStatus.FORBIDDEN, CodigoErro.ACESSO_NEGADO, "Sem permissão para esta operação.");
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> inesperado(Exception ex) {
        log.error("Erro não tratado", ex);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, CodigoErro.ERRO_INTERNO, "Erro interno. Tente novamente mais tarde.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> erros = ex.getFieldErrors().stream()
                .map(erro -> Map.of("campo", erro.getField(), "mensagem", String.valueOf(erro.getDefaultMessage())))
                .toList();
        ex.getBody().setDetail("Um ou mais campos são inválidos.");
        ex.getBody().setProperty("erros", erros);
        return handleExceptionInternal(ex, ex.getBody(), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Para alguns erros (404, 405) o body chega nulo e só a superclasse cria o ProblemDetail.
        ResponseEntity<Object> resposta = super.handleExceptionInternal(ex, body, headers, status, request);
        if (resposta != null && resposta.getBody() instanceof ProblemDetail problema) {
            problema.setProperty("codigo", codigoPara(status));
        }
        return resposta;
    }

    private static CodigoErro codigoPara(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> CodigoErro.VALIDACAO;
            case 404 -> CodigoErro.RECURSO_NAO_ENCONTRADO;
            default -> status.is5xxServerError() ? CodigoErro.ERRO_INTERNO : CodigoErro.REQUISICAO_INVALIDA;
        };
    }

    private static ResponseEntity<ProblemDetail> problema(HttpStatus status, CodigoErro codigo, String mensagem) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, mensagem);
        problema.setProperty("codigo", codigo);
        return ResponseEntity.status(status).body(problema);
    }
}
