/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.api;

import static org.mockito.Mockito.spy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.impl.auth.AuthenticationTls;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import com.google.common.collect.Sets;

public class TlsProducerConsumerBase extends ProducerConsumerBase {
    protected final String TLS_TRUST_CERT_FILE_PATH = "./src/test/resources/authentication/tls/cacert.pem";
    protected final String TLS_CLIENT_CERT_FILE_PATH = "./src/test/resources/authentication/tls/client-cert.pem";
    protected final String TLS_CLIENT_KEY_FILE_PATH = "./src/test/resources/authentication/tls/client-key.pem";
    protected final String TLS_SERVER_CERT_FILE_PATH = "./src/test/resources/authentication/tls/broker-cert.pem";
    protected final String TLS_SERVER_KEY_FILE_PATH = "./src/test/resources/authentication/tls/broker-key.pem";
    private final String clusterName = "use";

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        // TLS configuration for Broker
        internalSetUpForBroker();

        // Start Broker
        super.init();
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    protected void internalSetUpForBroker() throws Exception {
        conf.setBrokerServicePortTls(Optional.of(BROKER_PORT_TLS));
        conf.setWebServicePortTls(Optional.of(BROKER_WEBSERVICE_PORT_TLS));
        conf.setTlsCertificateFilePath(TLS_SERVER_CERT_FILE_PATH);
        conf.setTlsKeyFilePath(TLS_SERVER_KEY_FILE_PATH);
        conf.setTlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);
        conf.setClusterName(clusterName);
        conf.setTlsRequireTrustedClientCertOnConnect(true);
        Set<String> tlsProtocols = Sets.newConcurrentHashSet();
        tlsProtocols.add("TLSv1.2");
        conf.setTlsProtocols(tlsProtocols);
    }

    protected void internalSetUpForClient(boolean addCertificates, String lookupUrl) throws Exception {
        if (pulsarClient != null) {
            pulsarClient.close();
        }

        ClientBuilder clientBuilder = PulsarClient.builder().serviceUrl(lookupUrl)
                .tlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH).enableTls(true).allowTlsInsecureConnection(false)
                .operationTimeout(1000, TimeUnit.MILLISECONDS);
        if (addCertificates) {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
            authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);
            clientBuilder.authentication(AuthenticationTls.class.getName(), authParams);
        }
        pulsarClient = clientBuilder.build();
    }

    protected void internalSetUpForNamespace() throws Exception {
        Map<String, String> authParams = new HashMap<>();
        authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
        authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);

        if (admin != null) {
            admin.close();
        }

        admin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrlTls.toString())
                .tlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH).allowTlsInsecureConnection(false)
                .authentication(AuthenticationTls.class.getName(), authParams).build());
        admin.clusters().createCluster(clusterName, new ClusterData(brokerUrl.toString(), brokerUrlTls.toString(),
                "pulsar://localhost:" + BROKER_PORT, "pulsar+ssl://localhost:" + BROKER_PORT_TLS));
        admin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("use")));
        admin.namespaces().createNamespace("my-property/my-ns");
    }
}
