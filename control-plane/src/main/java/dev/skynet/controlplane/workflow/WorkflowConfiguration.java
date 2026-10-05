package dev.skynet.controlplane.workflow;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentDefaults.class, MonitoringProperties.class})
class WorkflowConfiguration {}
