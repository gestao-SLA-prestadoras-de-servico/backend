package com.gestaosla.backend.identidade.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.gestaosla.backend.identidade.api.Papel;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;

import lombok.RequiredArgsConstructor;

/**
 * Converte o JWT já validado (assinatura, iss, aud) em {@link UsuarioCorrente}. Os papéis vêm só de
 * {@code realm_access.roles} (RN-130); do registro local só se lê se o usuário existe e está ativo.
 * Rejeitar aqui, dentro da autenticação do token, faz o inativo sair como 401 sem filtro extra.
 */
@Component
@RequiredArgsConstructor
class UsuarioCorrenteConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UsuarioRepository usuarios;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID sub = UUID.fromString(jwt.getSubject());
        boolean ativo = usuarios.findBySub(sub).map(Usuario::isAtivo).orElse(false);
        if (!ativo) {
            throw new DisabledException("Usuário inativo ou sem cadastro no sistema.");
        }
        List<Papel> papeis = papeis(jwt);
        return new Autenticacao(new UsuarioCorrente(sub, papeis.isEmpty() ? null : papeis.getFirst(), null), jwt,
                papeis.stream().map(p -> new SimpleGrantedAuthority("ROLE_" + p)).toList());
    }

    // Ignora os papéis padrão do Keycloak (offline_access, uma_authorization, default-roles-flowsla).
    private static List<Papel> papeis(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return Arrays.stream(Papel.values()).filter(p -> roles.contains(p.name())).toList();
    }

    static class Autenticacao extends AbstractAuthenticationToken {

        private final UsuarioCorrente usuario;
        private final Jwt jwt;

        Autenticacao(UsuarioCorrente usuario, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.usuario = usuario;
            this.jwt = jwt;
            setAuthenticated(true);
        }

        @Override
        public UsuarioCorrente getPrincipal() {
            return usuario;
        }

        @Override
        public Jwt getCredentials() {
            return jwt;
        }

        @Override
        public String getName() {
            return usuario.sub().toString();
        }
    }
}
