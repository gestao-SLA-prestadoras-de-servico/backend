package com.gestaosla.backend.shared.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Apoio às listagens paginadas. */
public final class Paginas {

    private Paginas() {
    }

    /**
     * Acrescenta o {@code id} ao fim da ordenação. Sem desempate, itens com o mesmo valor (dois usuários com o mesmo nome)
     * podem repetir ou sumir entre páginas: o banco não garante a ordem dos empatados.
     */
    public static Pageable comDesempate(Pageable pagina) {
        return PageRequest.of(pagina.getPageNumber(), pagina.getPageSize(), pagina.getSort().and(Sort.by("id")));
    }
}
