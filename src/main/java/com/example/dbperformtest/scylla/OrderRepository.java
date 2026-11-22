package com.example.dbperformtest.scylla;

import com.example.dbperformtest.scylla.model.Order;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OrderRepository {

    static final String TABLE = "orders";

    private final DynamoDbClient db;

    public OrderRepository(DynamoDbClient db) {
        this.db = db;
    }

    public void createTableIfNotExists() {
        try {
            db.describeTable(r -> r.tableName(TABLE));
        } catch (ResourceNotFoundException e) {
            db.createTable(CreateTableRequest.builder()
                    .tableName(TABLE)
                    .keySchema(
                            KeySchemaElement.builder().attributeName("customerId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.RANGE).build())
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("customerId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("orderId").attributeType(ScalarAttributeType.S).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());

            db.waiter().waitUntilTableExists(r -> r.tableName(TABLE));
            System.out.println("Table created: " + TABLE);
        }
    }

    public void put(Order order) {
        db.putItem(PutItemRequest.builder()
                .tableName(TABLE)
                .item(Map.of(
                        "customerId", AttributeValue.fromS(order.customerId()),
                        "orderId",    AttributeValue.fromS(order.orderId()),
                        "amount",     AttributeValue.fromS(order.amount())))
                .build());
    }

    public Optional<Order> get(String customerId, String orderId) {
        GetItemResponse response = db.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of(
                        "customerId", AttributeValue.fromS(customerId),
                        "orderId",    AttributeValue.fromS(orderId)))
                .build());

        if (!response.hasItem()) return Optional.empty();

        var item = response.item();
        return Optional.of(new Order(
                item.get("customerId").s(),
                item.get("orderId").s(),
                item.get("amount").s()));
    }

    public List<Order> queryByCustomer(String customerId) {
        QueryResponse response = db.query(QueryRequest.builder()
                .tableName(TABLE)
                .keyConditionExpression("customerId = :cid")
                .expressionAttributeValues(Map.of(
                        ":cid", AttributeValue.fromS(customerId)))
                .build());

        return response.items().stream()
                .map(item -> new Order(
                        item.get("customerId").s(),
                        item.get("orderId").s(),
                        item.get("amount").s()))
                .toList();
    }

    public void delete(String customerId, String orderId) {
        db.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of(
                        "customerId", AttributeValue.fromS(customerId),
                        "orderId",    AttributeValue.fromS(orderId)))
                .build());
    }
}
