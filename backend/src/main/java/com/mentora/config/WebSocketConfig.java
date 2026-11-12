package com.mentora.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
  private final JwtService jwt;
  private final AppProps props;

  public WebSocketConfig(JwtService jwt, AppProps props) { this.jwt = jwt; this.props = props; }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/queue");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOriginPatterns(props.corsOrigins().split(","));
  }

  /** Authenticates CONNECT frames with the JWT. Clients may only subscribe to their own
   *  /user/queue/* destinations (resolved per-principal by Spring) and only send to /app/*. */
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new ChannelInterceptor() {
      @Override
      public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;
        StompCommand cmd = acc.getCommand();
        if (StompCommand.CONNECT.equals(cmd)) {
          String h = acc.getFirstNativeHeader("Authorization");
          String token = h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
          UserPrincipal p = token == null ? null : jwt.parse(token).orElse(null);
          if (p == null) throw new MessagingException("Unauthorized");
          acc.setUser(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
        } else if (StompCommand.SUBSCRIBE.equals(cmd)) {
          String d = acc.getDestination();
          if (acc.getUser() == null || d == null || !d.startsWith("/user/queue/")) throw new MessagingException("Forbidden destination");
        } else if (StompCommand.SEND.equals(cmd)) {
          String d = acc.getDestination();
          if (acc.getUser() == null || d == null || !d.startsWith("/app/")) throw new MessagingException("Forbidden destination");
        }
        return message;
      }
    });
  }
}
