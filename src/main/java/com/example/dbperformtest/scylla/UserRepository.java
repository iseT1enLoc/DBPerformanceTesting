package com.example.dbperformtest.scylla;

import com.example.dbperformtest.scylla.model.User;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;
import java.util.Optional;

public class UserRepository {

    static final String TABLE = "users";

    private final DynamoDbClient db;

    public UserRepository(DynamoDbClient db) {
        this.db = db;
    }

    public void createTableIfNotExists() {
        try {
            db.describeTable(r -> r.tableName(TABLE));
        } catch (ResourceNotFoundException e) {
            db.createTable(CreateTableRequest.builder()
                    .tableName(TABLE)
                    .keySchema(
                            KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());

            db.waiter().waitUntilTableExists(r -> r.tableName(TABLE));
            System.out.println("Table created: " + TABLE);
        }
    }

    public void put(User user) {
        db.putItem(PutItemRequest.builder()
                .tableName(TABLE)
                .item(Map.of(
                        "id",    AttributeValue.fromS(user.id()),
                        "name",  AttributeValue.fromS(user.name()),
                        "email", AttributeValue.fromS(user.email())))
                .build());
    }

    public Optional<User> get(String id) {
        GetItemResponse response = db.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("id", AttributeValue.fromS(id)))
                .build());

        if (!response.hasItem()) return Optional.empty();

        var item = response.item();
        return Optional.of(new User(
                item.get("id").s(),
                item.get("name").s(),
                item.get("email").s()));
    }

    public void delete(String id) {
        db.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("id", AttributeValue.fromS(id)))
                .build());
    }
}
