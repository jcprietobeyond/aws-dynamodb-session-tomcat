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
package com.amazonaws.services.dynamodb.sessionmanager.util;

import com.amazonaws.services.dynamodb.sessionmanager.DynamoSessionItem;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig.TableNameOverride;
import com.amazonaws.services.dynamodbv2.model.*;
import com.amazonaws.services.dynamodbv2.util.TableUtils;

public class DynamoUtils {

    private DynamoUtils() {

    }

    public static void createSessionTable(AmazonDynamoDB dynamo,
                                          String tableName,
                                          long readCapacityUnits,
                                          long writeCapacityUnits) {
        CreateTableRequest request = new CreateTableRequest().withTableName(tableName);

        request.withKeySchema(new KeySchemaElement().withAttributeName(DynamoSessionItem.SESSION_ID_ATTRIBUTE_NAME)
                .withKeyType(KeyType.HASH));

        request.withAttributeDefinitions(
                new AttributeDefinition().withAttributeName(DynamoSessionItem.SESSION_ID_ATTRIBUTE_NAME)
                        .withAttributeType(ScalarAttributeType.S));

        request.setProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(readCapacityUnits)
                .withWriteCapacityUnits(writeCapacityUnits));
        TableUtils.createTableIfNotExists(dynamo, request);
    }

    /**
     * Create a new DynamoDBMapper with table name override
     */
    public static DynamoDBMapper createDynamoMapper(AmazonDynamoDB dynamoDbClient, String tableName) {
        return new DynamoDBMapper(dynamoDbClient, DynamoDBMapperConfig.builder()
                .withConsistentReads(DynamoDBMapperConfig.ConsistentReads.CONSISTENT)
                .withTableNameOverride(new TableNameOverride(tableName))
                .build());
    }

}
