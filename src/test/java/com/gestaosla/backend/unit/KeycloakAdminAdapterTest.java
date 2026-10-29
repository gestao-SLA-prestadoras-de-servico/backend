package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gestaosla.backend.identidade.api.IdpIndisponivelException;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.identidade.api.NovoUsuarioKeycloak;
import com.gestaosla.backend.identidade.api.UsuarioJaExisteException;
import com.gestaosla.backend.identidade.internal.KeycloakAdminTestHelper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Timeout, retry e não duplicação contra um Keycloak simulado (RN-137). O comportamento com o Keycloak real fica no
 * KeycloakAdminClientIntegrationTest.
 */
class KeycloakAdminAdapterTest {

    private static final Duration TIMEOUT = Duration.ofMillis(150);
    private static final UUID ID = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");
    private static final NovoUsuarioKeycloak NOVO = new NovoUsuarioKeycloak("tecnico", "tecnico@flowsla.local", "Ana", "Lima");
    private static final String ENCONTRADO = "[{\"id\":\"" + ID + "\",\"username\":\"tecnico\",\"enabled\":true}]";
    private static final String CONFLITO = "{\"errorMessage\":\"User exists with same username\"}";

    private HttpServer keycloak;
    private final Map<String, AtomicInteger> chamadas = new ConcurrentHashMap<>();
    private final AtomicInteger tokens = new AtomicInteger();
    private final Set<UUID> comRegistroLocal = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> corpoDoPut = new AtomicReference<>();

