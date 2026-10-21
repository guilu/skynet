package dev.skynet.controlplane.runner;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunnerProperties.class)
class RunnerConfiguration {

  /** Antes que la seguridad: un cuerpo enorme se corta sin llegar a leerse. */
  @Bean
  FilterRegistrationBean<RunnerRequestSizeFilter> runnerRequestSizeFilter(
      RunnerProperties properties) {
    FilterRegistrationBean<RunnerRequestSizeFilter> registration =
        new FilterRegistrationBean<>(
            new RunnerRequestSizeFilter(properties.maxRequest().toBytes()));
    registration.addUrlPatterns("/api/runner/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
