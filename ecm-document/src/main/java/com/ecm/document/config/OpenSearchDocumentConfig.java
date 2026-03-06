package com.ecm.document.config;

import org.apache.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenSearchDocumentConfig {

    @Value("${ecm.opensearch.host:localhost}")
    private String host;

    @Value("${ecm.opensearch.port:9200}")
    private int port;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient openSearchClient() {
        return new RestHighLevelClient(
                RestClient.builder(new HttpHost(host, port, "http")));
    }
}