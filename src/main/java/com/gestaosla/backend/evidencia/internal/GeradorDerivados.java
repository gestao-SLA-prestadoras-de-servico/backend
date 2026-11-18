package com.gestaosla.backend.evidencia.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkClientException;

import com.gestaosla.backend.shared.api.ServicoIndisponivelException;

/**
 * Gera a miniatura e a visualização de uma evidência (RF-61, RN-78, RN-86). Bean separado do listener porque o
 * {@code @Retry} é por proxy: a chamada precisa atravessar o bean. Só as falhas transitórias listadas em
 * {@code resilience4j.retry.instances.evidencias} são repetidas; imagem ilegível não (RN-87).
 */
@Component
@RequiredArgsConstructor
class GeradorDerivados {

    static final int LADO_MINIATURA = 320;
    static final int LADO_VISUALIZACAO = 1280;
    private static final Logger log = LoggerFactory.getLogger(GeradorDerivados.class);

    /** A imagem original não decodifica: repetir não adianta. */
    static class ImagemIlegivelException extends RuntimeException {
        ImagemIlegivelException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    private final EvidenciaRepository evidencias;
    private final ArmazenamentoEvidencias armazenamento;
    private final Clock clock;
    private final TransactionTemplate transacao;

    /**
     * Cada tentativa reivindica de novo (RECEBIDA ou PROCESSANDO) e sobrescreve as mesmas chaves, que são determinísticas:
     * Uma nova tentativa pode sobrescrever os mesmos derivados. A geração trava só a linha da evidência para
     * evitar que o expurgo remova o prefixo enquanto os derivados estão sendo gravados.
     */
    @Retry(name = "evidencias")
    public void gerar(UUID id) {
        if (evidencias.reivindicar(id, Instant.now(clock)) == 0) {
            log.info("Evidência {} descartada: não está RECEBIDA nem PROCESSANDO (já processada, expurgada, em falha ou inexistente)", id);
            return;
        }
        transacao.executeWithoutResult(status -> gerarComTrava(id));
    }

    private void gerarComTrava(UUID id) {
        Evidencia evidencia = evidencias.buscarComTrava(id).orElseThrow();
        if (evidencia.getEstado() != EstadoEvidencia.PROCESSANDO) {
            return;
        }
        BufferedImage imagem = decodificar(lerOriginal(evidencia.getChaveOriginal()), id);
        ChaveObjeto chaves = new ChaveObjeto(evidencia.getChamadoId(), id);
        gravar(chaves.miniatura(), Redimensionador.jpeg(imagem, LADO_MINIATURA));
        gravar(chaves.visualizacao(), Redimensionador.jpeg(imagem, LADO_VISUALIZACAO));
        evidencias.concluir(id, chaves.miniatura(), chaves.visualizacao(), Instant.now(clock));
    }

    // Lê tudo antes de decodificar: falha de rede no meio do stream é transitória; falha de decodificação, não.
    private byte[] lerOriginal(String chave) {
        ObjetoLido lido = armazenamento.ler(chave);
        try (InputStream in = lido.conteudo()) {
            return in.readAllBytes();
        } catch (IOException | SdkClientException e) {
            throw new ServicoIndisponivelException("Falha ao ler o original do storage", e);
        }
    }

    private static BufferedImage decodificar(byte[] bytes, UUID id) {
        try {
            BufferedImage imagem = ImageIO.read(new ByteArrayInputStream(bytes));
            if (imagem == null) {
                throw new ImagemIlegivelException("Formato de imagem não reconhecido na evidência " + id, null);
            }
            return imagem;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ImagemIlegivelException ilegivel) {
                throw ilegivel;
            }
            throw new ImagemIlegivelException("A imagem da evidência " + id + " não decodifica", e);
        }
    }

    private void gravar(String chave, byte[] jpeg) {
        armazenamento.gravar(chave, new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");
    }
}
