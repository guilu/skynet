package dev.skynet.controlplane.artifact;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArtifactProperties.class)
class ArtifactConfiguration {

  @Bean
  BlobStore blobStore(ArtifactProperties properties) {
    return new FileSystemBlobStore(properties.root());
  }
}
