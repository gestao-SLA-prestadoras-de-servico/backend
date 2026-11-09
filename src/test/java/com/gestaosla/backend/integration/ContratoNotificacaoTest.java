package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.outbox.api.DestinatarioEmail;
import com.gestaosla.backend.outbox.api.DestinatariosInternos;
import com.gestaosla.backend.outbox.api.EventoNotificacao;
import com.gestaosla.backend.outbox.api.EventoNotificacao.Canal;
import com.gestaosla.backend.outbox.api.EventoNotificacao.Chamado;
import com.gestaosla.backend.outbox.api.EventoNotificacao.Relogio;
import com.gestaosla.backend.outbox.api.EventoNotificacao.Tipo;
import com.gestaosla.backend.outbox.api.MontadorDestinatarios;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contratos das mensagens de notificação (outbox-04). Os JSON Schemas em contratos/notificacoes/ são gerados dos records
 * de {@link EventoNotificacao} e compartilhados com o ms-notificacoes: se o arquivo do repositório estiver diferente do
 * gerado, ele é reescrito e o teste falha, para o diff entrar no PR (como o OpenApiContratoTest).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ContratoNotificacaoTest {

    private static final Path PASTA = Path.of("contratos/notificacoes");
    // No draft 2020-12, "format" (uuid, email, date-time) é só anotação por padrão; aqui ele é conferido.
    private static final SchemaRegistry VALIDADOR = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            registro -> registro.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

    @Autowired
    private OutboxService outbox;

    @Autowired
    private MontadorDestinatarios montador;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    private UUID tecnico;
    private UUID gestor;
    private UUID usuarioCliente;
    private UUID cliente;

    @BeforeEach
    void cadastrar() {
        cliente = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (id, created_at, updated_at, razao_social, email_contato, logradouro, numero, bairro,
                                     cidade, uf, cep, ativo, version)
                values (?, now(), now(), 'Padaria Boa Massa', 'contato@boamassa.com.br', 'Rua A', '1', 'Centro',
                        'Fortaleza', 'CE', '60000000', true, 0)""", cliente);
        tecnico = usuario("TECNICO", null);
        gestor = usuario("GESTOR", null);
        usuarioCliente = usuario("CLIENTE", cliente);
    }

    @Test
    void shouldMatchVersionedSchemas() throws Exception {
        List<String> desatualizados = new ArrayList<>();
        for (Tipo tipo : Tipo.values()) {
            Path arquivo = arquivo(tipo);
            String gerado = json.writerWithDefaultPrettyPrinter().writeValueAsString(gerarSchema(tipo)) + "\n";
            String versionado = Files.exists(arquivo) ? Files.readString(arquivo) : "";
            if (!gerado.equals(versionado)) {
                Files.createDirectories(PASTA);
                Files.writeString(arquivo, gerado);
                desatualizados.add(arquivo.toString());
            }
        }
        if (!desatualizados.isEmpty()) {
            fail("Schemas desatualizados foram regenerados: " + desatualizados + ". Confira o diff e commite junto.");
        }
    }

    @ParameterizedTest
    @EnumSource(Tipo.class)
    void shouldStorePayloadThatMatchesVersionedSchema(Tipo tipo) throws Exception {
        UUID eventoId = UUID.randomUUID();

        transacao.executeWithoutResult(t -> outbox.registrar(exemplo(tipo), eventoId));

        Map<String, Object> linha = jdbc.queryForMap(
                "select tipo, exchange, routing_key, payload::text as payload from outbox_eventos where evento_id = ?",
                eventoId);
        assertThat(linha).containsEntry("tipo", tipo.name()).containsEntry("exchange", "notificacoes")
                .containsEntry("routing_key", tipo.canal().routingKey());
        JsonNode payload = json.readTree((String) linha.get("payload"));
        assertThat(schemaVersionado(tipo).validate(payload)).isEmpty();
        assertThat(payload.get("eventoId").asString()).isEqualTo(eventoId.toString());
        assertThat(payload.get("tipo").asString()).isEqualTo(tipo.name());
    }

    @Test
    void shouldRejectPayloadWithExtraFieldsLikeContractOrEvidenceData() throws Exception {
        ObjectNode payload = (ObjectNode) json.readTree(json.writeValueAsString(exemplo(Tipo.RESOLUCAO)));
        payload.put("eventoId", UUID.randomUUID().toString());
        assertThat(schemaVersionado(Tipo.RESOLUCAO).validate(payload)).isEmpty();

        payload.put("contratoId", UUID.randomUUID().toString());
        payload.put("evidenciaUrl", "https://minio/evidencia.jpg");

        assertThat(schemaVersionado(Tipo.RESOLUCAO).validate(payload)).hasSize(2);
    }

    @Test
    void shouldRejectViolationWithoutClockAndNoticeWithoutClosingDate() throws Exception {
        ObjectNode violacao = (ObjectNode) json.readTree(json.writeValueAsString(exemplo(Tipo.VIOLACAO)));
        violacao.put("eventoId", UUID.randomUUID().toString());
        violacao.remove("relogio");
        ObjectNode aviso = (ObjectNode) json.readTree(json.writeValueAsString(exemplo(Tipo.AVISO_ENCERRAMENTO)));
        aviso.put("eventoId", UUID.randomUUID().toString());
        aviso.put("encerramentoPrevistoEm", "amanhã");

        assertThat(schemaVersionado(Tipo.VIOLACAO).validate(violacao)).isNotEmpty();
        assertThat(schemaVersionado(Tipo.AVISO_ENCERRAMENTO).validate(aviso)).isNotEmpty();
    }

    @Test
    void shouldNeverDeclareContractOrEvidenceFieldsInAnySchema() throws Exception {
        for (Tipo tipo : Tipo.values()) {
            String schema = Files.readString(arquivo(tipo)).toLowerCase(Locale.ROOT);
            assertThat(schema).as(tipo.name()).doesNotContain("contrato").doesNotContain("evidencia");
        }
    }

    @Test
    void shouldTypeRecipientsByChannel() {
        // RN-41 no tipo: evento interno só aceita DestinatariosInternos; e-mail só aceita DestinatarioEmail.
        for (Tipo tipo : Tipo.values()) {
            Class<?> destinatarios = Arrays.stream(tipo.record().getRecordComponents())
                    .filter(c -> c.getName().equals("destinatarios")).findFirst().orElseThrow().getType();
            assertThat(destinatarios).as(tipo.name())
                    .isEqualTo(tipo.canal() == Canal.INTERNA ? DestinatariosInternos.class : DestinatarioEmail.class);
        }
    }

    @Test
    void shouldRejectClientAndUnknownSubsAsInternalRecipients() {
        assertThat(montador.internos(List.of(tecnico, gestor, tecnico)).subs()).containsExactly(tecnico, gestor);
        assertThatThrownBy(() -> montador.internos(List.of(tecnico, usuarioCliente)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(usuarioCliente.toString());
        UUID desconhecido = UUID.randomUUID();
        assertThatThrownBy(() -> montador.internos(List.of(desconhecido)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(desconhecido.toString());
        assertThatThrownBy(() -> montador.internos(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSendEmailOnlyToClientContactAddress() {
        assertThat(montador.emailDoCliente(cliente).emails()).containsExactly("contato@boamassa.com.br");
    }

    private EventoNotificacao exemplo(Tipo tipo) {
        Chamado chamado = new Chamado(UUID.randomUUID(), 1042);
        String titulo = "Impressora parada";
        return switch (tipo) {
            case ATRIBUICAO -> new EventoNotificacao.Atribuicao(chamado, titulo, montador.internos(List.of(tecnico)));
            case REMOCAO -> new EventoNotificacao.Remocao(chamado, titulo, montador.internos(List.of(tecnico)));
            case REABERTURA -> new EventoNotificacao.Reabertura(chamado, titulo, montador.internos(List.of(tecnico)));
            case CONCLUSAO -> new EventoNotificacao.Conclusao(chamado, titulo, montador.internos(List.of(gestor)));
            case VIOLACAO -> new EventoNotificacao.Violacao(chamado, titulo, montador.internos(List.of(gestor, tecnico)),
                    Relogio.ATENDIMENTO_CAMPO);
            case POSICIONAMENTO -> new EventoNotificacao.Posicionamento(chamado, titulo,
                    montador.emailDoCliente(cliente), "Técnico a caminho amanhã às 9h.");
            case RESOLUCAO -> new EventoNotificacao.Resolucao(chamado, titulo, montador.emailDoCliente(cliente));
            case AVISO_ENCERRAMENTO -> new EventoNotificacao.AvisoEncerramento(chamado, titulo,
                    montador.emailDoCliente(cliente), Instant.parse("2026-11-12T20:00:00Z"));
        };
    }

    private UUID usuario(String perfil, UUID clienteId) {
        UUID sub = UUID.randomUUID();
        jdbc.update("""
                insert into usuario (id, created_at, updated_at, sub, nome, email, perfil, ativo, cliente_id, version)
                values (?, now(), now(), ?, 'Usuário de teste', ?, ?, true, ?, 0)""",
                UUID.randomUUID(), sub, sub + "@teste.com", perfil, clienteId);
        return sub;
    }

    private static Path arquivo(Tipo tipo) {
        return PASTA.resolve(tipo.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".schema.json");
    }

    private Schema schemaVersionado(Tipo tipo) throws Exception {
        return VALIDADOR.getSchema(json.readTree(Files.readString(arquivo(tipo))));
    }

    /**
     * Schema do payload como a outbox o grava: os campos do record, {@code tipo} fixo e o {@code eventoId} acrescentado.
     * Todos obrigatórios e nenhum campo a mais (additionalProperties false).
     */
    private ObjectNode gerarSchema(Tipo tipo) {
        var config = new SchemaGeneratorConfigBuilder(json, SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonSchemaModule())
                .with(Option.EXTRA_OPEN_API_FORMAT_VALUES, Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        config.forFields().withRequiredCheck(campo -> true)
                .withStringMinLengthResolver(campo -> campo.getDeclaredType().getErasedType() == String.class ? 1 : null);
        config.forTypesInGeneral().withCustomDefinitionProvider((tipoJava, contexto) -> {
            Class<?> classe = tipoJava.getErasedType();
            if (classe != DestinatariosInternos.class && classe != DestinatarioEmail.class) {
                return null;
            }
            ObjectNode lista = contexto.getGeneratorConfig().createObjectNode().put("type", "array").put("minItems", 1);
            ObjectNode item = lista.putObject("items").put("type", "string");
            if (classe == DestinatariosInternos.class) {
                item.put("format", "uuid");
                lista.put("description", "Subs do Keycloak de técnicos e gestores (RN-41)");
            } else {
                item.put("format", "email");
                lista.put("maxItems", 1).put("description", "E-mail de contato do cliente (RN-08, RN-41)");
            }
            return new CustomDefinition(lista, CustomDefinition.DefinitionType.INLINE,
                    CustomDefinition.AttributeInclusion.NO);
        });
        ObjectNode schema = new SchemaGenerator(config.build()).generateSchema(tipo.record());
        schema.put("title", tipo.name());
        ObjectNode propriedades = (ObjectNode) schema.get("properties");
        propriedades.putObject("eventoId").put("type", "string").put("format", "uuid")
                .put("description", "Identificador do evento; os consumidores descartam repetições por ele (RN-55)");
        propriedades.putObject("tipo").put("const", tipo.name());
        var obrigatorios = schema.putArray("required");
        propriedades.propertyNames().forEach(obrigatorios::add);
        return schema;
    }
}