    @BeforeEach
    void subirKeycloakSimulado() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.setExecutor(Executors.newCachedThreadPool()); // atende em paralelo, como o Keycloak
        keycloak.createContext("/realms/flowsla/protocol/openid-connect/token", troca -> {
            tokens.incrementAndGet();
            responder(troca, 200, "{\"access_token\":\"t\",\"expires_in\":300}");
        });
        keycloak.start();
    }

    @AfterEach
    void derrubar() {
        keycloak.stop(0);
    }

    @Test
    void shouldRetryIdempotentOperationWhileKeycloakIsUnavailable() {
        AtomicInteger puts = new AtomicInteger();
        usuarios(troca -> responder(troca, puts.incrementAndGet() < 3 ? 503 : 204, ""));

        comCliente(cliente -> cliente.desabilitar(ID));

        assertThat(puts).hasValue(3);
    }

    @Test
    void shouldGiveUpWith503AfterMaxAttempts() {
        usuarios(troca -> responder(troca, 503, ""));

        comCliente(cliente -> assertThatThrownBy(() -> cliente.reabilitar(ID)).isInstanceOf(IdpIndisponivelException.class));

        assertThat(chamadas("PUT")).isEqualTo(3);
    }

    @Test
    void shouldNotRetryPasswordEmail() {
        usuarios(troca -> responder(troca, 503, ""));

        comCliente(cliente -> assertThatThrownBy(() -> cliente.enviarDefinicaoSenha(ID))
                .isInstanceOf(IdpIndisponivelException.class));

        assertThat(chamadas("PUT")).isEqualTo(1);
    }

    // Segredo errado ou permissão faltando é erro de configuração: 500, sem retry.
    @Test
    void shouldNotRetryClientErrorsAndReportThemAsInternalError() {
        keycloak.removeContext("/realms/flowsla/protocol/openid-connect/token");
        keycloak.createContext("/realms/flowsla/protocol/openid-connect/token", troca -> {
            tokens.incrementAndGet();
            responder(troca, 401, "{\"error\":\"unauthorized_client\"}");
        });
        usuarios(troca -> responder(troca, 204, ""));

        comCliente(cliente -> assertThatThrownBy(() -> cliente.desabilitar(ID)).isInstanceOf(IllegalStateException.class));

        assertThat(tokens).hasValue(1);
    }

    @Test
    void shouldFailWithinTimeoutWhenKeycloakIsSlow() {
        usuarios(troca -> {
            dormir(2_000);
            responder(troca, 200, "[]");
        });

        comCliente(cliente -> {
            long inicio = System.nanoTime();
            assertThatThrownBy(() -> cliente.buscarPorUsername("tecnico")).isInstanceOf(IdpIndisponivelException.class);
            // 3 tentativas de 150 ms + esperas de 20 e 40 ms; bem abaixo dos 2 s do Keycloak lento.
            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofMillis(1_500));
        });
    }

    @Test
    void shouldReturnExistingSubWhenCreationTimesOutAfterKeycloakSavedTheAccount() {
        AtomicInteger posts = new AtomicInteger();
        usuarios(troca -> {
            switch (troca.getRequestMethod()) {
                case "POST" -> {
                    posts.incrementAndGet();
                    dormir(500); // grava, mas responde depois do timeout
                    responder(troca, 201, "");
                }
                case "GET" -> responder(troca, 200, ENCONTRADO);
                default -> responder(troca, 204, "");
            }
        });

        comCliente(cliente -> assertThat(cliente.criarUsuario(NOVO)).isEqualTo(ID));

        assertThat(posts).hasValue(1);
    }

    // A conta da 1ª tentativa ainda não aparecia na busca; a 2ª tentativa recebe 409 dela mesma.
    @Test
    void shouldReturnSubWhenRetryGets409FromAccountCreatedByFirstAttempt() {
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger buscas = new AtomicInteger();
        usuarios(troca -> {
            switch (troca.getRequestMethod()) {
                case "POST" -> {
                    if (posts.incrementAndGet() == 1) {
                        dormir(500);
                        responder(troca, 201, "");
                    } else {
                        responder(troca, 409, CONFLITO);
                    }
                }
                case "GET" -> responder(troca, 200, buscas.incrementAndGet() == 1 ? "[]" : ENCONTRADO);
                default -> responder(troca, 204, "");
            }
        });

        comCliente(cliente -> assertThat(cliente.criarUsuario(NOVO)).isEqualTo(ID));

        assertThat(posts).hasValue(2);
    }

    // Sem conseguir conferir, não se sabe se a conta existe: 503 e nenhum POST às cegas.
    @Test
    void shouldNotPostAgainWhenTheCheckAfterTimeoutFails() {
        AtomicInteger posts = new AtomicInteger();
        usuarios(troca -> {
            if (troca.getRequestMethod().equals("POST")) {
                posts.incrementAndGet();
                dormir(500);
                responder(troca, 201, "");
            } else {
                responder(troca, 503, "");
            }
        });

        comCliente(cliente -> assertThatThrownBy(() -> cliente.criarUsuario(NOVO)).isInstanceOf(IdpIndisponivelException.class));

        assertThat(posts).hasValue(1);
    }

    // Sobra de um cadastro que não terminou: mesmo username, sem registro local.
    @Test
    void shouldReuseOrphanAccountWithSameUsernameAndUpdateItsData() {
        conflitoComContaEncontrada(ENCONTRADO);

        comCliente(cliente -> assertThat(cliente.criarUsuario(NOVO)).isEqualTo(ID));

        assertThat(corpoDoPut.get()).contains("\"email\":\"tecnico@flowsla.local\"", "\"firstName\":\"Ana\"",
                "\"enabled\":true");
        assertThat(chamadas("POST")).isEqualTo(1);
    }

    @Test
    void shouldThrowConflictWhenUsernameBelongsToRegisteredUser() {
        comRegistroLocal.add(ID);
        conflitoComContaEncontrada(ENCONTRADO);

        comCliente(cliente -> assertThatThrownBy(() -> cliente.criarUsuario(NOVO)).isInstanceOf(UsuarioJaExisteException.class));

        assertThat(chamadas("POST")).isEqualTo(1);
        assertThat(chamadas("PUT")).isZero();
    }

    // 409 pelo e-mail de outra conta: a busca pelo username não acha nada.
    @Test
    void shouldThrowConflictWhenEmailBelongsToAnotherAccount() {
        conflitoComContaEncontrada("[]");

        comCliente(cliente -> assertThatThrownBy(() -> cliente.criarUsuario(NOVO)).isInstanceOf(UsuarioJaExisteException.class));

        assertThat(chamadas("PUT")).isZero();
    }

    // Depois do timeout, a conta achada pelo username já era de um usuário cadastrado: 409, não o sub dele.
    @Test
    void shouldNotReuseAccountFoundAfterTimeoutWhenItHasLocalRecord() {
        comRegistroLocal.add(ID);
        usuarios(troca -> {
            if (troca.getRequestMethod().equals("POST")) {
                dormir(500);
                responder(troca, 201, "");
            } else {
                responder(troca, 200, ENCONTRADO);
            }
        });

        comCliente(cliente -> assertThatThrownBy(() -> cliente.criarUsuario(NOVO)).isInstanceOf(UsuarioJaExisteException.class));

        assertThat(chamadas("POST")).isEqualTo(1);
        assertThat(chamadas("PUT")).isZero();
    }

    private void conflitoComContaEncontrada(String resultadoDaBusca) {
        usuarios(troca -> {
            switch (troca.getRequestMethod()) {
                case "POST" -> responder(troca, 409, CONFLITO);
                case "GET" -> responder(troca, 200, resultadoDaBusca);
                default -> {
                    corpoDoPut.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    responder(troca, 204, "");
                }
            }
        });
    }

    private void comCliente(Consumer<KeycloakAdminClient> teste) {
        KeycloakAdminTestHelper.contexto("http://localhost:" + keycloak.getAddress().getPort(), TIMEOUT, comRegistroLocal)
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    teste.accept(contexto.getBean(KeycloakAdminClient.class));
                });
    }

    private void usuarios(HttpHandler handler) {
        keycloak.createContext("/admin/realms/flowsla/users", troca -> {
            chamadas.computeIfAbsent(troca.getRequestMethod(), m -> new AtomicInteger()).incrementAndGet();
            handler.handle(troca);
        });
    }

    private int chamadas(String metodo) {
        return chamadas.getOrDefault(metodo, new AtomicInteger()).get();
    }

    private static void responder(HttpExchange troca, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 201) {
            troca.getResponseHeaders().add("Location", "http://localhost/admin/realms/flowsla/users/" + ID);
        }
        troca.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            troca.getResponseBody().write(bytes);
        }
        troca.close();
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
