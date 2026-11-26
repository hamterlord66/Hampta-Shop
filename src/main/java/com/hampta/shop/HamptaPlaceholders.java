package com.hampta.shop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

final class HamptaPlaceholders extends PlaceholderExpansion {
    private final HamptaShopPlugin plugin;

    HamptaPlaceholders(HamptaShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "hamptashop";
    }

    @Override
    public String getAuthor() {
        return "hamterlord66";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String parameters) {
        if (parameters.equalsIgnoreCase("balance") && player != null) {
            return plugin.getEconomy().format(plugin.getEconomy().getBalance(player));
        }
        return null;
    }
}
