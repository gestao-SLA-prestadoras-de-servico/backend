package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.gestaosla.backend.cadastro.internal.DadosCliente;
import com.gestaosla.backend.cadastro.internal.Endereco;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class DadosClienteValidacaoTest {

    private static final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();
    private static final Endereco ENDERECO = new Endereco("Av. Santos Dumont", "1500", null, "Aldeota", "Fortaleza", "CE", "60150160");

    @Test
    void shouldAcceptCompleteClient() {
        assertThat(invalidos(new DadosCliente("Refrigeração Norte Ltda", "contato@norte.com.br", ENDERECO))).isEmpty();
    }

    @Test
    void shouldRejectInvalidContactEmail() {
        assertThat(invalidos(new DadosCliente("Refrigeração Norte Ltda", "contato.norte.com.br", ENDERECO))).containsExactly("emailContato");
    }

    @Test
    void shouldRequireCorporateNameAndAddress() {
        assertThat(invalidos(new DadosCliente(" ", "contato@norte.com.br", null))).containsExactlyInAnyOrder("razaoSocial", "endereco");
    }

    @Test
    void shouldValidateStateAndPostalCodeFormat() {
        Endereco errado = new Endereco("Rua A", "10", null, "Centro", "Fortaleza", "ce", "60150-160");

        assertThat(invalidos(new DadosCliente("Cliente", "a@b.com", errado))).containsExactlyInAnyOrder("endereco.uf", "endereco.cep");
    }

    private static Set<String> invalidos(DadosCliente dados) {
        return validador.validate(dados).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }
}
