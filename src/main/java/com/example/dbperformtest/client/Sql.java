package com.example.dbperformtest.client;

/** SQL shared by both clients so they run identical statements. */
final class Sql {

    private Sql() {
    }

    static String byCustomer(String table) {
        return "DECLARE $cid STRING; SELECT * FROM " + table + " WHERE customerId = $cid";
    }

    static String byAmount(String table) {
        return "DECLARE $lo DOUBLE; $hi DOUBLE; SELECT * FROM " + table + " WHERE amount >= $lo AND amount < $hi";
    }
}
