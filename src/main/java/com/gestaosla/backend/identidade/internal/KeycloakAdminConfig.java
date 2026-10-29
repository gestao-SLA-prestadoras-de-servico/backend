package com.gestaosla.backend.identidade.internal;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminConfig {

    // Builder do Boot: as chamadas aparecem em http.client.requests no Prometheus.
    @Bean
    RestClient keycloakAdminRestClient(RestClient.Builder builder, KeycloakAdminProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.serverUrl()).requestFactory(requestFactory).build();
    }
}
