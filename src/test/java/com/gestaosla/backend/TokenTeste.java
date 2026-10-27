package com.gestaosla.backend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Faz o papel do Keycloak nos testes: serve um JWKS por HTTP e assina tokens com a chave correspondente. Ao contrário
 * do {@code jwt()} do spring-security-test, o token passa pelo decoder (assinatura, iss, aud) e pelo conversor reais.
 */
public final class TokenTeste {

    public static final String ISSUER = "http://localhost/realms/flowsla";
    public static final String SUB_GESTOR = "0199a000-0000-7000-8000-000000000001";
    public static final RSAPrivateKey CHAVE;
    public static final String JWK_SET_URI;

    static {
        KeyPair par = novoPar();
        CHAVE = (RSAPrivateKey) par.getPrivate();
        byte[] jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) par.getPublic()).build()).toString()
                .getBytes(StandardCharsets.UTF_8);
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            servidor.createContext("/certs", troca -> {
                troca.getResponseHeaders().add("Content-Type", "application/json");
                troca.sendResponseHeaders(200, jwks.length);
                troca.getResponseBody().write(jwks);
                troca.close();
            });
            servidor.start();
            JWK_SET_URI = "http://localhost:" + servidor.getAddress().getPort() + "/certs";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TokenTeste() {
    }

    public static String token(String sub, String... papeis) {
        return token(CHAVE, ISSUER, "app-principal", sub, papeis);
    }

    public static String token(RSAPrivateKey chave, String issuer, String aud, String sub, String... papeis) {
        long agora = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(List.of(aud, "ms-notificacoes"))
                .subject(sub)
                .issueTime(new Date(agora))
                .expirationTime(new Date(agora + 300_000))
                .claim("realm_access", Map.of("roles", List.of(papeis)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(chave));
            return "Bearer " + jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair novoPar() {
        try {
            KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
            gerador.initialize(2048);
            return gerador.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
