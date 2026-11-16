package com.gestaosla.backend.evidencia.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationContext;

/**
 * Dá acesso a {@code integration/} e {@code unit/} (o CI só roda testes nesses pacotes) ao armazenamento e à convenção
 * de chaves, que são internos ao módulo.
 */
public final class ArmazenamentoTestHelper {

    private final ArmazenamentoEvidencias armazenamento;

    public ArmazenamentoTestHelper(ApplicationContext contexto) {
        this.armazenamento = contexto.getBean(ArmazenamentoEvidencias.class);
    }

    public void garantirBucket() {
        armazenamento.garantirBucket();
    }

    public void gravar(String chave, byte[] conteudo, String contentType) {
        armazenamento.gravar(chave, new ByteArrayInputStream(conteudo), conteudo.length, contentType);
    }

    /** Lê em stream e confere o tamanho anunciado contra os bytes recebidos. */
    public byte[] ler(String chave) throws IOException {
        ObjetoLido lido = armazenamento.ler(chave);
        try (InputStream in = lido.conteudo()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length != lido.tamanho()) {
                throw new IllegalStateException("tamanho anunciado " + lido.tamanho() + " != lido " + bytes.length);
            }
            return bytes;
        }
    }

    /** Chave -> última modificação (nunca nula, senão o teste falha). */
    public Map<String, java.time.Instant> listar(String prefixo) {
        Map<String, java.time.Instant> mapa = new LinkedHashMap<>();
        armazenamento.listar(prefixo).forEach(o -> mapa.put(o.chave(), o.ultimaModificacao()));
        return mapa;
    }

    public void removerPrefixo(String prefixo) {
        armazenamento.removerPrefixo(prefixo);
    }

    public static String prefixo(UUID chamado, UUID evidencia) {
        return new ChaveObjeto(chamado, evidencia).prefixo();
    }

    public static String original(UUID chamado, UUID evidencia, String extensao) {
        return new ChaveObjeto(chamado, evidencia).original(extensao);
    }

    public static String miniatura(UUID chamado, UUID evidencia) {
        return new ChaveObjeto(chamado, evidencia).miniatura();
    }

    public static String visualizacao(UUID chamado, UUID evidencia) {
        return new ChaveObjeto(chamado, evidencia).visualizacao();
    }
}
