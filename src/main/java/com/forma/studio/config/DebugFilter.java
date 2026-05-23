package com.forma.studio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class DebugFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String auth = request.getHeader("Authorization");
        String method = request.getMethod();
        String uri = request.getRequestURI();

        System.out.println("=== DEBUG REQUEST ===");
        System.out.println("Method: " + method);
        System.out.println("URI: " + uri);
        System.out.println("Authorization header: " + auth);
        System.out.println("====================");

        filterChain.doFilter(request, response);
    }
}