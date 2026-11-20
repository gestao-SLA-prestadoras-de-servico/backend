package com.gestaosla.backend.retencao.internal;

import org.springframework.context.ApplicationContext;

public final class RotinaRetencaoTestHelper {

    private RotinaRetencaoTestHelper() {
    }

    public static void executar(ApplicationContext contexto) {
        contexto.getBean(RotinaRetencao.class).executar();
    }
}
