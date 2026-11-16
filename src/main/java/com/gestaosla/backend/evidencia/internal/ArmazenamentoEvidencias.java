package com.gestaosla.backend.evidencia.internal;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.gestaosla.backend.shared.api.ServicoIndisponivelException;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Único ponto de acesso ao bucket das evidências (MinIO). Falha de rede, timeout ou 5xx vira {@link ServicoIndisponivelException}. */
@Component
class ArmazenamentoEvidencias {

    static final String BUCKET = "evidencias";
    private static final int LOTE_REMOCAO = 1000;
    private static final Logger log = LoggerFactory.getLogger(ArmazenamentoEvidencias.class);

    private final S3Client s3;

    ArmazenamentoEvidencias(S3Client s3) {
        this.s3 = s3;
    }

    /**
     * Cria o bucket se não existir. Se o MinIO estiver fora do ar na subida, só avisa no log: a aplicação sobe e o primeiro
     * uso falha com 503, em vez de a API inteira depender do storage para ligar.
     */
    @EventListener(ApplicationReadyEvent.class)
    void garantirBucket() {
        try {
            try {
                s3.headBucket(b -> b.bucket(BUCKET));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(BUCKET));
                log.info("Bucket {} criado", BUCKET);
            }
        } catch (RuntimeException e) {
            log.warn("Não foi possível garantir o bucket {}; o primeiro uso do storage vai falhar até o MinIO responder", BUCKET, e);
        }
    }

    void gravar(String chave, InputStream conteudo, long tamanho, String contentType) {
        chamar(() -> s3.putObject(b -> b.bucket(BUCKET).key(chave).contentType(contentType),
                RequestBody.fromInputStream(conteudo, tamanho)));
    }

    ObjetoLido ler(String chave) {
        ResponseInputStream<GetObjectResponse> resposta = chamar(() -> s3.getObject(b -> b.bucket(BUCKET).key(chave)));
        return new ObjetoLido(resposta, resposta.response().contentLength());
    }

    List<ObjetoArmazenado> listar(String prefixo) {
        return chamar(() -> {
            List<ObjetoArmazenado> objetos = new ArrayList<>();
            s3.listObjectsV2Paginator(b -> b.bucket(BUCKET).prefix(prefixo)).contents()
                    .forEach(o -> objetos.add(new ObjetoArmazenado(o.key(), o.lastModified(), o.size())));
            return objetos;
        });
    }

    /** Remove tudo sob o prefixo; prefixo vazio apagaria o bucket inteiro, então é recusado. */
    void removerPrefixo(String prefixo) {
        if (prefixo == null || prefixo.isBlank()) {
            throw new IllegalArgumentException("Prefixo obrigatório para remover objetos");
        }
        List<ObjectIdentifier> chaves = listar(prefixo).stream()
                .map(o -> ObjectIdentifier.builder().key(o.chave()).build()).toList();
        for (int i = 0; i < chaves.size(); i += LOTE_REMOCAO) {
            List<ObjectIdentifier> lote = chaves.subList(i, Math.min(i + LOTE_REMOCAO, chaves.size()));
            var resposta = chamar(() -> s3.deleteObjects(b -> b.bucket(BUCKET).delete(d -> d.objects(lote).quiet(true))));
            if (resposta.hasErrors() && !resposta.errors().isEmpty()) {
                throw new ServicoIndisponivelException("Falha ao remover " + resposta.errors().size() + " objeto(s) do storage", null);
            }
        }
    }

    private <T> T chamar(Supplier<T> operacao) {
        try {
            return operacao.get();
        } catch (SdkClientException e) {
            throw new ServicoIndisponivelException("Storage de evidências inacessível", e);
        } catch (S3Exception e) {
            if (e.statusCode() >= 500) {
                throw new ServicoIndisponivelException("Storage de evidências respondeu " + e.statusCode(), e);
            }
            throw e;
        }
    }
}
