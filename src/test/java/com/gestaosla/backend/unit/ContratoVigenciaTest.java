package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.gestaosla.backend.cadastro.internal.Contrato;
import com.gestaosla.backend.cadastro.internal.DadosContrato;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ContratoVigenciaTest {

    private static final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();
    private static final LocalDate INICIO = LocalDate.of(2026, 1, 1);
    private static final LocalDate FIM = LocalDate.of(2026, 12, 31);
    private static final Contrato CONTRATO = new Contrato(UUID.randomUUID(), "CT-1", INICIO, FIM);

    @Test
    void shouldBeInForceFromFirstToLastDayInclusive() {
        assertThat(CONTRATO.estaVigenteEm(INICIO)).isTrue();
        assertThat(CONTRATO.estaVigenteEm(LocalDate.of(2026, 6, 15))).isTrue();
        assertThat(CONTRATO.estaVigenteEm(FIM)).isTrue();
    }

    @Test
    void shouldNotBeInForceBeforeStartOrAfterEnd() {
        assertThat(CONTRATO.estaVigenteEm(INICIO.minusDays(1))).isFalse();
        assertThat(CONTRATO.estaVigenteEm(FIM.plusDays(1))).isFalse();
    }

    @Test
    void shouldAcceptSingleDayContract() {
        assertThat(invalidos(new DadosContrato(UUID.randomUUID(), "CT-1", INICIO, INICIO))).isEmpty();
        assertThat(new Contrato(UUID.randomUUID(), "CT-1", INICIO, INICIO).estaVigenteEm(INICIO)).isTrue();
    }

    @Test
    void shouldRejectEndBeforeStartOnEndField() {
        assertThat(invalidos(new DadosContrato(UUID.randomUUID(), "CT-1", INICIO, INICIO.minusDays(1)))).containsExactly("fimVigencia");
    }

    @Test
    void shouldRequireAllFields() {
        assertThat(invalidos(new DadosContrato(null, " ", null, null)))
                .containsExactlyInAnyOrder("clienteId", "codigo", "inicioVigencia", "fimVigencia");
    }

    private static Set<String> invalidos(DadosContrato dados) {
        return validador.validate(dados).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }
}
