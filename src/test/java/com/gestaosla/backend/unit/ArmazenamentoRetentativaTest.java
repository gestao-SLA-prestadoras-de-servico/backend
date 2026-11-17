package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Retentativa do SDK ao gravar (evidencia-01, correção C da revisão): servidor local que responde 503 ao 1º PUT e 200 ao 2º. */
class ArmazenamentoRetentativaTest {

    private HttpServer servidor;
    private S3Client s3;
    private final List<byte[]> corpos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void subir() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            corpos.add(troca.getRequestBody().readAllBytes());
            troca.sendResponseHeaders(corpos.size() == 1 ? 503 : 200, -1);
            troca.close();
        });
        servidor.start();
        // Mesma configuração do ArmazenamentoConfig (que é interno ao módulo), apontada para o servidor falso.
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .build();
    }

    @AfterEach
    void descer() {
        s3.close();
        servidor.stop(0);
    }

    // Retentativa do SDK com stream (evidencia-01). Todos os chamadores de produção embrulham um byte[] em ByteArrayInputStream,
    // cujo reset funciona sem limite (ignora o readlimit de 128 KB do mark): a gravação > 128 KB é repetida e íntegra.
    @Test
    void shouldRetryTheWriteOfAByteArrayStreamLargerThan128KbAfterATransientFailure() {
        byte[] conteudo = conteudo();

        new ArmazenamentoTestHelper(s3).gravarStream("chave", new ByteArrayInputStream(conteudo), conteudo.length, "image/png");

        assertThat(corpos).hasSize(2);
        // O corpo vem em aws-chunked (blocos de 128 KB com assinatura), então não é igual ao conteúdo: confere o tamanho
        // da 1ª tentativa (que o servidor leu inteira) e que o primeiro e o último bloco chegaram íntegros na 2ª.
        assertThat(corpos.get(1)).hasSize(corpos.get(0).length);
        assertThat(contem(corpos.get(1), Arrays.copyOfRange(conteudo, 0, 128 * 1024))).as("primeiro bloco").isTrue();
        assertThat(contem(corpos.get(1), Arrays.copyOfRange(conteudo, 384 * 1024, conteudo.length))).as("último bloco").isTrue();
    }

    // Documenta o risco que o teste acima descarta para os chamadores atuais: um stream com mark limitado (aqui o
    // BufferedInputStream) não é resetável além do limite, e a falha transitória vira erro em vez de retentativa.
    @Test
    void shouldNotRetryAStreamThatCannotBeResetBeyondTheMarkLimit() {
        byte[] conteudo = conteudo();
        var armazenamento = new ArmazenamentoTestHelper(s3);
        var stream = new BufferedInputStream(new ByteArrayInputStream(conteudo), 8192);

        assertThatThrownBy(() -> armazenamento.gravarStream("chave", stream, conteudo.length, "image/png"))
                .hasMessageContaining("Resetting to invalid mark");
        assertThat(corpos).hasSize(1);
    }

    private static byte[] conteudo() {
        byte[] conteudo = new byte[400 * 1024];
        new Random(1).nextBytes(conteudo);
        return conteudo;
    }

    private static boolean contem(byte[] corpo, byte[] trecho) {
        return new String(corpo, StandardCharsets.ISO_8859_1).contains(new String(trecho, StandardCharsets.ISO_8859_1));
    }
}
