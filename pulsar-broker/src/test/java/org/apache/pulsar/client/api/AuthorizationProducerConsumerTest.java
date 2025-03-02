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

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.naming.AuthenticationException;

import lombok.Cleanup;

import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataCommand;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.authentication.AuthenticationProvider;
import org.apache.pulsar.broker.authorization.AuthorizationProvider;
import org.apache.pulsar.broker.authorization.AuthorizationService;
import org.apache.pulsar.broker.authorization.PulsarAuthorizationProvider;
import org.apache.pulsar.broker.cache.ConfigurationCacheService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class AuthorizationProducerConsumerTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationProducerConsumerTest.class);

    private final static String clientRole = "plugbleRole";
    private final static Set<String> clientAuthProviderSupportedRoles = Sets.newHashSet(clientRole);

    protected void setup() throws Exception {

        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationEnabled(true);

        Set<String> superUserRoles = new HashSet<>();
        superUserRoles.add("superUser");
        conf.setSuperUserRoles(superUserRoles);

        Set<String> providers = new HashSet<>();
        providers.add(TestAuthenticationProvider.class.getName());
        conf.setAuthenticationProviders(providers);

        conf.setClusterName("test");

        super.init();
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    /**
     * It verifies plugable authorization service
     *
     * <pre>
     * 1. Client passes correct authorization plugin-name + correct auth role: SUCCESS
     * 2. Client passes correct authorization plugin-name + incorrect auth-role: FAIL
     * 3. Client passes incorrect authorization plugin-name + correct auth-role: FAIL
     * </pre>
     *
     * @throws Exception
     */
    @Test
    public void testProducerAndConsumerAuthorization() throws Exception {
        log.info("-- Starting {} test --", methodName);

        conf.setAuthorizationProvider(TestAuthorizationProvider.class.getName());
        setup();

        Authentication adminAuthentication = new ClientAuthentication("superUser");

        @Cleanup
        PulsarAdmin admin = spy(
                PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString()).authentication(adminAuthentication).build());

        String lookupUrl;
        lookupUrl = new URI("pulsar://localhost:" + BROKER_PORT).toString();

        Authentication authentication = new ClientAuthentication(clientRole);
        Authentication authenticationInvalidRole = new ClientAuthentication("test-role");

        @Cleanup
        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(lookupUrl).authentication(authentication)
                .operationTimeout(1000, TimeUnit.MILLISECONDS).build();

        @Cleanup
        PulsarClient pulsarClientInvalidRole = PulsarClient.builder().serviceUrl(lookupUrl)
                .operationTimeout(1000, TimeUnit.MILLISECONDS)
                .authentication(authenticationInvalidRole).build();

        admin.clusters().createCluster("test", new ClusterData(brokerUrl.toString()));

        admin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("test")));
        admin.namespaces().createNamespace("my-property/my-ns", Sets.newHashSet("test"));

        // (1) Valid Producer and consumer creation
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://my-property/my-ns/my-topic")
                .subscriptionName("my-subscriber-name").subscribe();
        Producer<byte[]> producer = pulsarClient.newProducer().topic("persistent://my-property/my-ns/my-topic")
                .create();
        consumer.close();
        producer.close();

        // (2) InValid user auth-role will be rejected by authorization service
        try {
            consumer = pulsarClientInvalidRole.newConsumer().topic("persistent://my-property/my-ns/my-topic")
                    .subscriptionName("my-subscriber-name").subscribe();
            Assert.fail("should have failed with authorization error");
        } catch (PulsarClientException.AuthorizationException pa) {
            // Ok
        }
        try {
            producer = pulsarClientInvalidRole.newProducer().topic("persistent://my-property/my-ns/my-topic")
                    .create();
            Assert.fail("should have failed with authorization error");
        } catch (PulsarClientException.AuthorizationException pa) {
            // Ok
        }

        log.info("-- Exiting {} test --", methodName);
    }

    @Test
    public void testSubscriberPermission() throws Exception {
        log.info("-- Starting {} test --", methodName);

        conf.setAuthorizationProvider(PulsarAuthorizationProvider.class.getName());
        setup();

        final String tenantRole = "tenant-role";
        final String subscriptionRole = "sub1-role";
        final String subscriptionName = "sub1";
        final String namespace = "my-property/my-ns-sub-auth";
        final String topicName = "persistent://" + namespace + "/my-topic";
        Authentication adminAuthentication = new ClientAuthentication("superUser");

        clientAuthProviderSupportedRoles.add(subscriptionRole);

        @Cleanup
        PulsarAdmin superAdmin = spy(
                PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString()).authentication(adminAuthentication).build());

        Authentication tenantAdminAuthentication = new ClientAuthentication(tenantRole);
        @Cleanup
        PulsarAdmin tenantAdmin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString())
                .authentication(tenantAdminAuthentication).build());

        Authentication subAdminAuthentication = new ClientAuthentication(subscriptionRole);
        @Cleanup
        PulsarAdmin sub1Admin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString())
                .authentication(subAdminAuthentication).build());

        String lookupUrl;
        lookupUrl = new URI("pulsar://localhost:" + BROKER_PORT).toString();

        Authentication authentication = new ClientAuthentication(subscriptionRole);

        superAdmin.clusters().createCluster("test", new ClusterData(brokerUrl.toString()));

        superAdmin.tenants().createTenant("my-property",
                new TenantInfo(Sets.newHashSet(tenantRole), Sets.newHashSet("test")));
        superAdmin.namespaces().createNamespace(namespace, Sets.newHashSet("test"));
        tenantAdmin.namespaces().grantPermissionOnNamespace(namespace, subscriptionRole,
                Collections.singleton(AuthAction.consume));

        pulsarClient = PulsarClient.builder().serviceUrl(lookupUrl).authentication(authentication).build();
        // (1) Create subscription name
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName(subscriptionName)
                .subscribe();
        consumer.close();

        // verify tenant is able to perform all subscription-admin api
        tenantAdmin.topics().skipAllMessages(topicName, subscriptionName);
        tenantAdmin.topics().skipMessages(topicName, subscriptionName, 1);
        tenantAdmin.topics().expireMessages(topicName, subscriptionName, 10);
        tenantAdmin.topics().peekMessages(topicName, subscriptionName, 1);
        tenantAdmin.topics().resetCursor(topicName, subscriptionName, 10);
        tenantAdmin.topics().resetCursor(topicName, subscriptionName, MessageId.earliest);

        // grant namespace-level authorization to the subscriptionRole
        tenantAdmin.namespaces().grantPermissionOnNamespace(namespace, subscriptionRole,
                Collections.singleton(AuthAction.consume));

        // subscriptionRole has namespace-level authorization
        sub1Admin.topics().resetCursor(topicName, subscriptionName, 10);

        // grant subscription access to specific different role and only that role can access the subscription
        String otherPrincipal = "Principal-1-to-access-sub";
        superAdmin.namespaces().grantPermissionOnSubscription(namespace, subscriptionName,
                Collections.singleton(otherPrincipal));

        // now, subscriptionRole doesn't have subscription level access so, it will fail to access subscription
        try {
            sub1Admin.topics().resetCursor(topicName, subscriptionName, 10);
            fail("should have fail with authorization exception");
        } catch (org.apache.pulsar.client.admin.PulsarAdminException.NotAuthorizedException e) {
            // Ok
        }

        // now, grant subscription-access to subscriptionRole as well
        superAdmin.namespaces().grantPermissionOnSubscription(namespace, subscriptionName,
                Sets.newHashSet(otherPrincipal, subscriptionRole));

        sub1Admin.topics().skipAllMessages(topicName, subscriptionName);
        sub1Admin.topics().skipMessages(topicName, subscriptionName, 1);
        sub1Admin.topics().expireMessages(topicName, subscriptionName, 10);
        sub1Admin.topics().peekMessages(topicName, subscriptionName, 1);
        sub1Admin.topics().resetCursor(topicName, subscriptionName, 10);
        sub1Admin.topics().resetCursor(topicName, subscriptionName, MessageId.earliest);

        superAdmin.namespaces().revokePermissionOnSubscription(namespace, subscriptionName, subscriptionRole);

        try {
            sub1Admin.topics().resetCursor(topicName, subscriptionName, 10);
            fail("should have fail with authorization exception");
        } catch (org.apache.pulsar.client.admin.PulsarAdminException.NotAuthorizedException e) {
            // Ok
        }

        log.info("-- Exiting {} test --", methodName);
    }

    @Test
    public void testSubscriptionPrefixAuthorization() throws Exception {
        log.info("-- Starting {} test --", methodName);

        conf.setAuthorizationProvider(TestAuthorizationProviderWithSubscriptionPrefix.class.getName());
        setup();

        Authentication adminAuthentication = new ClientAuthentication("superUser");
        @Cleanup
        PulsarAdmin admin = spy(
                PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString()).authentication(adminAuthentication).build());

        String lookupUrl;
        lookupUrl = new URI("pulsar://localhost:" + BROKER_PORT).toString();

        Authentication authentication = new ClientAuthentication(clientRole);

        pulsarClient = PulsarClient.builder().serviceUrl(lookupUrl).authentication(authentication).build();

        admin.clusters().createCluster("test", new ClusterData(brokerUrl.toString()));

        admin.tenants().createTenant("prop-prefix",
                new TenantInfo(Sets.newHashSet("appid1", "appid2"), Sets.newHashSet("test")));
        admin.namespaces().createNamespace("prop-prefix/ns", Sets.newHashSet("test"));

        // (1) Valid subscription name will be approved by authorization service
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://prop-prefix/ns/t1")
                .subscriptionName(clientRole + "-sub1").subscribe();
        consumer.close();

        // (2) InValid subscription name will be rejected by authorization service
        try {
            consumer = pulsarClient.newConsumer().topic("persistent://prop-prefix/ns/t1").subscriptionName("sub1")
                    .subscribe();
            Assert.fail("should have failed with authorization error");
        } catch (PulsarClientException.AuthorizationException pa) {
            // Ok
        }

        log.info("-- Exiting {} test --", methodName);
    }

    @Test
    public void testGrantPermission() throws Exception {
        log.info("-- Starting {} test --", methodName);

        conf.setAuthorizationProvider(TestAuthorizationProviderWithGrantPermission.class.getName());
        setup();

        AuthorizationService authorizationService = new AuthorizationService(conf, null);
        TopicName topicName = TopicName.get("persistent://prop/cluster/ns/t1");
        String role = "test-role";
        Assert.assertFalse(authorizationService.canProduce(topicName, role, null));
        Assert.assertFalse(authorizationService.canConsume(topicName, role, null, "sub1"));
        authorizationService.grantPermissionAsync(topicName, null, role, "auth-json").get();
        Assert.assertTrue(authorizationService.canProduce(topicName, role, null));
        Assert.assertTrue(authorizationService.canConsume(topicName, role, null, "sub1"));

        log.info("-- Exiting {} test --", methodName);
    }

    @Test
    public void testAuthData() throws Exception {
        log.info("-- Starting {} test --", methodName);

        conf.setAuthorizationProvider(TestAuthorizationProviderWithGrantPermission.class.getName());
        setup();

        AuthorizationService authorizationService = new AuthorizationService(conf, null);
        TopicName topicName = TopicName.get("persistent://prop/cluster/ns/t1");
        String role = "test-role";
        authorizationService.grantPermissionAsync(topicName, null, role, "auth-json").get();
        Assert.assertEquals(TestAuthorizationProviderWithGrantPermission.authDataJson, "auth-json");
        Assert.assertTrue(authorizationService.canProduce(topicName, role, new AuthenticationDataCommand("prod-auth")));
        Assert.assertEquals(TestAuthorizationProviderWithGrantPermission.authenticationData.getCommandData(),
                "prod-auth");
        Assert.assertTrue(
                authorizationService.canConsume(topicName, role, new AuthenticationDataCommand("cons-auth"), "sub1"));
        Assert.assertEquals(TestAuthorizationProviderWithGrantPermission.authenticationData.getCommandData(),
                "cons-auth");

        log.info("-- Exiting {} test --", methodName);
    }

    public static class ClientAuthentication implements Authentication {
        String user;

        public ClientAuthentication(String user) {
            this.user = user;
        }

        @Override
        public void close() throws IOException {
            // No-op
        }

        @Override
        public String getAuthMethodName() {
            return "test";
        }

        @Override
        public AuthenticationDataProvider getAuthData() throws PulsarClientException {
            AuthenticationDataProvider provider = new AuthenticationDataProvider() {
                public boolean hasDataForHttp() {
                    return true;
                }

                @SuppressWarnings("unchecked")
                public Set<Map.Entry<String, String>> getHttpHeaders() {
                    return Sets.newHashSet(Maps.immutableEntry("user", user));
                }

                public boolean hasDataFromCommand() {
                    return true;
                }

                public String getCommandData() {
                    return user;
                }
            };
            return provider;
        }

        @Override
        public void configure(Map<String, String> authParams) {
            // No-op
        }

        @Override
        public void start() throws PulsarClientException {
            // No-op
        }

    }

    public static class TestAuthenticationProvider implements AuthenticationProvider {

        @Override
        public void close() throws IOException {
            // no-op
        }

        @Override
        public void initialize(ServiceConfiguration config) throws IOException {
            // No-op
        }

        @Override
        public String getAuthMethodName() {
            return "test";
        }

        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            return authData.getCommandData() != null ? authData.getCommandData() : authData.getHttpHeader("user");
        }

    }

    public static class TestAuthorizationProvider implements AuthorizationProvider {

        public ServiceConfiguration conf;

        @Override
        public void close() throws IOException {
            // No-op
        }

        @Override
        public CompletableFuture<Boolean> isSuperUser(String role, ServiceConfiguration serviceConfiguration) {
            Set<String> superUserRoles = serviceConfiguration.getSuperUserRoles();
            return CompletableFuture.completedFuture(role != null && superUserRoles.contains(role) ? true : false);
        }

        @Override
        public void initialize(ServiceConfiguration conf, ConfigurationCacheService configCache) throws IOException {
            this.conf = conf;
            // No-op
        }

        @Override
        public CompletableFuture<Boolean> canProduceAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData) {
            return CompletableFuture.completedFuture(clientAuthProviderSupportedRoles.contains(role));
        }

        @Override
        public CompletableFuture<Boolean> canConsumeAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData, String subscription) {
            return CompletableFuture.completedFuture(clientAuthProviderSupportedRoles.contains(role));
        }

        @Override
        public CompletableFuture<Boolean> canLookupAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData) {
            return CompletableFuture.completedFuture(clientAuthProviderSupportedRoles.contains(role));
        }

        @Override
        public CompletableFuture<Boolean> allowFunctionOpsAsync(NamespaceName namespaceName, String role, AuthenticationDataSource authenticationData) {
            return null;
        }

        @Override
        public CompletableFuture<Void> grantPermissionAsync(NamespaceName namespace, Set<AuthAction> actions,
                String role, String authenticationData) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> grantPermissionAsync(TopicName topicname, Set<AuthAction> actions, String role,
                String authenticationData) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> grantSubscriptionPermissionAsync(NamespaceName namespace,
                String subscriptionName, Set<String> roles, String authDataJson) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> revokeSubscriptionPermissionAsync(NamespaceName namespace,
                String subscriptionName, String role, String authDataJson) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * This provider always fails authorization on consumer and passes on producer
     *
     */
    public static class TestAuthorizationProvider2 extends TestAuthorizationProvider {

        @Override
        public CompletableFuture<Boolean> canProduceAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> canConsumeAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData, String subscription) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Boolean> canLookupAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData) {
            return CompletableFuture.completedFuture(true);
        }
    }

    public static class TestAuthorizationProviderWithSubscriptionPrefix extends TestAuthorizationProvider {

        @Override
        public CompletableFuture<Boolean> canConsumeAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData, String subscription) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            if (isNotBlank(subscription)) {
                if (!subscription.startsWith(role)) {
                    future.completeExceptionally(new PulsarServerException(
                            "The subscription name needs to be prefixed by the authentication role"));
                }
            }
            future.complete(clientRole.equals(role));
            return future;
        }

    }

    public static class TestAuthorizationProviderWithGrantPermission extends TestAuthorizationProvider {

        private Set<String> grantRoles = Sets.newHashSet();
        static AuthenticationDataSource authenticationData;
        static String authDataJson;

        @Override
        public CompletableFuture<Boolean> canProduceAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData) {
            this.authenticationData = authenticationData;
            return CompletableFuture.completedFuture(grantRoles.contains(role));
        }

        @Override
        public CompletableFuture<Boolean> canConsumeAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData, String subscription) {
            this.authenticationData = authenticationData;
            return CompletableFuture.completedFuture(grantRoles.contains(role));
        }

        @Override
        public CompletableFuture<Boolean> canLookupAsync(TopicName topicName, String role,
                AuthenticationDataSource authenticationData) {
            this.authenticationData = authenticationData;
            return CompletableFuture.completedFuture(grantRoles.contains(role));
        }

        @Override
        public CompletableFuture<Void> grantPermissionAsync(NamespaceName namespace, Set<AuthAction> actions,
                String role, String authData) {
            this.authDataJson = authData;
            grantRoles.add(role);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> grantPermissionAsync(TopicName topicname, Set<AuthAction> actions, String role,
                String authData) {
            this.authDataJson = authData;
            grantRoles.add(role);
            return CompletableFuture.completedFuture(null);
        }
    }

}
