package com.picturecreate.doccanvas.config;

import org.springframework.context.annotation.Profile;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Profile("!lite")
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties properties;

    public WebSocketConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = properties.cors() == null
                ? new String[] { "http://localhost:3000" }
                : properties.cors().allowedOrigins().toArray(String[]::new);
        String[] originPatterns = properties.cors() == null || properties.cors().allowedOriginPatterns() == null
                ? new String[] { "http://localhost:*", "http://127.0.0.1:*" }
                : properties.cors().allowedOriginPatterns().toArray(String[]::new);
        registry.addEndpoint("/ws").setAllowedOrigins(origins).setAllowedOriginPatterns(originPatterns).withSockJS();
    }
}
