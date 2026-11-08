package com.gestaosla.backend.cadastro.internal;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/clientes")
@PreAuthorize("hasRole('GESTOR')")
@RequiredArgsConstructor
class ClienteController {

    private final ClienteService servico;

    @PostMapping
    ResponseEntity<ClienteResponse> criar(@Valid @RequestBody DadosCliente dados) {
        Cliente cliente = servico.criar(dados);
        return ResponseEntity.created(URI.create("/api/clientes/" + cliente.getId())).body(ClienteResponse.de(cliente));
    }

    @PutMapping("/{id}")
    ClienteResponse editar(@PathVariable UUID id, @Valid @RequestBody Edicao edicao) {
        return ClienteResponse.de(servico.editar(id, edicao.version(), edicao.paraDados()));
    }

    // ?razaoSocial= (contém, sem diferenciar maiúsculas) &ativo= &page=0&size=20&sort=razaoSocial
    @GetMapping
    PagedModel<ClienteResponse> listar(@RequestParam(required = false) String razaoSocial,
            @RequestParam(required = false) Boolean ativo,
            @PageableDefault(size = 20, sort = "razaoSocial", direction = Sort.Direction.ASC) Pageable pagina) {
        return new PagedModel<>(servico.listar(razaoSocial, ativo, pagina).map(ClienteResponse::de));
    }

    @PostMapping("/{id}/inativacao")
    ResponseEntity<Void> inativar(@PathVariable UUID id) {
        servico.inativar(id);
        return ResponseEntity.noContent().build();
    }

    // PUT: os mesmos campos do POST mais a version vista pelo gestor (RN-117).
    record Edicao(
            @NotNull Long version,
            @NotBlank @Size(max = 200) String razaoSocial,
            @NotBlank @Email @Size(max = 254) String emailContato,
            @NotNull @Valid Endereco endereco) {

        DadosCliente paraDados() {
            return new DadosCliente(razaoSocial, emailContato, endereco);
        }
    }

    record ClienteResponse(UUID id, String razaoSocial, String emailContato, Endereco endereco, boolean ativo, long version) {

        static ClienteResponse de(Cliente c) {
            return new ClienteResponse(c.getId(), c.getRazaoSocial(), c.getEmailContato(), c.getEndereco(), c.isAtivo(),
                    c.getVersion());
        }
    }
}
