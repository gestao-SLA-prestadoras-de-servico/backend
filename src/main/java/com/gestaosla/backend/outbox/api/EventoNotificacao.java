package com.gestaosla.backend.outbox.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Contrato das mensagens de notificação (RN-53, RN-58): o ms-notificacoes não consulta o app-principal, então tudo de que
 * ele precisa vai aqui. Os JSON Schemas ficam em {@code contratos/notificacoes/}, gerados destes records pelo
 * {@code ContratoNotificacaoTest}. O {@code eventoId} é acrescentado pela outbox ao gravar.
 *
 * <p>Nunca carrega dado de contrato nem de evidência. Os destinatários só saem do {@link MontadorDestinatarios}: sub de
 * técnico ou gestor no canal interno, e-mail de contato do cliente no e-mail (RN-41).
 */
public sealed interface EventoNotificacao {

    /** Canal de entrega; a routing key é a da topologia (infra/rabbitmq/topologia.json). */
    enum Canal {
        INTERNA("interna"), EMAIL("email");

        private final String routingKey;

        Canal(String routingKey) {
            this.routingKey = routingKey;
        }

        public String routingKey() {
            return routingKey;
        }
    }

    /** Tipo do evento e o canal em que ele sai. ATRIBUICAO cobre também a reatribuição (RF-53). */
    enum Tipo {
        ATRIBUICAO(Atribuicao.class, Canal.INTERNA),
        REMOCAO(Remocao.class, Canal.INTERNA),
        REABERTURA(Reabertura.class, Canal.INTERNA),
        CONCLUSAO(Conclusao.class, Canal.INTERNA),
        VIOLACAO(Violacao.class, Canal.INTERNA),
        POSICIONAMENTO(Posicionamento.class, Canal.EMAIL),
        RESOLUCAO(Resolucao.class, Canal.EMAIL),
        AVISO_ENCERRAMENTO(AvisoEncerramento.class, Canal.EMAIL);

        private final Class<? extends EventoNotificacao> record;
        private final Canal canal;

        Tipo(Class<? extends EventoNotificacao> record, Canal canal) {
            this.record = record;
            this.canal = canal;
        }

        public Class<? extends EventoNotificacao> record() {
            return record;
        }

        public Canal canal() {
            return canal;
        }

        static Tipo de(EventoNotificacao evento) {
            return Arrays.stream(values()).filter(t -> t.record == evento.getClass()).findFirst().orElseThrow();
        }
    }

    /** Relógio de SLA violado (RN-23 a RN-25). */
    enum Relogio {
        RESPOSTA, ATENDIMENTO_CAMPO, RESOLUCAO
    }

    /** Identificador interno e número legível do chamado (o ms-notificacoes exibe {@code #numero}). */
    record Chamado(UUID id, long numero) {
        public Chamado {
            Objects.requireNonNull(id);
        }
    }

    Chamado chamado();

    String titulo();

    @JsonProperty("tipo")
    default Tipo tipo() {
        return Tipo.de(this);
    }

    /** Técnico atribuído ou reatribuído ao chamado (RF-53). */
    record Atribuicao(Chamado chamado, String titulo, DestinatariosInternos destinatarios) implements EventoNotificacao {
        public Atribuicao {
            exigir(chamado, titulo, destinatarios);
        }
    }

    /** Técnico anterior, removido por reatribuição; sem acesso ao chamado (RF-55, RN-37). */
    record Remocao(Chamado chamado, String titulo, DestinatariosInternos destinatarios) implements EventoNotificacao {
        public Remocao {
            exigir(chamado, titulo, destinatarios);
        }
    }

    /** Técnico atribuído, quando o cliente reabre o chamado (RF-54). */
    record Reabertura(Chamado chamado, String titulo, DestinatariosInternos destinatarios) implements EventoNotificacao {
        public Reabertura {
            exigir(chamado, titulo, destinatarios);
        }
    }

    /** Gestores com a preferência ativa no chamado, quando o técnico conclui o atendimento (RF-57). */
    record Conclusao(Chamado chamado, String titulo, DestinatariosInternos destinatarios) implements EventoNotificacao {
        public Conclusao {
            exigir(chamado, titulo, destinatarios);
        }
    }

    /** Um aviso por relógio violado, a todos os gestores e ao técnico atribuído (RF-58, RN-39). */
    record Violacao(Chamado chamado, String titulo, DestinatariosInternos destinatarios, Relogio relogio)
            implements EventoNotificacao {
        public Violacao {
            exigir(chamado, titulo, destinatarios);
            Objects.requireNonNull(relogio);
        }
    }

    /** E-mail ao cliente com o primeiro posicionamento do gestor (RF-52, RN-53). */
    record Posicionamento(Chamado chamado, String titulo, DestinatarioEmail destinatarios, String textoPosicionamento)
            implements EventoNotificacao {
        public Posicionamento {
            exigir(chamado, titulo, destinatarios);
            if (textoPosicionamento == null || textoPosicionamento.isBlank()) {
                throw new IllegalArgumentException("O posicionamento precisa de texto.");
            }
        }
    }

    /** E-mail ao cliente quando o chamado é marcado como resolvido (RF-52). */
    record Resolucao(Chamado chamado, String titulo, DestinatarioEmail destinatarios) implements EventoNotificacao {
        public Resolucao {
            exigir(chamado, titulo, destinatarios);
        }
    }

    /** E-mail ao cliente 1 dia útil antes da validação automática (RN-34, RN-92). */
    record AvisoEncerramento(Chamado chamado, String titulo, DestinatarioEmail destinatarios,
            Instant encerramentoPrevistoEm) implements EventoNotificacao {
        public AvisoEncerramento {
            exigir(chamado, titulo, destinatarios);
            Objects.requireNonNull(encerramentoPrevistoEm);
        }
    }

    private static void exigir(Chamado chamado, String titulo, Object destinatarios) {
        Objects.requireNonNull(chamado);
        Objects.requireNonNull(destinatarios);
        if (titulo == null || titulo.isBlank()) {
            throw new IllegalArgumentException("O evento precisa do título do chamado.");
        }
    }
}
