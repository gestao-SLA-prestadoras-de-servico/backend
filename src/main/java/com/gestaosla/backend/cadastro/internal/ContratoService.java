package com.gestaosla.backend.cadastro.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.ConflitoException;
import com.gestaosla.backend.shared.api.Paginas;
import com.gestaosla.backend.shared.api.RegraNegocioException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class ContratoService {

    private final ContratoRepository contratos;
    private final ClienteRepository clientes;
    private final Clock clock;

    @Transactional
    Contrato criar(DadosContrato dados) {
        Cliente cliente = clientes.findById(dados.clienteId()).orElseThrow(
                () -> new RegraNegocioException(CodigoErro.CLIENTE_INEXISTENTE, "Empresa cliente não encontrada."));
        if (!cliente.isAtivo()) {
            throw new RegraNegocioException(CodigoErro.CLIENTE_INATIVO, "Empresa cliente inativa não aceita novo contrato.");
        }
        // A UNIQUE do banco cobre a corrida entre dois cadastros simultâneos; aqui o caso comum ganha o código certo.
        if (contratos.existsByCodigo(dados.codigo())) {
            throw new ConflitoException(CodigoErro.CODIGO_CONTRATO_JA_EXISTE, "Já existe contrato com este código.");
        }
        return contratos.save(new Contrato(cliente.getId(), dados.codigo(), dados.inicioVigencia(), dados.fimVigencia()));
    }

    @Transactional(readOnly = true)
    Page<Contrato> listar(UUID clienteId, Boolean vigente, Pageable pagina) {
        Specification<Contrato> filtro = Specification.unrestricted();
        if (clienteId != null) {
            filtro = filtro.and(doCliente(clienteId));
        }
        if (vigente != null) {
            filtro = filtro.and(vigente ? vigentes() : Specification.not(vigentes()));
        }
        return contratos.findAll(filtro, Paginas.comDesempate(pagina));
    }

    /** Contratos vigentes hoje da empresa do usuário; a empresa vem do token, nunca da requisição (RN-126). */
    @Transactional(readOnly = true)
    Page<Contrato> listarVigentesDoCliente(UUID empresaClienteId, Pageable pagina) {
        if (empresaClienteId == null) {
            return Page.empty(pagina);
        }
        return contratos.findAll(doCliente(empresaClienteId).and(vigentes()), Paginas.comDesempate(pagina));
    }

    private static Specification<Contrato> doCliente(UUID clienteId) {
        return (c, q, cb) -> cb.equal(c.get("clienteId"), clienteId);
    }

    // Mesma regra de Contrato.estaVigenteEm, em SQL.
    private Specification<Contrato> vigentes() {
        LocalDate hoje = LocalDate.now(clock);
        return (c, q, cb) -> cb.and(cb.lessThanOrEqualTo(c.get("inicioVigencia"), hoje),
                cb.greaterThanOrEqualTo(c.get("fimVigencia"), hoje));
    }
}
