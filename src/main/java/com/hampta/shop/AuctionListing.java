package com.hampta.shop;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

record AuctionListing(long id, UUID sellerId, String sellerName, ItemStack item, double price,
                      long createdAt, long expiresAt) {
}
