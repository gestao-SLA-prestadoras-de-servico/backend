package com.gestaosla.backend.unit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class SlaArquiteturaTest {

    private static final JavaClasses producao = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.gestaosla.backend");

    // O ModularidadeTest só barra ciclo e acesso a internal; o sla importar chamado.api passaria nele.
    @Test
    void shouldNotDependOnChamadoModule() {
        noClasses().that().resideInAPackage("com.gestaosla.backend.sla..")
                .should().dependOnClassesThat().resideInAPackage("com.gestaosla.backend.chamado..")
                .because("o chamado depende do sla; o sla devolve as violações e quem grava o aviso é o chamado (sla-02)")
                .check(producao);
    }
}
