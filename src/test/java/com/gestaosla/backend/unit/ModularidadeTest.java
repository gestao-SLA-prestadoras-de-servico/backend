package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import com.gestaosla.backend.BackendApplication;

class ModularidadeTest {

    private static final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void shouldDetectAllModules() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("shared", "identidade", "cadastro", "chamado", "sla",
                        "evidencia", "outbox", "auditoria", "relatorio", "retencao");
    }

    @Test
    void shouldRespectModuleBoundariesAndHaveNoCycles() {
        modules.verify();
    }
}
