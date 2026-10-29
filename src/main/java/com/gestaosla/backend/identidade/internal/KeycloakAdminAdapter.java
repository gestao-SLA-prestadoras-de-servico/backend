package com.gestaosla.backend.identidade.internal;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gestaosla.backend.identidade.api.IdpIndisponivelException;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.identidade.api.NovoUsuarioKeycloak;
import com.gestaosla.backend.identidade.api.Papel;
import com.gestaosla.backend.identidade.api.UsuarioJaExisteException;
import com.gestaosla.backend.identidade.api.UsuarioKeycloak;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

import io.github.resilience4j.retry.annotation.Retry;

/**
 * Único ponto de acesso à Admin API do Keycloak (RN-127, RN-131, RN-137). Timeouts no {@link KeycloakAdminConfig};
 * retry ({@code resilience4j.retry.instances.keycloakAdmin}) só nas operações idempotentes e só em
 * {@link IdpIndisponivelException}. Tradução das falhas: rede, timeout e 5xx → {@link IdpIndisponivelException} (503);
 * 409 → {@link UsuarioJaExisteException}; 404 → {@link RecursoNaoEncontradoException}; outro 4xx (segredo errado,
 * permissão faltando) → {@link IllegalStateException} (500, erro de configuração, sem retry).
 */
