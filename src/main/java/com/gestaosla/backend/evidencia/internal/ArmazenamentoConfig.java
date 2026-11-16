package com.gestaosla.backend.evidencia.internal;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArmazenamentoProperties.class)
class ArmazenamentoConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(ArmazenamentoProperties p) {
        return S3Client.builder()
                .endpointOverride(URI.create(p.endpoint()))
                .region(Region.US_EAST_1) // o MinIO ignora a região, mas o SDK exige uma
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(p.connectTimeout())
                        .socketTimeout(p.readTimeout()))
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(p.apiCallTimeout()).build())
                .build();
    }
}
