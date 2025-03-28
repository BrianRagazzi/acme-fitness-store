package com.example.acme.assist.config;

import io.pivotal.cfenv.core.CfCredentials;
import io.pivotal.cfenv.core.CfEnv;
import io.pivotal.cfenv.core.CfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class ChatConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ChatConfiguration.class);

    private static final String MCP_SERVICE_URL = "mcpServiceURL";

    @Bean
    public List<String> mcpServiceURLs() {
        CfEnv cfEnv = new CfEnv();
        List<CfService> cfServices = cfEnv.findAllServices();
        List<String> mcpServiceURLs = new ArrayList<>();

        for (CfService cfService : cfServices) {
            CfCredentials cfCredentials = cfService.getCredentials();
            String mcpServiceUrl = cfCredentials.getString(MCP_SERVICE_URL);
            if (mcpServiceUrl != null) {
                mcpServiceURLs.add(mcpServiceUrl);
                logger.info("Bound to MCP Service: {}", mcpServiceUrl);
            }
        }

        return mcpServiceURLs;
    }

    @Bean
    public SSLContext sslContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCertificates = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCertificates, new java.security.SecureRandom());
        return sslContext;
    }

}
