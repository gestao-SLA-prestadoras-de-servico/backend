package com.gestaosla.backend.identidade.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gestaosla.backend.shared.api.Papel;

/** O que outros módulos podem saber dos usuários. Lê a cópia local do perfil: serve para consulta, nunca para autorizar (RN-130). */
public interface UsuarioConsulta {

    /** Subs dos gestores ativos, destinatários da notificação de violação de SLA (RN-39). */
    List<UUID> listarGestoresAtivos();

    /** Perfil de cada sub com registro local; sub sem registro fica fora do mapa. Filtra destinatários (RN-41). */
    Map<UUID, Papel> perfis(Collection<UUID> subs);
}
