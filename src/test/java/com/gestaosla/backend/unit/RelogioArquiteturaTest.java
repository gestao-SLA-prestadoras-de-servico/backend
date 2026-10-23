package com.gestaosla.backend.unit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.time.Clock;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class RelogioArquiteturaTest {

    private static final JavaClasses producao = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.gestaosla.backend");

    // now()/now(ZoneId) de qualquer tipo do java.time, Clock.system*() e System.currentTimeMillis().
    private static final DescribedPredicate<JavaMethodCall> leHoraDoSistema = DescribedPredicate.describe(
            "lê a hora do sistema sem o Clock injetado", chamada -> {
                var alvo = chamada.getTarget();
                var dono = alvo.getOwner();
                if (alvo.getName().equals("now") && dono.getPackageName().equals("java.time")) {
                    return alvo.getRawParameterTypes().stream().noneMatch(tipo -> tipo.isEquivalentTo(Clock.class));
                }
                return (dono.isEquivalentTo(Clock.class) && alvo.getName().startsWith("system"))
                        || (dono.isEquivalentTo(System.class) && alvo.getName().equals("currentTimeMillis"));
            });

    @Test
    void shouldReadTimeOnlyThroughInjectedClock() {
        noClasses()
                .that().doNotHaveFullyQualifiedName("com.gestaosla.backend.shared.internal.ClockConfig")
                .should().callMethodWhere(leHoraDoSistema)
                .because("o cálculo de prazo precisa de um Clock substituível nos testes (RN-62)")
                .check(producao);
    }
}
