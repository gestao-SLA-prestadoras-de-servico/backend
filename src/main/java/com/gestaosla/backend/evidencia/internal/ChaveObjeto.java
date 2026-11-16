package com.gestaosla.backend.evidencia.internal;

import java.util.UUID;

/** Convenção das chaves no bucket: {@code evidencias/{chamadoId}/{evidenciaId}/(original.{ext}|miniatura.jpg|visualizacao.jpg)}. */
record ChaveObjeto(UUID chamadoId, UUID evidenciaId) {

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
