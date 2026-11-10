package com.gestaosla.backend.cadastro.internal;

import java.time.LocalDate;
import java.util.UUID;

import com.gestaosla.backend.shared.api.EntidadeBase;

import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Contrato de um cliente (RF-06, RN-06). Todo chamado é aberto sob um contrato vigente (RN-01). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Contrato extends EntidadeBase {

    private UUID clienteId;

    private String codigo;

    private LocalDate inicioVigencia;

    private LocalDate fimVigencia;

    // Público só para o teste em unit/; fora do módulo, o Modulith barra.
    public Contrato(UUID clienteId, String codigo, LocalDate inicioVigencia, LocalDate fimVigencia) {
        this.clienteId = clienteId;
        this.codigo = codigo;
        this.inicioVigencia = inicioVigencia;
        this.fimVigencia = fimVigencia;
    }

    /**
     * Início e fim inclusivos. Com a data de hoje no fuso do sistema ({@code LocalDate.now(clock)}), o contrato vale
     * até 23:59:59 do último dia.
     */
    public boolean estaVigenteEm(LocalDate data) {
        return !data.isBefore(inicioVigencia) && !data.isAfter(fimVigencia);
    }
}
