package com.hampta.shop;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class MenuHolder implements InventoryHolder {
    enum Kind {
        CATEGORIES,
        ITEMS,
        AUCTION
    }

    final Kind kind;
    final UUID owner;
    final String category;
    final String query;
    final int page;
    final Map<Integer, Long> listingsBySlot = new HashMap<>();
    Inventory inventory;

    MenuHolder(Kind kind, UUID owner, String category, String query, int page) {
        this.kind = kind;
        this.owner = owner;
        this.category = category;
        this.query = query;
        this.page = page;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
