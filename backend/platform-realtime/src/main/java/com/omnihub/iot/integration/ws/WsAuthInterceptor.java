package com.omnihub.iot.integration.ws;

import com.omnihub.iot.identity.core.JwtService;
import com.omnihub.iot.system.service.AccountService;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Rejects the WebSocket handshake unless ?token= carries a valid JWT of an enabled user. */
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER = "loginUser";

    private final JwtService jwtService;
    private final AccountService accountService;

    public WsAuthInterceptor(JwtService jwtService, AccountService accountService) {
        this.jwtService = jwtService;
        this.accountService = accountService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            return false;
        }
        String token = servlet.getServletRequest().getParameter("token");
        if (token == null || token.isBlank()) {
            return false;
        }
        return jwtService.parse(token).flatMap(p -> accountService.loadLoginUser(p.userId())).map(u -> {
            attributes.put(ATTR_USER, u);
            return true;
        }).orElse(false);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception exception) {
    }
}
