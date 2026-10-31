package dev.skynet.controlplane.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Todo exige sesión salvo la salud, el login, leer la paleta, la API del runner, que lleva su
 * propio token, y los webhooks de GitHub, que van firmados. Las peticiones con cabecera {@code
 * Authorization} (HTTP Basic de un script, o el runner) no usan cookies y no necesitan CSRF. Un 401
 * nunca lleva {@code WWW-Authenticate}, para que el navegador no abra su diálogo de usuario y
 * contraseña.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts) {
    HttpStatusEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
    http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/api/runner/**", "/api/auth/login", "/actuator/health/**", "/error")
                    .permitAll()
                    // Los webhooks de GitHub: los protege su firma (X-Hub-Signature-256).
                    .requestMatchers(HttpMethod.POST, "/api/github/webhooks")
                    .permitAll()
                    // La paleta se lee antes de entrar, para pintar el login con ella.
                    .requestMatchers(HttpMethod.GET, "/api/settings/appearance")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .securityContext(context -> context.securityContextRepository(contexts))
        .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
        .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
        .csrf(
            csrf ->
                csrf.spa()
                    // El registro del runner aún no lleva token, pero tampoco cookies.
                    .ignoringRequestMatchers("/api/runner/**", "/api/github/webhooks")
                    .ignoringRequestMatchers(
                        new RequestHeaderRequestMatcher(HttpHeaders.AUTHORIZATION)))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .cors(Customizer.withDefaults())
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler(
                        new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .formLogin(form -> form.disable())
        .requestCache(cache -> cache.disable())
        .headers(Customizer.withDefaults());
    return http.build();
  }

  @Bean
  AuthThrottle authThrottle(SecurityProperties properties) {
    return new AuthThrottle(
        properties.maxFailedAttempts(), properties.lockout(), java.time.Clock.systemUTC());
  }

  /** Antes que Spring Security, para ver el 401 de un login o de un HTTP Basic fallido. */
  @Bean
  FilterRegistrationBean<AuthThrottleFilter> authThrottleFilter(AuthThrottle throttle) {
    FilterRegistrationBean<AuthThrottleFilter> registration =
        new FilterRegistrationBean<>(new AuthThrottleFilter(throttle));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
    return registration;
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService users(SecurityProperties properties, PasswordEncoder encoder) {
    String password = properties.password();
    if (password == null || password.isBlank()) {
      if (properties.requirePassword()) {
        throw new IllegalStateException(
            "Define SKYNET_ADMIN_PASSWORD: es la contraseña del usuario '"
                + properties.username()
                + "' de la web");
      }
      password = generatedPassword();
      Path file = properties.generatedPasswordFile().toAbsolutePath();
      writeOwnerOnly(file, password);
      // Nunca la contraseña: el log puede acabar en un agregador o en una captura.
      log.warn(
          "Sin SKYNET_ADMIN_PASSWORD: la contraseña generada para el usuario '{}' está en {}",
          properties.username(),
          file);
    }
    return new InMemoryUserDetailsManager(
        User.withUsername(properties.username())
            .password(encoder.encode(password))
            .roles("ADMIN")
            .build());
  }

  @Bean
  AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (!properties.corsOrigins().isEmpty()) {
      CorsConfiguration cors = new CorsConfiguration();
      cors.setAllowedOrigins(properties.corsOrigins());
      cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
      cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Last-Event-ID"));
      cors.setAllowCredentials(true);
      source.registerCorsConfiguration("/api/**", cors);
    }
    return source;
  }

  private static void writeOwnerOnly(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.deleteIfExists(file);
      if (Files.getFileStore(file.getParent())
          .supportsFileAttributeView(PosixFileAttributeView.class)) {
        Files.createFile(
            file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } else {
        Files.createFile(file);
      }
      Files.writeString(file, content + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(
          "No se pudo guardar la contraseña generada en "
              + file
              + "; define SKYNET_ADMIN_PASSWORD o SKYNET_ADMIN_PASSWORD_FILE",
          e);
    }
  }

  private static String generatedPassword() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
