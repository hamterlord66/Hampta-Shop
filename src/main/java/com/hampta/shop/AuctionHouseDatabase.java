package com.hampta.shop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AuctionHouseDatabase implements AutoCloseable {
    private final Connection connection;

    AuctionHouseDatabase(File file) throws SQLException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create plugin data directory.");
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS listings (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      seller_uuid TEXT NOT NULL,
                      seller_name TEXT NOT NULL,
                      item_yaml TEXT NOT NULL,
                      price REAL NOT NULL,
                      created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS mailbox (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      owner_uuid TEXT NOT NULL,
                      item_yaml TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS listings_expiry ON listings(expires_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS listings_seller ON listings(seller_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS mailbox_owner ON mailbox(owner_uuid)");
        }
    }

    List<AuctionListing> listActive(int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM listings WHERE expires_at > ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            return readListings(statement);
        }
    }

    List<AuctionListing> listBySeller(UUID sellerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM listings WHERE seller_uuid = ? AND expires_at > ? ORDER BY created_at DESC")) {
            statement.setString(1, sellerId.toString());
            statement.setLong(2, System.currentTimeMillis());
            return readListings(statement);
        }
    }

    int countBySeller(UUID sellerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM listings WHERE seller_uuid = ? AND expires_at > ?")) {
            statement.setString(1, sellerId.toString());
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    long create(UUID sellerId, String sellerName, ItemStack item, double price, long expiresAt) throws SQLException {
        String sql = "INSERT INTO listings(seller_uuid, seller_name, item_yaml, price, created_at, expires_at) "
                + "VALUES(?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            statement.setString(1, sellerId.toString());
            statement.setString(2, sellerName);
            statement.setString(3, serialize(item));
            statement.setDouble(4, price);
            statement.setLong(5, now);
            statement.setLong(6, expiresAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return a listing ID.");
                }
                return keys.getLong(1);
            }
        }
    }

    AuctionListing take(long id) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            AuctionListing listing;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM listings WHERE id = ? AND expires_at > ?")) {
                select.setLong(1, id);
                select.setLong(2, System.currentTimeMillis());
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return null;
                    }
                    listing = readListing(result);
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM listings WHERE id = ?")) {
                delete.setLong(1, id);
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
            }
            connection.commit();
            return listing;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    AuctionListing get(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM listings WHERE id = ? AND expires_at > ?")) {
            statement.setLong(1, id);
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readListing(result) : null;
            }
        }
    }

    boolean delete(long id, UUID sellerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM listings WHERE id = ? AND seller_uuid = ? AND expires_at > ?")) {
            statement.setLong(1, id);
            statement.setString(2, sellerId.toString());
            statement.setLong(3, System.currentTimeMillis());
            return statement.executeUpdate() == 1;
        }
    }

    void expireListings() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            String selectSql = "SELECT id, seller_uuid, item_yaml FROM listings WHERE expires_at <= ?";
            try (PreparedStatement select = connection.prepareStatement(selectSql);
                 PreparedStatement mailboxInsert = connection.prepareStatement(
                         "INSERT INTO mailbox(owner_uuid, item_yaml) VALUES(?, ?)");
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM listings WHERE expires_at <= ?")) {
                select.setLong(1, System.currentTimeMillis());
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        String itemYaml = result.getString("item_yaml");
                        mailboxInsert.setString(1, result.getString("seller_uuid"));
                        mailboxInsert.setString(2, itemYaml);
                        mailboxInsert.addBatch();
                    }
                }
                mailboxInsert.executeBatch();
                delete.setLong(1, System.currentTimeMillis());
                delete.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    void addToMailbox(UUID ownerId, String itemYaml) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO mailbox(owner_uuid, item_yaml) VALUES(?, ?)")) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, itemYaml);
            statement.executeUpdate();
        }
    }

    List<ItemStack> claimMailbox(UUID ownerId) throws SQLException {
        List<ItemStack> items = new ArrayList<>();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, item_yaml FROM mailbox WHERE owner_uuid = ?");
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM mailbox WHERE owner_uuid = ?")) {
                select.setString(1, ownerId.toString());
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        items.add(deserialize(result.getString("item_yaml")));
                    }
                }
                delete.setString(1, ownerId.toString());
                delete.executeUpdate();
            }
            connection.commit();
            return items;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private List<AuctionListing> readListings(PreparedStatement statement) throws SQLException {
        List<AuctionListing> listings = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                listings.add(readListing(result));
            }
        }
        return listings;
    }

    private AuctionListing readListing(ResultSet result) throws SQLException {
        return new AuctionListing(
                result.getLong("id"),
                UUID.fromString(result.getString("seller_uuid")),
                result.getString("seller_name"),
                deserialize(result.getString("item_yaml")),
                result.getDouble("price"),
                result.getLong("created_at"),
                result.getLong("expires_at"));
    }

    private String serialize(ItemStack item) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        return yaml.saveToString();
    }

    private ItemStack deserialize(String yamlText) throws SQLException {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(yamlText);
            ItemStack item = yaml.getItemStack("item");
            if (item == null || item.getType().isAir()) {
                throw new IllegalArgumentException("Serialized auction item is empty.");
            }
            return item;
        } catch (Exception exception) {
            throw new SQLException("Could not deserialize an auction item.", exception);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
