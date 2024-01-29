package com.dinehub.common.security;


import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Turns a bearer token into an authenticated principal.
 *
 * <p>The principal is the user's UUID, so a controller can take
 * {@code @AuthenticationPrincipal UUID userId} and never has to parse the token
 * itself.
 *
 * <p>A bad token is not an error here — the filter simply leaves the context
 * unauthenticated and lets the authorisation rules decide. That keeps public
 * endpoints working when a client sends a stale token.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwt.parseAccessToken(header.substring(7));
                UUID userId = UUID.fromString(claims.getSubject());
                String role = claims.get(JwtService.CLAIM_ROLE, String.class);

                var authentication = new UsernamePasswordAuthenticationToken(
                        userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Puts the user on every log line for this request.
                MDC.put("userId", userId.toString());
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            // Threads are pooled. A context left behind leaks one user's
            // identity into the next request on that thread.
            SecurityContextHolder.clearContext();
        }
    }
}
