package dev.skynet.controlplane.runner;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunnerProperties.class)
class RunnerConfiguration {}
