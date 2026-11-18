package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

import software.amazon.awssdk.services.s3.S3Client;

/** Adaptador do MinIO contra um MinIO real (evidencia-01). */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ArmazenamentoEvidenciasTest {

    @Autowired
    ApplicationContext contexto;
    @Autowired
    S3Client s3;

    ArmazenamentoTestHelper armazenamento;
    UUID chamado;

    @BeforeEach
    void preparar() {
        armazenamento = new ArmazenamentoTestHelper(contexto);
        chamado = UUID.randomUUID();
    }

    @Test
    void shouldHaveBucketAfterStartupAndCreateItIdempotently() {
        assertThat(s3.listBuckets().buckets()).extracting(b -> b.name()).contains("evidencias");

        armazenamento.garantirBucket();

        assertThat(s3.listBuckets().buckets()).extracting(b -> b.name()).containsOnlyOnce("evidencias");
    }

    @Test
    void shouldWriteAndReadBackTheSameBytes() throws Exception {
        byte[] conteudo = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(conteudo);
        String chave = ArmazenamentoTestHelper.original(chamado, UUID.randomUUID(), "png");

        armazenamento.gravar(chave, conteudo, "image/png");

        assertThat(Arrays.equals(armazenamento.ler(chave), conteudo)).isTrue();
    }

    @Test
    void shouldListKeysWithModificationDate() {
        UUID evidencia = UUID.randomUUID();
        armazenamento.gravar(ArmazenamentoTestHelper.original(chamado, evidencia, "jpg"), new byte[] {1}, "image/jpeg");
        armazenamento.gravar(ArmazenamentoTestHelper.miniatura(chamado, evidencia), new byte[] {2}, "image/jpeg");

        var objetos = armazenamento.listar(ArmazenamentoTestHelper.prefixo(chamado, evidencia));

        assertThat(objetos).containsOnlyKeys(
                ArmazenamentoTestHelper.original(chamado, evidencia, "jpg"),
                ArmazenamentoTestHelper.miniatura(chamado, evidencia));
        assertThat(objetos.values()).doesNotContainNull();
    }

    @Test
    void shouldRemoveOnlyTheGivenPrefix() {
        UUID alvo = UUID.randomUUID();
        UUID outra = UUID.randomUUID();
        armazenamento.gravar(ArmazenamentoTestHelper.original(chamado, alvo, "png"), new byte[] {1}, "image/png");
        armazenamento.gravar(ArmazenamentoTestHelper.miniatura(chamado, alvo), new byte[] {2}, "image/jpeg");
        armazenamento.gravar(ArmazenamentoTestHelper.visualizacao(chamado, alvo), new byte[] {3}, "image/jpeg");
        armazenamento.gravar(ArmazenamentoTestHelper.miniatura(chamado, outra), new byte[] {4}, "image/jpeg");

        armazenamento.removerPrefixo(ArmazenamentoTestHelper.prefixo(chamado, alvo));

        assertThat(armazenamento.listar(ArmazenamentoTestHelper.prefixo(chamado, alvo))).isEmpty();
        assertThat(armazenamento.listar(ArmazenamentoTestHelper.prefixo(chamado, outra)))
                .containsOnlyKeys(ArmazenamentoTestHelper.miniatura(chamado, outra));
    }

    @Test
    void shouldRefuseToRemoveWithEmptyPrefix() {
        assertThatThrownBy(() -> armazenamento.removerPrefixo(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
