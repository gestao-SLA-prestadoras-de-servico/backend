package com.gestaosla.backend.identidade.api;

import java.util.List;
import java.util.UUID;

/** O que outros módulos podem saber dos usuários. Lê a cópia local do perfil: serve para consulta, nunca para autorizar (RN-130). */
public interface UsuarioConsulta {

    /** Subs dos gestores ativos, destinatários da notificação de violação de SLA (RN-39). */
    List<UUID> listarGestoresAtivos();
}
