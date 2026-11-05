package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.gestaosla.backend.identidade.internal.NovoUsuario;
import com.gestaosla.backend.shared.api.Papel;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class NovoUsuarioValidacaoTest {

    private static final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptTechnicianWithoutCompany() {
        assertThat(invalidos(new NovoUsuario("Ana Lima", "ana@norte.com.br", Papel.TECNICO, null))).isEmpty();
    }

    @Test
    void shouldRejectInvalidEmailMissingNameAndProfile() {
        assertThat(invalidos(new NovoUsuario(" ", "ana.norte.com.br", null, null)))
                .containsExactlyInAnyOrder("nome", "email", "perfil");
    }

    // RN-128: a senha nunca passa pelo sistema.
    @Test
    void shouldHaveNoPasswordField() {
        assertThat(Arrays.stream(NovoUsuario.class.getRecordComponents()).map(c -> c.getName().toLowerCase()))
                .noneMatch(nome -> nome.contains("senha") || nome.contains("password"));
    }

    private static Set<String> invalidos(NovoUsuario dados) {
        return validador.validate(dados).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }
}
