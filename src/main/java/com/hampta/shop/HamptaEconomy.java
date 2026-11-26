package com.hampta.shop;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

final class HamptaEconomy implements AutoCloseable {
    enum Result {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        INVALID_AMOUNT,
        ERROR
    }

    private final Connection connection;
    private final double startingBalance;
    private final double maximumBalance;

    HamptaEconomy(File file, double startingBalance, double maximumBalance) throws SQLException {
        if (!Double.isFinite(startingBalance) || startingBalance < 0
                || !Double.isFinite(maximumBalance) || maximumBalance <= 0
                || startingBalance > maximumBalance) {
            throw new IllegalArgumentException("Invalid economy starting or maximum balance.");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create plugin data directory.");
        }
        this.startingBalance = startingBalance;
        this.maximumBalance = maximumBalance;
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try {
            try (Statement statement = opened.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS accounts (
                          player_uuid TEXT PRIMARY KEY,
                          balance REAL NOT NULL CHECK(balance >= 0)
                        )
                        """);
            }
        } catch (SQLException exception) {
            try {
                opened.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
        connection = opened;
    }

    synchronized double getBalance(UUID playerId) throws SQLException {
        ensureAccount(playerId);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Account was not created for " + playerId);
                }
                return result.getDouble("balance");
            }
        }
    }

    synchronized Result deposit(UUID playerId, double amount) throws SQLException {
        if (!Double.isFinite(amount) || amount < 0) {
            return Result.INVALID_AMOUNT;
        }
        if (amount > maximumBalance) {
            return Result.BALANCE_LIMIT;
        }
        if (amount == 0) {
            return Result.SUCCESS;
        }
        ensureAccount(playerId);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = balance + ? "
                        + "WHERE player_uuid = ? AND balance <= ?")) {
            statement.setDouble(1, amount);
            statement.setString(2, playerId.toString());
            statement.setDouble(3, maximumBalance - amount);
            if (statement.executeUpdate() == 1) {
                return Result.SUCCESS;
            }
        }
        return Result.BALANCE_LIMIT;
    }

    synchronized Result withdraw(UUID playerId, double amount) throws SQLException {
        if (!Double.isFinite(amount) || amount < 0) {
            return Result.INVALID_AMOUNT;
        }
        if (amount == 0) {
            return Result.SUCCESS;
        }
        ensureAccount(playerId);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = balance - ? "
                        + "WHERE player_uuid = ? AND balance >= ?")) {
            statement.setDouble(1, amount);
            statement.setString(2, playerId.toString());
            statement.setDouble(3, amount);
            if (statement.executeUpdate() == 1) {
                return Result.SUCCESS;
            }
        }
        return Result.INSUFFICIENT_FUNDS;
    }

    synchronized Result transfer(UUID from, UUID to, double amount) throws SQLException {
        if (!Double.isFinite(amount) || amount <= 0 || amount > maximumBalance || from.equals(to)) {
            return Result.INVALID_AMOUNT;
        }
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ensureAccount(from);
            ensureAccount(to);
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE accounts SET balance = balance - ? "
                            + "WHERE player_uuid = ? AND balance >= ?");
                 PreparedStatement credit = connection.prepareStatement(
                         "UPDATE accounts SET balance = balance + ? "
                                 + "WHERE player_uuid = ? AND balance <= ?")) {
                debit.setDouble(1, amount);
                debit.setString(2, from.toString());
                debit.setDouble(3, amount);
                if (debit.executeUpdate() != 1) {
                    connection.rollback();
                    return Result.INSUFFICIENT_FUNDS;
                }
                credit.setDouble(1, amount);
                credit.setString(2, to.toString());
                credit.setDouble(3, maximumBalance - amount);
                if (credit.executeUpdate() != 1) {
                    connection.rollback();
                    return Result.BALANCE_LIMIT;
                }
            }
            connection.commit();
            return Result.SUCCESS;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    synchronized Result setBalance(UUID playerId, double amount) throws SQLException {
        if (!Double.isFinite(amount) || amount < 0) {
            return Result.INVALID_AMOUNT;
        }
        if (amount > maximumBalance) {
            return Result.BALANCE_LIMIT;
        }
        ensureAccount(playerId);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = ? WHERE player_uuid = ?")) {
            statement.setDouble(1, amount);
            statement.setString(2, playerId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Could not set account balance for " + playerId);
            }
        }
        return Result.SUCCESS;
    }

    double maximumBalance() {
        return maximumBalance;
    }

    private void ensureAccount(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO accounts(player_uuid, balance) VALUES(?, ?)")) {
            statement.setString(1, playerId.toString());
            statement.setDouble(2, startingBalance);
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
