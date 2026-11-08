package com.gestaosla.backend.cadastro.internal;

import com.gestaosla.backend.shared.api.EntidadeBase;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Empresa cliente (RF-05, RN-08). Nunca é apagada; inativar preserva o histórico (RN-07). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Cliente extends EntidadeBase {

    private String razaoSocial;

    private String emailContato;

    @Embedded
    private Endereco endereco;

    private boolean ativo;

    @Version
    private long version;

    Cliente(String razaoSocial, String emailContato, Endereco endereco) {
        alterar(razaoSocial, emailContato, endereco);
        this.ativo = true;
    }

    void alterar(String razaoSocial, String emailContato, Endereco endereco) {
        this.razaoSocial = razaoSocial;
        this.emailContato = emailContato;
        this.endereco = endereco;
    }

    /** @return {@code false} se já estava inativo */
    boolean inativar() {
        boolean estavaAtivo = ativo;
        ativo = false;
        return estavaAtivo;
    }
}
