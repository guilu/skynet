package dev.skynet.controlplane.github;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GitHubProperties.class, WebhookProperties.class})
class GitHubConfiguration {}