@Component
class KeycloakAdminAdapter implements KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminAdapter.class);
    private static final String RETRY = "keycloakAdmin";

    private final RestClient http;
    private final KeycloakAdminProperties properties;
    private final Clock clock;
    private final UsuarioRepository usuarios;
    private TokenEmCache token;

    KeycloakAdminAdapter(RestClient keycloakAdminRestClient, KeycloakAdminProperties properties, Clock clock,
            UsuarioRepository usuarios) {
        this.http = keycloakAdminRestClient;
        this.properties = properties;
        this.clock = clock;
        this.usuarios = usuarios;
    }

    /**
     * Sem retry cego. Depois de uma falha (ex.: timeout com a conta já gravada), confere pelo username antes de fazer
     * uma única nova tentativa. Conta com o mesmo username e sem registro local (sobra de um cadastro que não
     * terminou: timeout, compensação que falhou) é reaproveitada e atualizada com os dados novos; com registro local,
     * é 409. No máximo 5 chamadas à Admin API, cada uma limitada pelo read-timeout.
     */
    @Override
    public UUID criarUsuario(NovoUsuarioKeycloak usuario) {
        try {
            return postarOuReaproveitar(usuario);
        } catch (IdpIndisponivelException falha) {
            log.warn("Falha ao criar '{}' no Keycloak; conferindo se a conta foi gravada antes de tentar de novo.",
                    usuario.username(), falha);
            Optional<UsuarioKeycloak> gravada = consultar(usuario.username());
            return gravada.isPresent() ? reaproveitar(gravada.get(), usuario) : postarOuReaproveitar(usuario);
        }
    }

    // 409 por e-mail de outra conta (username diferente) não acha nada na busca e continua 409.
    private UUID postarOuReaproveitar(NovoUsuarioKeycloak usuario) {
        try {
            return postarUsuario(usuario);
        } catch (UsuarioJaExisteException conflito) {
            return consultar(usuario.username()).map(conta -> reaproveitar(conta, usuario)).orElseThrow(() -> conflito);
        }
    }

    private UUID reaproveitar(UsuarioKeycloak conta, NovoUsuarioKeycloak usuario) {
        if (usuarios.existsBySub(conta.id())) {
            throw new UsuarioJaExisteException();
        }
        log.info("Reaproveitando a conta {} ('{}'), que existia no Keycloak sem registro local.", conta.id(), conta.username());
        Map<String, Object> dados = new HashMap<>();
        dados.put("email", usuario.email());
        dados.put("firstName", usuario.firstName());
        dados.put("lastName", usuario.lastName());
        dados.put("enabled", true);
        dados.put("emailVerified", true);
        atualizar(conta.id(), dados);
        return conta.id();
    }

    @Override
    @Retry(name = RETRY)
    public void atribuirPapel(UUID userId, Papel papel) {
        PapelKeycloak representacao = chamar(token -> http.get()
                .uri("/admin/realms/{realm}/roles/{papel}", properties.realm(), papel.name())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(PapelKeycloak.class));
        // Repetir o mapeamento de um papel já atribuído não dá erro no Keycloak.
        chamar(token -> http.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.realm(), userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(representacao))
                .retrieve()
                .toBodilessEntity());
    }

    // Sem retry: repetir dispararia um segundo e-mail (RN-138).
    @Override
    public void enviarDefinicaoSenha(UUID userId) {
        chamar(token -> http.put()
                .uri("/admin/realms/{realm}/users/{id}/execute-actions-email?client_id=flowsla-frontend",
                        properties.realm(), userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of("UPDATE_PASSWORD"))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    @Retry(name = RETRY)
    public void desabilitar(UUID userId) {
        alterarEnabled(userId, false);
    }

    @Override
    @Retry(name = RETRY)
    public void reabilitar(UUID userId) {
        alterarEnabled(userId, true);
    }

    // Compensação (RN-136): conta que já não existe conta como removida.
    @Override
    @Retry(name = RETRY)
    public void remover(UUID userId) {
        try {
            chamar(token -> http.delete()
                    .uri("/admin/realms/{realm}/users/{id}", properties.realm(), userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity());
        } catch (RecursoNaoEncontradoException jaRemovida) {
            log.debug("Conta {} já não existe no Keycloak.", userId);
        }
    }

    @Override
    @Retry(name = RETRY)
    public Optional<UsuarioKeycloak> buscarPorUsername(String username) {
        return consultar(username);
    }

    // Sem @Retry de propósito: usado dentro de criarUsuario, que já controla as próprias tentativas.
    private Optional<UsuarioKeycloak> consultar(String username) {
        List<UsuarioKeycloakJson> encontrados = chamar(token -> http.get()
                .uri("/admin/realms/{realm}/users?username={username}&exact=true", properties.realm(), username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<UsuarioKeycloakJson>>() {
                }));
        return encontrados == null || encontrados.isEmpty()
                ? Optional.empty()
                : Optional.of(encontrados.getFirst().paraUsuario());
    }

    private UUID postarUsuario(NovoUsuarioKeycloak usuario) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("username", usuario.username());
        corpo.put("email", usuario.email());
        corpo.put("firstName", usuario.firstName());
        corpo.put("lastName", usuario.lastName());
        corpo.put("enabled", true);
        corpo.put("emailVerified", true);
        URI location = chamar(token -> http.post()
                .uri("/admin/realms/{realm}/users", properties.realm())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(corpo)
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation());
        if (location == null) {
            throw new IllegalStateException("Keycloak criou a conta sem devolver o Location.");
        }
        String caminho = location.getPath();
        return UUID.fromString(caminho.substring(caminho.lastIndexOf('/') + 1));
    }

    private void alterarEnabled(UUID userId, boolean enabled) {
        atualizar(userId, Map.of("enabled", enabled));
    }

    // PUT parcial: o Keycloak só altera os campos enviados.
    private void atualizar(UUID userId, Map<String, Object> campos) {
        chamar(token -> http.put()
                .uri("/admin/realms/{realm}/users/{id}", properties.realm(), userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(campos)
                .retrieve()
                .toBodilessEntity());
    }

    // Executa com o token da conta de serviço; se o Keycloak recusar o token (revogado), renova uma vez.
    private <T> T chamar(Function<String, T> requisicao) {
        try {
            try {
                return requisicao.apply(token());
            } catch (HttpClientErrorException.Unauthorized tokenRecusado) {
                invalidarToken();
                return requisicao.apply(token());
            }
        } catch (RestClientException e) {
            throw traduzir(e);
        }
    }

    private synchronized String token() {
        if (token == null || !Instant.now(clock).isBefore(token.expiraEm())) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());
            RespostaToken resposta;
            try {
                resposta = http.post()
                        .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(RespostaToken.class);
            } catch (RestClientException e) {
                throw traduzir(e);
            }
            // Renova um pouco antes de expirar, para o token não vencer no meio de uma chamada.
            long margem = Math.min(30, resposta.expiresIn() / 2);
            token = new TokenEmCache(resposta.accessToken(), Instant.now(clock).plusSeconds(resposta.expiresIn() - margem));
        }
        return token.valor();
    }

    private synchronized void invalidarToken() {
        token = null;
    }

    private static RuntimeException traduzir(RestClientException e) {
        if (e instanceof ResourceAccessException || e instanceof HttpServerErrorException) {
            return new IdpIndisponivelException("Keycloak indisponível ou lento além do timeout.", e);
        }
        if (e instanceof HttpClientErrorException erro) {
            if (erro.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                return new UsuarioJaExisteException();
            }
            if (erro.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return new RecursoNaoEncontradoException();
            }
        }
        return new IllegalStateException("Keycloak recusou a chamada da Admin API (configuração da conta de serviço?)", e);
    }

    private record TokenEmCache(String valor, Instant expiraEm) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RespostaToken(@JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PapelKeycloak(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UsuarioKeycloakJson(String id, String username, String email, String firstName, String lastName,
            boolean enabled) {

        UsuarioKeycloak paraUsuario() {
            return new UsuarioKeycloak(UUID.fromString(id), username, email, firstName, lastName, enabled);
        }
    }
}
