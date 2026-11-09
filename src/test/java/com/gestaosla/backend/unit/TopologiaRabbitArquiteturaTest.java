package com.gestaosla.backend.unit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.QueueBuilder;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class TopologiaRabbitArquiteturaTest {

    private static final JavaClasses producao = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.gestaosla.backend");

    @Test
    void shouldNotDeclareRabbitTopologyInApplication() {
        noClasses()
                .should().dependOnClassesThat().areAssignableTo(Declarable.class)
                .orShould().dependOnClassesThat().belongToAnyOf(QueueBuilder.class, ExchangeBuilder.class,
                        BindingBuilder.class)
                .because("filas, exchanges e bindings vêm só de infra/rabbitmq/topologia.json; o RabbitAdmin "
                        + "declararia com argumentos próprios e divergiria do broker (outbox-02)")
                .check(producao);
    }
}
