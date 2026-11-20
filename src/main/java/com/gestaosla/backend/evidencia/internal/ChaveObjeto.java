package com.gestaosla.backend.evidencia.internal;

import java.util.Optional;
import java.util.UUID;

/** Convenção das chaves no bucket: {@code evidencias/{chamadoId}/{evidenciaId}/(original.{ext}|miniatura.jpg|visualizacao.jpg)}. */
record ChaveObjeto(UUID chamadoId, UUID evidenciaId) {

    static Optional<ChaveObjeto> identificar(String chave) {
        String[] partes = chave.split("/", 4);
        if (partes.length != 4 || !partes[0].equals("evidencias")) {
            return Optional.empty();
        }
        try {
            var referencia = new ChaveObjeto(UUID.fromString(partes[1]), UUID.fromString(partes[2]));
            return chave.startsWith(referencia.prefixo()) ? Optional.of(referencia) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    String prefixo() {
        return "evidencias/" + chamadoId + "/" + evidenciaId + "/";
    }

    String original(String extensao) {
        return prefixo() + "original." + extensao;
    }

    String miniatura() {
        return prefixo() + "miniatura.jpg";
    }

    String visualizacao() {
        return prefixo() + "visualizacao.jpg";
    }
}
