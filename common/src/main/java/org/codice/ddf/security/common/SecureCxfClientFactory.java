/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
package org.codice.ddf.security.common;

import ddf.security.PropertiesLoader;
import ddf.security.service.SecurityServiceException;
import ddf.security.settings.SecuritySettingsService;
import ddf.security.sts.client.configuration.STSClientConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.cxf.Bus;
import org.apache.cxf.BusException;
import org.apache.cxf.common.util.CollectionUtils;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.EndpointException;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.jaxrs.client.Client;
import org.apache.cxf.jaxrs.client.ClientConfiguration;
import org.apache.cxf.jaxrs.client.JAXRSClientFactory;
import org.apache.cxf.jaxrs.client.JAXRSClientFactoryBean;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transport.https.HttpsURLConnectionFactory;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.trust.STSClient;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.codice.ddf.security.common.jaxrs.RestSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import javax.ws.rs.core.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class SecureCxfClientFactory<T> {

    protected static final String ADDRESSING_NAMESPACE = "http://www.w3.org/2005/08/addressing";

    private static final transient Logger LOGGER = LoggerFactory
            .getLogger(SecureCxfClientFactory.class);

    protected final Client cxfClient;

    private final SecuritySettingsService securitySettingsService;

    private final STSClientConfiguration stsClientConfig;

    private final String username;

    private final String password;

    public SecureCxfClientFactory(String endpointUrl, Class<T> interfaceClass, String username,
            String password, SecuritySettingsService securitySettingsService,
            STSClientConfiguration stsClientConfig) throws SecurityServiceException {
        cxfClient = WebClient.client(JAXRSClientFactory.create(endpointUrl, interfaceClass));

        this.username = username;
        this.password = password;
        this.securitySettingsService = securitySettingsService;
        this.stsClientConfig = stsClientConfig;

        initSecurity();
    }

    public SecureCxfClientFactory(String endpointUrl, Class<T> interfaceClass,
            ClassLoader classLoader, List<?> providers, boolean disableCnCheck, String username,
            String password, SecuritySettingsService securitySettingsService,
            STSClientConfiguration stsClientConfig) throws SecurityServiceException {
        if (StringUtils.isEmpty(endpointUrl)) {
            throw new IllegalArgumentException(
                    "Called without a valid URL, will not be able to connect.");
        }

        this.username = username;
        this.password = password;
        this.securitySettingsService = securitySettingsService;
        this.stsClientConfig = stsClientConfig;

        JAXRSClientFactoryBean clientFactoryBean = new JAXRSClientFactoryBean();
        clientFactoryBean.setServiceClass(interfaceClass);
        clientFactoryBean.setAddress(endpointUrl);
        clientFactoryBean.setClassLoader(classLoader);
        clientFactoryBean.getInInterceptors().add(new LoggingInInterceptor());
        clientFactoryBean.getOutInterceptors().add(new LoggingOutInterceptor());

        if (!CollectionUtils.isEmpty(providers)) {
            clientFactoryBean.setProviders(providers);
        }

        cxfClient = WebClient.client(clientFactoryBean.create(interfaceClass));
        initSecurity();

        if (disableCnCheck) {
            disableCnCheck();
        }
    }

    /**
     * The returned client is NOT reusable!
     * This method should be called for each request in order to ensure
     * that the security subject is up-to-date each time.
     */
    public T getClient() {
        WebClient newClient = WebClient.fromClient(cxfClient);

        Subject subject = SecurityUtils.getSubject();
        if (subject instanceof ddf.security.Subject) {
            RestSecurity.setSubjectOnClient((ddf.security.Subject) subject, newClient);
        }

        return (T) newClient;
    }

    private void disableCnCheck() throws SecurityServiceException {
        ClientConfiguration clientConfig = WebClient.getConfig(cxfClient);
        HTTPConduit httpConduit = clientConfig.getHttpConduit();
        if (httpConduit == null) {
            throw new SecurityServiceException(
                    "HTTPConduit was null for " + this + ". Unable to disable CN Check");
        }

        TLSClientParameters tlsParams = httpConduit.getTlsClientParameters();

        if (tlsParams == null) {
            tlsParams = new TLSClientParameters();
            httpConduit.setTlsClientParameters(tlsParams);
        }

        tlsParams.setDisableCNCheck(true);
    }

    /**
     * Add TLS and Basic Auth credentials to the underlying {@link org.apache.cxf.transport.http.HTTPConduit}
     * This includes two-way ssl assuming that the platform keystores are configured correctly
     */
    private void initSecurity() throws SecurityServiceException {
        ClientConfiguration clientConfig = WebClient.getConfig(cxfClient);

        HTTPConduit httpConduit = clientConfig.getHttpConduit();
        if (httpConduit == null) {
            throw new SecurityServiceException(
                    "HTTPConduit was null for " + this + ". Unable to configure security.");
        }

        if (StringUtils.isNotEmpty(username) && StringUtils.isNotEmpty(password)) {
            if (!StringUtils.startsWithIgnoreCase(httpConduit.getAddress(), "https")) {
                throw new SecurityServiceException(
                        "Cannot perform basic auth over non-https connection " + httpConduit
                                .getAddress());
            }
            if (httpConduit.getAuthorization() != null) {
                httpConduit.getAuthorization().setUserName(username);
                httpConduit.getAuthorization().setPassword(password);
            }
        }

        TLSClientParameters tlsParams = securitySettingsService.getTLSParameters();
        httpConduit.setTlsClientParameters(tlsParams);

        initSamlAssertion();
    }

    private void initSamlAssertion() throws SecurityServiceException {
        if (stsClientConfig == null || StringUtils.isBlank(stsClientConfig.getAddress())) {
            LOGGER.debug(
                    "STSClientConfiguration is either null or its address is blank - assuming no STS Client is configured, so no SAML assertion will get generated.");
            return;
        }
        ClientConfiguration clientConfig = WebClient.getConfig(cxfClient);
        Bus clientBus = clientConfig.getBus();
        STSClient stsClient = configureSTSClient(clientBus, stsClientConfig);
        try {
            SecurityToken securityToken = stsClient
                    .requestSecurityToken(stsClientConfig.getAddress());
            Element samlToken = securityToken.getToken();
            if (samlToken != null) {
                Cookie cookie = new Cookie(RestSecurity.SECURITY_COOKIE_NAME,
                        RestSecurity.encodeSaml(samlToken));
                cxfClient.reset();
                cxfClient.cookie(cookie);
            } else {
                LOGGER.debug(
                        "Attempt to retrieve SAML token resulted in null token - could not add token to request");
            }
        } catch (Exception e) {
            throw new SecurityServiceException("Exception trying to get SAML assertion", e);
        }
    }

    /**
     * Returns a new STSClient object configured with the properties that have
     * been set.
     *
     * @param bus - CXF bus to initialize STSClient with
     * @return STSClient
     */
    protected STSClient configureSTSClient(Bus bus, STSClientConfiguration stsClientConfig)
            throws SecurityServiceException {
        final String methodName = "configureSTSClient";
        LOGGER.debug("ENTERING: {}", methodName);

        String stsAddress = stsClientConfig.getAddress();
        String stsServiceName = stsClientConfig.getServiceName();
        String stsEndpointName = stsClientConfig.getEndpointName();
        String signaturePropertiesPath = stsClientConfig.getSignatureProperties();
        String encryptionPropertiesPath = stsClientConfig.getEncryptionProperties();
        String stsPropertiesPath = stsClientConfig.getTokenProperties();

        STSClient stsClient = new STSClient(bus);
        if (StringUtils.isBlank(stsAddress)) {
            LOGGER.debug("STS address is null, unable to create STS Client");
            LOGGER.debug("EXITING: {}", methodName);
            return stsClient;
        }
        LOGGER.debug("Setting WSDL location (stsAddress) on STSClient: " + stsAddress);
        stsClient.setWsdlLocation(stsAddress);
        LOGGER.debug("Setting service name on STSClient: " + stsServiceName);
        stsClient.setServiceName(stsServiceName);
        LOGGER.debug("Setting endpoint name on STSClient: " + stsEndpointName);
        stsClient.setEndpointName(stsEndpointName);
        LOGGER.debug("Setting addressing namespace on STSClient: " + ADDRESSING_NAMESPACE);
        stsClient.setAddressingNamespace(ADDRESSING_NAMESPACE);

        Map<String, Object> newStsProperties = new HashMap<>();

        // Properties loader should be able to find the properties file
        // no matter where it is
        if (signaturePropertiesPath != null && !signaturePropertiesPath.isEmpty()) {
            LOGGER.debug("Setting signature properties on STSClient: " + signaturePropertiesPath);
            Properties signatureProperties = PropertiesLoader
                    .loadProperties(signaturePropertiesPath);
            newStsProperties.put(SecurityConstants.SIGNATURE_PROPERTIES, signatureProperties);
        }
        if (encryptionPropertiesPath != null && !encryptionPropertiesPath.isEmpty()) {
            LOGGER.debug("Setting encryption properties on STSClient: " + encryptionPropertiesPath);
            Properties encryptionProperties = PropertiesLoader
                    .loadProperties(encryptionPropertiesPath);
            newStsProperties.put(SecurityConstants.ENCRYPT_PROPERTIES, encryptionProperties);
        }
        if (stsPropertiesPath != null && !stsPropertiesPath.isEmpty()) {
            LOGGER.debug("Setting sts properties on STSClient: " + stsPropertiesPath);
            Properties stsProperties = PropertiesLoader.loadProperties(stsPropertiesPath);
            newStsProperties.put(SecurityConstants.STS_TOKEN_PROPERTIES, stsProperties);
        }

        LOGGER.debug("Setting STS TOKEN USE CERT FOR KEY INFO to \"true\"");
        newStsProperties
                .put(SecurityConstants.STS_TOKEN_USE_CERT_FOR_KEYINFO, Boolean.TRUE.toString());
        stsClient.setProperties(newStsProperties);

        if (stsClient.getWsdlLocation()
                .startsWith(HttpsURLConnectionFactory.HTTPS_URL_PROTOCOL_ID)) {
            try {
                LOGGER.debug("Setting up SSL on the STSClient HTTP Conduit");
                HTTPConduit httpConduit = (HTTPConduit) stsClient.getClient().getConduit();
                if (httpConduit == null) {
                    LOGGER.info(
                            "HTTPConduit was null for stsClient. Unable to configure keystores for stsClient.");
                } else {
                    if (securitySettingsService != null) {
                        httpConduit
                                .setTlsClientParameters(securitySettingsService.getTLSParameters());
                    } else {
                        LOGGER.debug(
                                "Could not get reference to security settings, SSL communications will use system defaults.");
                    }

                }
            } catch (BusException e) {
                throw new SecurityServiceException("Unable to create sts client.", e);
            } catch (EndpointException e) {
                throw new SecurityServiceException("Unable to create sts client endpoint.", e);
            }
        }

        LOGGER.debug("EXITING: {}", methodName);

        stsClient.setTokenType(stsClientConfig.getAssertionType());
        stsClient.setKeyType(stsClientConfig.getKeyType());
        stsClient.setKeySize(Integer.valueOf(stsClientConfig.getKeySize()));

        return stsClient;
    }
}
