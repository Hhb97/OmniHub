package com.omnihub.iot.integration.ws;

import com.omnihub.iot.config.CorsProperties;
import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;

@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private final WsHandler handler;
    private final WsAuthInterceptor interceptor;
    private final CorsProperties props;

    public WsConfig(WsHandler handler, WsAuthInterceptor interceptor, CorsProperties props) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(props.getCorsAllowedOrigins().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
        // empty => same-origin only (Spring default)
        registry.addHandler(handler, "/ws").addInterceptors(interceptor).setAllowedOrigins(origins);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(4096);
        c.setMaxSessionIdleTimeout(30L * 60 * 1000);
        return c;
    }
}
