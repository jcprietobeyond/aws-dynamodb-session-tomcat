/*
 * Copyright 2013 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.services.dynamodb.sessionmanager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.*;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodb.sessionmanager.converters.SessionConverter;
import com.amazonaws.services.dynamodb.sessionmanager.util.DynamoUtils;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.util.StringUtils;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.session.PersistentManagerBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import java.io.File;

/**
 * Tomcat persistent session manager implementation that uses Amazon DynamoDB to store HTTP session
 * data.
 */
public class DynamoDBSessionManager extends PersistentManagerBase {

    public static final String DEFAULT_TABLE_NAME = "Tomcat_SessionState";

    private static final String USER_AGENT = "DynamoSessionManager/2.0.1";
    private static final String NAME = "AmazonDynamoDBSessionManager";

    private String regionId = "eu-west-3";
    private String endpoint;
    private File credentialsFile;
    private String accessKey;
    private String secretKey;
    private long readCapacityUnits = 10;
    private long writeCapacityUnits = 5;
    private String tableName = DEFAULT_TABLE_NAME;
    private String proxyHost;
    private Integer proxyPort;
    private boolean deleteCorruptSessions = false;

    private static final Log logger = LogFactory.getLog(DynamoDBSessionManager.class);

    public DynamoDBSessionManager() {
        setSaveOnRestart(true);

        // MaxIdleBackup controls when sessions are persisted to the store
        setMaxIdleBackup(30); // 30 seconds
    }

    @Override
    public String getName() {
        return NAME;
    }

    public void setRegionId(String regionId) {
        this.regionId = regionId;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setAwsAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public void setAwsSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public void setAwsCredentialsFile(String credentialsFile) {
        this.credentialsFile = new File(credentialsFile);
    }

    public void setTable(String table) {
        this.tableName = table;
    }

    public void setReadCapacityUnits(int readCapacityUnits) {
        this.readCapacityUnits = readCapacityUnits;
    }

    public void setWriteCapacityUnits(int writeCapacityUnits) {
        this.writeCapacityUnits = writeCapacityUnits;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }

    public void setDeleteCorruptSessions(boolean deleteCorruptSessions) {
        this.deleteCorruptSessions = deleteCorruptSessions;
    }

    @Override
    protected void initInternal() throws LifecycleException {
        AmazonDynamoDB dynamoClient = createDynamoClient();
        initDynamoTable(dynamoClient);
        DynamoSessionStorage sessionStorage = createSessionStorage(dynamoClient);
        setStore(new DynamoDBSessionStore(sessionStorage, deleteCorruptSessions));
        new ExpiredSessionReaperExecutor(new ExpiredSessionReaper(sessionStorage));
    }

    private AmazonDynamoDB createDynamoClient() {
        AWSCredentialsProvider credentialsProvider = initCredentials();
        ClientConfiguration clientConfiguration = initClientConfiguration();
        AmazonDynamoDB dynamoClient;
        if (this.regionId != null && this.endpoint != null) {
            dynamoClient = getAmazonDynamoDBClientBuilder(credentialsProvider, clientConfiguration)
                    .withRegion(Regions.fromName(this.regionId))
                    .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, regionId))
                    .build();
        } else if (regionId != null) {
            dynamoClient = getAmazonDynamoDBClientBuilder(credentialsProvider, clientConfiguration)
                    .withRegion(Regions.fromName(this.regionId))
                    .build();
        } else {
            dynamoClient = getAmazonDynamoDBClientBuilder(credentialsProvider, clientConfiguration)
                    .build();
        }
        return dynamoClient;
    }

    private AmazonDynamoDBClientBuilder getAmazonDynamoDBClientBuilder(AWSCredentialsProvider credentialsProvider,
                                                                       ClientConfiguration clientConfiguration) {
        return AmazonDynamoDBClient.builder()
                .withCredentials(credentialsProvider)
                .withClientConfiguration(clientConfiguration);
    }

    private AWSCredentialsProvider initCredentials() {
        // Attempt to use any credentials specified in context.xml first
        if (credentialsExistInContextConfig()) {
            // Fail fast if credentials aren't valid as user has likely made a configuration mistake
            if (credentialsInContextConfigAreValid()) {
                throw new AmazonClientException("Incomplete AWS security credentials specified in context.xml.");
            }
            logger.debug("Using AWS access key ID and secret key from context.xml");
            return new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey));
        }

        // Use any explicitly specified credentials properties file next
        if (credentialsFile != null) {
            try {
                logger.debug("Reading security credentials from properties file: " + credentialsFile);
                PropertiesCredentials credentials = new PropertiesCredentials(credentialsFile);
                logger.debug("Using AWS credentials from file: " + credentialsFile);
                return new AWSStaticCredentialsProvider(credentials);
            } catch (Exception e) {
                throw new AmazonClientException(
                        "Unable to read AWS security credentials from file specified in context.xml: "
                                + credentialsFile,
                        e);
            }
        }

        // Fall back to the default credentials chain provider if credentials weren't explicitly set
        AWSCredentialsProvider defaultChainProvider = new DefaultAWSCredentialsProviderChain();
        if (defaultChainProvider.getCredentials() == null) {
            logger.debug("Loading security credentials from default credentials provider chain.");
            throw new AmazonClientException("Unable to find AWS security credentials.  "
                    + "Searched JVM system properties, OS env vars, and EC2 instance roles.  "
                    + "Specify credentials in Tomcat's context.xml file or put them in one of the places mentioned above.");
        }
        logger.debug("Using default AWS credentials provider chain to load credentials");
        return defaultChainProvider;
    }

    /**
     * @return True if the user has set their AWS credentials either partially or completely in
     *         context.xml. False otherwise
     */
    private boolean credentialsExistInContextConfig() {
        return accessKey != null || secretKey != null;
    }

    /**
     * @return True if both the access key and secret key were set in context.xml config. False
     *         otherwise
     */
    private boolean credentialsInContextConfigAreValid() {
        return StringUtils.isNullOrEmpty(accessKey) || StringUtils.isNullOrEmpty(secretKey);
    }

    private ClientConfiguration initClientConfiguration() {
        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setUserAgentPrefix(USER_AGENT);

        // Attempt to use an explicit proxy configuration
        if (proxyHost != null || proxyPort != null) {
            logger.debug("Reading proxy settings from context.xml");
            if (proxyHost == null || proxyPort == null) {
                throw new AmazonClientException("Incomplete proxy settings specified in context.xml."
                        + " Both proxy hot and proxy port needs to be specified");
            }
            logger.debug("Using proxy host and port from context.xml");
            clientConfiguration.withProxyHost(proxyHost).withProxyPort(proxyPort);
        }

        return clientConfiguration;
    }

    private void initDynamoTable(AmazonDynamoDB dynamo) {
        DynamoUtils.createSessionTable(dynamo, this.tableName, readCapacityUnits, writeCapacityUnits);

    }

    private DynamoSessionStorage createSessionStorage(AmazonDynamoDB dynamoClient) {
        DynamoDBMapper dynamoMapper = DynamoUtils.createDynamoMapper(dynamoClient, tableName);
        return new DynamoSessionStorage(dynamoMapper, getSessionConverter());
    }

    private SessionConverter getSessionConverter() {
        ClassLoader classLoader = getContext().getLoader().getClassLoader();
        return SessionConverter.createDefaultSessionConverter(this, classLoader);
    }

}
