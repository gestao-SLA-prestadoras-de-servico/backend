package com.gestaosla.backend.unit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * A regra de rota (RegrasAcesso) barra antes de ler o corpo, e o {@code @PreAuthorize} é a segunda barreira: tirar só ele
 * não muda nenhuma resposta HTTP, então nenhum teste de rota percebe. Esta regra exige o {@code @PreAuthorize} em todo
 * endpoint, no método ou na classe. Exceção: o perfil corrente, aberto a qualquer usuário autenticado.
 */
class SegurancaArquiteturaTest {

    private static final JavaClasses producao = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.gestaosla.backend");

    private static final ArchCondition<JavaMethod> declararPapel = new ArchCondition<>("declarar o papel com @PreAuthorize") {
        @Override
        public void check(JavaMethod metodo, ConditionEvents eventos) {
            if (!metodo.isAnnotatedWith(PreAuthorize.class) && !metodo.getOwner().isAnnotatedWith(PreAuthorize.class)) {
                eventos.add(SimpleConditionEvent.violated(metodo, metodo.getFullName() + " não tem @PreAuthorize"));
            }
        }
    };

    @Test
    void shouldDeclareRoleOnEveryEndpointExceptCurrentUser() {
        methods()
                .that().areMetaAnnotatedWith(RequestMapping.class)
                .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().doNotHaveName("me")
                .should(declararPapel)
                .because("a regra de rota e o @PreAuthorize são as duas barreiras de papel (README, Autenticação e autorização)")
                .check(producao);
    }
}
