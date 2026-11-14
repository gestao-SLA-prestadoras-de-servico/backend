package com.gestaosla.backend;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Massa de teste por SQL: cliente, contrato vigente com a política Padrão, chamado e usuário. Serve aos testes que
 * precisam de um chamado sem passar pela abertura (os do sla, por causa da FK, e os do modelo do chamado).
 */
public final class ChamadoTeste {

    public static final UUID POLITICA_PADRAO = UUID.fromString("0199a000-0000-7000-8000-00000000c001");
    public static final UUID VERSAO_PADRAO = UUID.fromString("0199a000-0000-7000-8000-00000000c002");

    private ChamadoTeste() {
    }

    public static UUID cliente(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (id, created_at, updated_at, razao_social, email_contato, logradouro, numero, bairro,
                                     cidade, uf, cep, ativo, version)
                values (?, now(), now(), 'Cliente', 'c@x.com', 'Rua A', '1', 'Centro', 'Fortaleza', 'CE', '60000000', true, 0)""", id);
        return id;
    }

    /** Contrato vigente de 2000 a 2100, com a política Padrão. */
    public static UUID contrato(JdbcTemplate jdbc, UUID cliente) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into contrato (id, created_at, updated_at, cliente_id, codigo, inicio_vigencia, fim_vigencia, politica_id)
                values (?, now(), now(), ?, ?, '2000-01-01', '2100-12-31', ?)""", id, cliente, "CT-" + id, POLITICA_PADRAO);
        return id;
    }

    /** Chamado ABERTO de um cliente novo, sem técnico, com a versão 1 da Padrão (prioridade padrão NORMAL). */
    public static UUID chamado(JdbcTemplate jdbc) {
        return chamado(jdbc, cliente(jdbc), "ABERTO", null);
    }

    public static UUID chamado(JdbcTemplate jdbc, UUID cliente, String estado, UUID tecnicoSub) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into chamado (id, created_at, updated_at, contrato_id, cliente_id, politica_versao_id, prioridade_padrao,
                                     titulo, descricao, local, estado, tecnico_sub, aberto_em, version)
                values (?, now(), now(), ?, ?, ?, 'NORMAL', 'Ar-condicionado parado', 'Não liga', 'Sala 3', ?, ?, now(), 0)""",
                id, contrato(jdbc, cliente), cliente, VERSAO_PADRAO, estado, tecnicoSub);
        return id;
    }

    /** Usuário local ativo; devolve o sub. */
    public static String usuario(JdbcTemplate jdbc, String perfil, UUID empresa) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id)
                values (?, now(), now(), ?, 'Usuário', ?, ?, true, ?)""", UUID.randomUUID(), sub, sub + "@x.com", perfil, empresa);
        return sub.toString();
    }
}
