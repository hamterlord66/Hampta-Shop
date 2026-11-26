package com.hampta.shop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class HamptaShopPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int PAGE_SIZE = 45;
    private Economy economy;
    private AuctionHouseDatabase auctionDatabase;
    private BukkitTask expiryTask;
    private YamlConfiguration messages;
    private YamlConfiguration presets;
    private File presetsFile;
    private YamlConfiguration playerData;
    private File playerDataFile;
    private final Map<UUID, SellConfirmation> sellConfirmations = new HashMap<>();
    private final Map<UUID, AuctionBuyConfirmation> auctionBuyConfirmations = new HashMap<>();

    private record SellConfirmation(Map<String, Integer> amounts, boolean hotbarOnly, long expiresAt) {
    }

    private record AuctionBuyConfirmation(long listingId, long expiresAt) {
    }
    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("presets.yml", false);
        messages = YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "messages.yml"));
        presetsFile = new File(getDataFolder(), "presets.yml");
        presets = YamlConfiguration.loadConfiguration(presetsFile);
        playerDataFile = new File(getDataFolder(), "players.yml");
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
        setupEconomy();
        if (!isEnabled()) {
            return;
        }
        try {
            auctionDatabase = new AuctionHouseDatabase(new java.io.File(getDataFolder(), "auctions.db"));
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not open the auction-house database.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String commandName : List.of("shop", "sell", "ah", "hamptashop", "ec", "craft", "anvil")) {
            PluginCommand command = getCommand(commandName);
            if (command == null) {
                getLogger().severe("Missing command registration: " + commandName);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new HamptaPlaceholders(this).register();
        }
        expiryTask = Bukkit.getScheduler().runTaskTimer(this, this::expireListings, 1200L, 1200L);
        getLogger().info("Hampta Shop is ready.");
    }

    @Override
    public void onDisable() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        if (auctionDatabase != null) {
            try {
                auctionDatabase.close();
            } catch (SQLException exception) {
                getLogger().log(Level.WARNING, "Could not close the auction-house database cleanly.", exception);
            }
        }
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("Vault is required.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("No Vault economy provider is installed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = registration.getProvider();
    }

    Economy getEconomy() {
        return economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "shop" -> handleShop(sender, args);
            case "sell" -> handleSell(sender, args);
            case "ah" -> handleAuction(sender, args);
            case "hamptashop" -> handleAdmin(sender, args);
            case "ec" -> handleUtility(sender, "enderchest");
            case "craft" -> handleUtility(sender, "crafting");
            case "anvil" -> handleUtility(sender, "anvil");
            default -> false;
        };
    }

    private boolean handleShop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("hamptashop.use")) {
            send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            openCategories(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "search" -> {
                if (args.length < 2) {
                    send(player, "search-prompt");
                } else {
                    openShopItems(player, null, String.join(" ", Arrays.copyOfRange(args, 1, args.length)), 0);
                }
                return true;
            }
            case "favorites", "favourites" -> {
                openShopItems(player, null, "", 0, "name", true);
                return true;
            }
            case "favorite", "favourite" -> {
                String favoriteId = args.length == 2 ? findItemId(args[1]) : null;
                if (favoriteId == null) {
                    send(player, "invalid-usage", "usage", "/shop favorite <item-id>");
                    return true;
                }
                toggleFavorite(player, favoriteId);
                return true;
            }
            case "buy" -> {
                if (args.length < 2 || args.length > 3) {
                    send(player, "invalid-usage", "usage", "/shop buy <item-id> [amount]");
                    return true;
                }
                String id = findItemId(args[1]);
                int amount = args.length == 3 ? parseAmount(args[2]) : 1;
                if (id == null) {
                    send(player, "unknown-item");
                } else if (amount < 1 || amount > 2304) {
                    send(player, "invalid-number");
                } else {
                    buyFromShop(player, id, amount);
                }
                return true;
            }
            case "sell" -> {
                if (args.length < 2 || args.length > 3) {
                    send(player, "invalid-usage", "usage", "/shop sell <item-id> [amount|all]");
                    return true;
                }
                String id = findItemId(args[1]);
                int amount = args.length == 2 || args[2].equalsIgnoreCase("all")
                        ? Integer.MAX_VALUE : parseAmount(args[2]);
                if (!player.hasPermission("hamptashop.sell")) {
                    send(player, "no-permission");
                } else if (id == null || amount < 1) {
                    send(player, id == null ? "unknown-item" : "invalid-number");
                } else if (sellMatching(player, id, amount) == 0) {
                    send(player, "sell-empty");
                }
                return true;
            }
            case "sort" -> {
                if (args.length != 2 || !List.of("name", "buy", "sell").contains(args[1].toLowerCase(Locale.ROOT))) {
                    send(player, "invalid-usage", "usage", "/shop sort <name|buy|sell>");
                } else {
                    openShopItems(player, null, "", 0, args[1].toLowerCase(Locale.ROOT), false);
                }
                return true;
            }
            default -> {
                String category = findCategory(args[0]);
                if (category == null) {
                    send(player, "invalid-usage", "usage",
                            "/shop [category|search <words>|buy|sell|favorite|favorites|sort]");
                } else {
                    openShopItems(player, category, "", 0);
                }
                return true;
            }
        }
    }

    private boolean handleSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("hamptashop.sell")) {
            send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            send(player, "invalid-usage", "usage",
                    "/sell <hand [amount]|all|hotbar|category <name>|preview|confirm|cancel>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hand" -> {
                if (args.length > 2) {
                    send(player, "invalid-usage", "usage", "/sell hand [amount]");
                    return true;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                String itemId = findShopItem(hand);
                int amount = args.length == 2 ? parseAmount(args[1]) : hand.getAmount();
                if (itemId == null) {
                    send(player, "unknown-item");
                } else if (amount < 1) {
                    send(player, "invalid-number");
                } else {
                    sellHeld(player, itemId, hand, amount);
                }
            }
            case "all", "hotbar", "category" -> {
                if (args[0].equalsIgnoreCase("category") && args.length != 2) {
                    send(player, "invalid-usage", "usage", "/sell category <category>");
                    return true;
                }
                if (args[0].equalsIgnoreCase("all") && args.length == 2
                        && args[1].equalsIgnoreCase("confirm")) {
                    confirmSell(player);
                    return true;
                }
                if (args.length != 1 && !args[0].equalsIgnoreCase("category")) {
                    send(player, "invalid-usage", "usage", "/sell all|hotbar|category <name>");
                    return true;
                }
                String category = args[0].equalsIgnoreCase("category") ? findCategory(args[1]) : null;
                if (args[0].equalsIgnoreCase("category") && category == null) {
                    send(player, "admin-invalid-category");
                    return true;
                }
                requestSellConfirmation(player, category, args[0].equalsIgnoreCase("hotbar"));
            }
            case "preview" -> sendSellPreview(player);
            case "confirm" -> confirmSell(player);
            case "cancel" -> {
                sellConfirmations.remove(player.getUniqueId());
                send(player, "sell-cancelled");
            }
            default -> {
                send(player, "invalid-usage", "usage",
                        "/sell <hand [amount]|all|hotbar|category <name>|preview|confirm|cancel>");
            }
        }
        return true;
    }

    private boolean handleUtility(CommandSender sender, String kind) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        String permission = switch (kind) {
            case "enderchest" -> "hamptashop.utility.enderchest";
            case "crafting" -> "hamptashop.utility.crafting";
            default -> "hamptashop.utility.anvil";
        };
        if (!player.hasPermission(permission)) {
            send(player, "no-permission");
            return true;
        }
        switch (kind) {
            case "enderchest" -> player.openInventory(player.getEnderChest());
            case "crafting" -> player.openWorkbench(player.getLocation(), true);
            case "anvil" -> player.openInventory(Bukkit.createInventory(null, InventoryType.ANVIL,
                    color("&8Portable Anvil")));
            default -> throw new IllegalStateException("Unknown utility menu: " + kind);
        }
        return true;
    }

    private boolean handleAuction(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("hamptashop.auction")) {
            send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            openAuction(player, 0, "newest", "");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> {
                if (args.length < 2 || args.length > 3) {
                    send(player, "invalid-usage", "usage", "/ah sell <price> [amount]");
                    return true;
                }
                int amount = args.length == 3 ? parseAmount(args[2]) : player.getInventory().getItemInMainHand().getAmount();
                if (amount < 1) {
                    send(player, "invalid-number");
                    return true;
                }
                createListing(player, args[1], amount);
            }
            case "mine" -> openOwnListings(player, 0);
            case "cancel" -> {
                if (args.length != 2) {
                    send(player, "invalid-usage", "usage", "/ah cancel <id>");
                    return true;
                }
                cancelListing(player, args[1]);
            }
            case "search" -> {
                if (args.length < 2) {
                    send(player, "ah-search-prompt");
                } else {
                    openAuction(player, 0, "newest", String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
            }
            case "sort" -> {
                if (args.length != 2 || !List.of("newest", "cheapest", "expensive", "ending")
                        .contains(args[1].toLowerCase(Locale.ROOT))) {
                    send(player, "invalid-usage", "usage", "/ah sort <newest|cheapest|expensive|ending>");
                } else {
                    openAuction(player, 0, args[1].toLowerCase(Locale.ROOT), "");
                }
            }
            case "watch", "unwatch" -> {
                if (args.length != 2) {
                    send(player, "invalid-usage", "usage", "/ah watch|unwatch <player>");
                    return true;
                }
                toggleWatchedSeller(player, args[1], args[0].equalsIgnoreCase("watch"));
            }
            case "stats" -> showAuctionStats(player);
            case "info" -> showListingInfo(player, args.length == 2 ? args[1] : "");
            case "refresh" -> openAuction(player, 0, "newest", "");
            case "cancelall" -> cancelAllListings(player, args.length == 2 && args[1].equalsIgnoreCase("confirm"));
            case "claim" -> claimMailbox(player, false);
            default -> send(player, "invalid-usage", "usage",
                    "/ah [search|sort|sell|mine|cancel|cancelall|watch|unwatch|stats|info|refresh|claim]");
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "invalid-usage", "usage",
                    "/hamptashop <reload|preset <name>|preset list|preset save <name>|preset delete <name>|admin ...>");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            if (!hasAdminPermission(sender, "hamptashop.admin.reload")) {
                send(sender, "no-permission");
                return true;
            }
            reloadConfig();
            messages = YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "messages.yml"));
            presets = YamlConfiguration.loadConfiguration(presetsFile);
            send(sender, "admin-reloaded");
            return true;
        }
        if (args[0].equalsIgnoreCase("preset")) {
            return handlePreset(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
            if (!hasAdminPermission(sender, "hamptashop.shop.edit")) {
                send(sender, "no-permission");
                return true;
            }
            return handleAdminEdit(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        send(sender, "invalid-usage", "usage",
                "/hamptashop <reload|preset <name>|preset list|preset save <name>|preset delete <name>|admin ...>");
        return true;
    }

    private boolean handlePreset(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "invalid-usage", "usage", "/hamptashop preset <name|list|save|delete>");
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            List<String> names = presetNames();
            send(sender, names.isEmpty() ? "preset-none" : "preset-list", "presets",
                    names.isEmpty() ? "" : String.join("&7, &f", names));
            return true;
        }
        if (args[0].equalsIgnoreCase("save")) {
            if (!hasAdminPermission(sender, "hamptashop.preset.manage")) {
                send(sender, "no-permission");
                return true;
            }
            if (args.length != 2 || !validId(args[1].toLowerCase(Locale.ROOT))) {
                send(sender, "invalid-usage", "usage", "/hamptashop preset save <name>");
                return true;
            }
            String id = args[1].toLowerCase(Locale.ROOT);
            if (findPreset(id) != null) {
                send(sender, "preset-exists", "preset", id);
                return true;
            }
            saveCurrentPreset(sender, id);
            return true;
        }
        if (args[0].equalsIgnoreCase("delete")) {
            if (!hasAdminPermission(sender, "hamptashop.preset.manage")) {
                send(sender, "no-permission");
                return true;
            }
            if (args.length != 2) {
                send(sender, "invalid-usage", "usage", "/hamptashop preset delete <name>");
                return true;
            }
            String id = args[1].toLowerCase(Locale.ROOT);
            if (presets.getConfigurationSection("presets." + id) != null) {
                send(sender, "preset-builtin", "preset", id);
            } else if (presets.getConfigurationSection("custom-presets." + id) == null) {
                send(sender, "preset-not-found", "preset", id);
            } else {
                presets.set("custom-presets." + id, null);
                savePresets(sender);
            }
            return true;
        }
        if (!hasAdminPermission(sender, "hamptashop.preset.apply")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length != 1) {
            send(sender, "invalid-usage", "usage", "/hamptashop preset <name>");
            return true;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        ConfigurationSection preset = findPreset(id);
        if (preset == null) {
            send(sender, "preset-not-found", "preset", id);
            return true;
        }
        if (preset.getConfigurationSection("shop.categories") == null
                || preset.getConfigurationSection("shop.items") == null
                || preset.getConfigurationSection("auction") == null) {
            send(sender, "preset-invalid", "preset", id);
            return true;
        }
        applyPreset(sender, id, preset);
        return true;
    }

    private ConfigurationSection findPreset(String id) {
        ConfigurationSection custom = presets.getConfigurationSection("custom-presets." + id);
        return custom != null ? custom : presets.getConfigurationSection("presets." + id);
    }

    private List<String> presetNames() {
        List<String> names = new ArrayList<>();
        for (String root : List.of("presets", "custom-presets")) {
            ConfigurationSection section = presets.getConfigurationSection(root);
            if (section != null) {
                names.addAll(section.getKeys(false));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void saveCurrentPreset(CommandSender sender, String id) {
        try {
            copyConfigSectionToPresets("shop", "custom-presets." + id + ".shop");
            copyConfigSectionToPresets("auction", "custom-presets." + id + ".auction");
            presets.save(presetsFile);
            send(sender, "preset-saved", "preset", id);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save shop preset '" + id + "'.", exception);
            send(sender, "preset-save-error");
        }
    }

    private void copyConfigSectionToPresets(String source, String target) {
        ConfigurationSection section = getConfig().getConfigurationSection(source);
        if (section == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                presets.set(target + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    private void applyPreset(CommandSender sender, String id, ConfigurationSection preset) {
        File backupFolder = new File(getDataFolder(), "backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            getLogger().severe("Could not create the config backup directory.");
            send(sender, "preset-backup-error");
            return;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        File backup = new File(backupFolder, "config-" + timestamp + ".yml");
        try {
            getConfig().save(backup);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not back up the current config before applying a preset.", exception);
            send(sender, "preset-backup-error");
            return;
        }
        copyPresetSection(preset, "shop");
        copyPresetSection(preset, "auction");
        saveConfig();
        reloadConfig();
        send(sender, "preset-applied", "preset", id, "backup", backup.getName());
        getLogger().info(sender.getName() + " applied preset '" + id + "'. Backup: " + backup.getName());
    }

    private void copyPresetSection(ConfigurationSection preset, String sectionName) {
        ConfigurationSection values = preset.getConfigurationSection(sectionName);
        if (values == null) {
            return;
        }
        getConfig().set(sectionName, null);
        for (Map.Entry<String, Object> entry : values.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                getConfig().set(sectionName + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    private void savePresets(CommandSender sender) {
        try {
            presets.save(presetsFile);
            send(sender, "preset-deleted");
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save preset file.", exception);
            send(sender, "preset-save-error");
        }
    }

    private boolean hasAdminPermission(CommandSender sender, String permission) {
        return sender.hasPermission("hamptashop.admin") || sender.hasPermission(permission);
    }

    private boolean handleAdminEdit(CommandSender sender, String[] args) {
        if (args[0].equalsIgnoreCase("addcategory")) {
            if (args.length != 4) {
                send(sender, "invalid-usage", "usage", "/hs admin addcategory <id> <name> <material>");
                return true;
            }
            String id = args[1].toLowerCase(Locale.ROOT);
            Material material = Material.matchMaterial(args[3]);
            if (!validId(id) || material == null || !material.isItem()) {
                send(sender, "invalid-number");
                return true;
            }
            String path = "shop.categories." + id;
            if (getConfig().isConfigurationSection(path)) {
                send(sender, "invalid-usage", "usage", "That category ID is already in use.");
                return true;
            }
            getConfig().set(path + ".name", args[2]);
            getConfig().set(path + ".material", material.name());
            getConfig().set(path + ".slot", nextCategorySlot());
            saveConfig();
            send(sender, "admin-category-added", "category", id);
            return true;
        }
        if (args[0].equalsIgnoreCase("additem")) {
            if (!(sender instanceof Player player)) {
                send(sender, "player-only");
                return true;
            }
            if (args.length != 5) {
                send(sender, "invalid-usage", "usage", "/hs admin additem <id> <category> <buy> <sell>");
                return true;
            }
            String id = args[1].toLowerCase(Locale.ROOT);
            String category = findCategory(args[2]);
            double buy = parseNonNegative(args[3]);
            double sell = parseNonNegative(args[4]);
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!validId(id)) {
                send(player, "invalid-number");
            } else if (category == null) {
                send(player, "admin-invalid-category");
            } else if (held.getType().isAir()) {
                send(player, "admin-held-item");
            } else if (Double.isNaN(buy) || Double.isNaN(sell)) {
                send(player, "invalid-number");
            } else if (getConfig().isConfigurationSection("shop.items." + id)) {
                send(player, "invalid-usage", "usage", "That item ID is already in use.");
            } else {
                String path = "shop.items." + id;
                getConfig().set(path + ".category", category);
                getConfig().set(path + ".item", held.clone());
                getConfig().set(path + ".buy-price", buy);
                getConfig().set(path + ".sell-price", sell);
                getConfig().set(path + ".name", held.getItemMeta() != null
                        && held.getItemMeta().hasDisplayName() ? held.getItemMeta().getDisplayName() : prettify(id));
                saveConfig();
                send(player, "admin-item-added", "item", id);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("setprice")) {
            if (args.length != 4) {
                send(sender, "invalid-usage", "usage", "/hs admin setprice <id> <buy> <sell>");
                return true;
            }
            double buy = parseNonNegative(args[2]);
            double sell = parseNonNegative(args[3]);
            String path = "shop.items." + args[1];
            if (!getConfig().isConfigurationSection(path)) {
                send(sender, "unknown-item");
            } else if (Double.isNaN(buy) || Double.isNaN(sell)) {
                send(sender, "invalid-number");
            } else {
                getConfig().set(path + ".buy-price", buy);
                getConfig().set(path + ".sell-price", sell);
                saveConfig();
                send(sender, "admin-updated", "item", args[1]);
            }
            return true;
        }
        send(sender, "invalid-usage", "usage", "/hs admin <addcategory|additem|setprice>");
        return true;
    }

    private void openCategories(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CATEGORIES, player.getUniqueId(), null, "", 0);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                color(getConfig().getString("settings.shop-title", "&8Hampta Shop")));
        holder.inventory = inventory;
        ConfigurationSection categories = getConfig().getConfigurationSection("shop.categories");
        if (categories != null) {
            for (String id : categories.getKeys(false)) {
                String path = "shop.categories." + id;
                Material material = Material.matchMaterial(getConfig().getString(path + ".material", "CHEST"));
                if (material == null || !material.isItem()) {
                    material = Material.CHEST;
                }
                ItemStack icon = new ItemStack(material);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color(getConfig().getString(path + ".name", prettify(id))));
                    meta.setLore(List.of(color("&7Browse items in this category"), color("&8Click to open")));
                    icon.setItemMeta(meta);
                }
                int slot = getConfig().getInt(path + ".slot", inventory.firstEmpty());
                if (slot >= 0 && slot < inventory.getSize()) {
                    inventory.setItem(slot, icon);
                }
            }
        }
        player.openInventory(inventory);
    }

    private void openShopItems(Player player, String category, String query, int page) {
        openShopItems(player, category, query, page, "name", false);
    }

    private void openShopItems(Player player, String category, String query, int page, String sort,
                               boolean favoritesOnly) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.ITEMS, player.getUniqueId(), category, query, page,
                sort, favoritesOnly);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Shop &7- &f" +
                (category == null ? "Search" : getConfig().getString("shop.categories." + category + ".name", category))));
        holder.inventory = inventory;
        List<String> matching = itemIds().stream()
                .filter(id -> category == null || category.equalsIgnoreCase(
                        getConfig().getString("shop.items." + id + ".category", "")))
                .filter(id -> matchesSearch(id, query))
                .filter(id -> !favoritesOnly || isFavorite(player, id))
                .sorted(shopComparator(sort, player))
                .toList();
        int start = Math.max(0, page) * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, matching.size()); i++) {
            String id = matching.get(i);
            ItemStack display = shopItem(id);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                if (!lore.isEmpty()) {
                    lore.add("");
                }
                lore.add(color("&aBuy: &f" + formatCurrency(buyPrice(id, player)) + " &7per item"));
                lore.add(color("&cSell: &f" + formatCurrency(sellPrice(id, player)) + " &7per item"));
                lore.add(color("&8Left-click: buy 1"));
                lore.add(color("&8Shift-left: buy 16"));
                lore.add(color("&8Right-click: sell 1"));
                lore.add(color("&8Shift-right: sell all"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inventory.setItem(i - start, display);
        }
        addNavigation(inventory, page, matching.size(), true);
        inventory.setItem(46, namedItem(Material.NETHER_STAR,
                favoritesOnly ? "&eViewing favorites" : "&eFavorites",
                List.of("&7Click to toggle your favorite items.")));
        inventory.setItem(47, namedItem(Material.HOPPER, "&eSort: " + sortLabel(sort),
                List.of("&7Click to change item sorting.")));
        inventory.setItem(51, namedItem(Material.BOOK, "&eCategories",
                List.of("&7Return to the category menu.")));
        if (matching.isEmpty()) {
            inventory.setItem(22, namedItem(Material.BARRIER, "&cNo items found",
                    List.of("&7Try a different search or category.")));
        }
        player.openInventory(inventory);
    }

    private void openAuction(Player player, int page, String sort, String query) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.AUCTION, player.getUniqueId(), null, query, page, sort, false);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color(getConfig().getString("settings.auction-title", "&8Auction House")));
        holder.inventory = inventory;
        try {
            List<AuctionListing> listings = auctionDatabase.listActive(PAGE_SIZE, page * PAGE_SIZE, sort, query);
            int totalListings = auctionDatabase.countActive(query);
            for (int i = 0; i < listings.size(); i++) {
                AuctionListing listing = listings.get(i);
                ItemStack display = listing.item().clone();
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add("");
                    lore.add(color("&7Seller: &f" + listing.sellerName()));
                    lore.add(color("&7Price: &a" + formatCurrency(listing.price())));
                    lore.add(color("&7Expires: &f" + remainingTime(listing.expiresAt())));
                    lore.add(color("&8Listing #" + listing.id() + " - click to buy"));
                    meta.setLore(lore);
                    display.setItemMeta(meta);
                }
                inventory.setItem(i, display);
                holder.listingsBySlot.put(i, listing.id());
            }
            addNavigation(inventory, page, totalListings, false);
            inventory.setItem(46, namedItem(Material.HOPPER, "&eSort: " + auctionSortLabel(sort),
                    List.of("&7Use &f/ah sort <newest|cheapest|expensive|ending>")));
            inventory.setItem(47, namedItem(Material.COMPASS, "&bSearch listings",
                    List.of("&7Use &f/ah search <words>")));
            inventory.setItem(49, namedItem(Material.PAPER, "&bAuction House",
                    List.of("&7Sell with &f/ah sell <price> [amount]",
                            "&7Manage listings with &f/ah mine")));
            inventory.setItem(51, namedItem(Material.BOOK, "&e" + totalListings + " active listings",
                    List.of("&7Use &f/ah stats &7for your totals.")));
            if (listings.isEmpty()) {
                inventory.setItem(22, namedItem(Material.BARRIER, "&cNo listings found",
                        List.of("&7Try a different search or sort.")));
            }
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not load auction listings.", exception);
            send(player, "economy-error");
            return;
        }
        player.openInventory(inventory);
    }

    private void addNavigation(Inventory inventory, int page, int resultCount, boolean shop) {
        inventory.setItem(45, page > 0 ? namedItem(Material.ARROW, "&ePrevious page", List.of("&7Page " + page))
                : namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList()));
        inventory.setItem(49, namedItem(Material.BARRIER, "&cClose", List.of("&7Click to close this menu.")));
        inventory.setItem(53, resultCount > (page + 1) * PAGE_SIZE
                        ? namedItem(Material.ARROW, "&eNext page", List.of("&7Page " + (page + 2)))
                        : namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList()));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            if (holder.page > 0 && holder.kind != MenuHolder.Kind.CATEGORIES) {
                if (holder.kind == MenuHolder.Kind.AUCTION) {
                    openAuction(player, holder.page - 1, holder.sort, holder.query);
                } else if (holder.kind == MenuHolder.Kind.AUCTION_MINE) {
                    openOwnListings(player, holder.page - 1);
                } else {
                    openShopItems(player, holder.category, holder.query, holder.page - 1);
                }
            }
            return;
        }
        if (slot == 53 && holder.kind != MenuHolder.Kind.CATEGORIES) {
            if (holder.kind == MenuHolder.Kind.AUCTION) {
                openAuction(player, holder.page + 1, holder.sort, holder.query);
            } else if (holder.kind == MenuHolder.Kind.AUCTION_MINE) {
                openOwnListings(player, holder.page + 1);
            } else {
                openShopItems(player, holder.category, holder.query, holder.page + 1);
            }
            return;
        }
        if (holder.kind == MenuHolder.Kind.CATEGORIES) {
            String category = categoryAtSlot(slot);
            if (category != null) {
                openShopItems(player, category, "", 0);
            }
        } else if (holder.kind == MenuHolder.Kind.ITEMS && slot >= 0 && slot < PAGE_SIZE) {
            String itemId = itemAtSlot(holder, slot);
            if (itemId != null) {
                if (event.getClick() == ClickType.MIDDLE) {
                    toggleFavorite(player, itemId);
                } else if (event.isLeftClick()) {
                    buyFromShop(player, itemId, event.isShiftClick() ? 16 : 1);
                } else if (event.isRightClick()) {
                    sellMatching(player, itemId, event.isShiftClick() ? Integer.MAX_VALUE : 1);
                }
                openShopItems(player, holder.category, holder.query, holder.page, holder.sort, holder.favoritesOnly);
            }
        } else if (holder.kind == MenuHolder.Kind.ITEMS && slot == 46) {
            openShopItems(player, holder.category, holder.query, 0, holder.sort, !holder.favoritesOnly);
        } else if (holder.kind == MenuHolder.Kind.ITEMS && slot == 47) {
            String nextSort = switch (holder.sort) {
                case "name" -> "buy";
                case "buy" -> "sell";
                default -> "name";
            };
            openShopItems(player, holder.category, holder.query, 0, nextSort, holder.favoritesOnly);
        } else if (holder.kind == MenuHolder.Kind.ITEMS && slot == 51) {
            openCategories(player);
        } else if (holder.kind == MenuHolder.Kind.AUCTION) {
            Long listingId = holder.listingsBySlot.get(slot);
            if (listingId != null) {
                AuctionBuyConfirmation pending = auctionBuyConfirmations.get(player.getUniqueId());
                if (pending == null || pending.listingId() != listingId
                        || pending.expiresAt() < System.currentTimeMillis()) {
                    auctionBuyConfirmations.put(player.getUniqueId(),
                            new AuctionBuyConfirmation(listingId, System.currentTimeMillis() + 8_000L));
                    try {
                        AuctionListing listing = auctionDatabase.get(listingId);
                        if (listing != null) {
                            send(player, "ah-confirm-buy", "price", formatCurrency(listing.price()));
                        }
                    } catch (SQLException exception) {
                        getLogger().log(Level.SEVERE, "Could not read auction listing #" + listingId, exception);
                        send(player, "economy-error");
                    }
                } else {
                    auctionBuyConfirmations.remove(player.getUniqueId());
                    buyListing(player, listingId);
                }
            }
        } else if (holder.kind == MenuHolder.Kind.AUCTION_MINE) {
            Long listingId = holder.listingsBySlot.get(slot);
            if (listingId != null) {
                cancelListing(player, Long.toString(listingId));
                openOwnListings(player, holder.page);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        expireListings();
        claimMailbox(event.getPlayer(), true);
    }

    private void buyFromShop(Player player, String id, int requestedAmount) {
        double unitPrice = buyPrice(id, player);
        if (unitPrice <= 0) {
            send(player, "unknown-item");
            return;
        }
        ItemStack product = shopItem(id);
        int amount = requestedAmount;
        double total = unitPrice * amount;
        if (!Double.isFinite(total) || total <= 0 || !canFit(player.getInventory(), product, amount)) {
            send(player, "inventory-full");
            return;
        }
        EconomyResponse response = economy.withdrawPlayer(player, total);
        if (!response.transactionSuccess()) {
            send(player, "insufficient-funds");
            return;
        }
        Map<Integer, ItemStack> remainder = addItems(player.getInventory(), product, amount);
        if (!remainder.isEmpty()) {
            EconomyResponse refund = economy.depositPlayer(player, total);
            if (!refund.transactionSuccess()) {
                getLogger().severe("Could not refund a shop purchase to " + player.getName());
            }
            send(player, "inventory-full");
            return;
        }
        send(player, "shop-bought", "amount", Integer.toString(amount),
                "item", displayName(product), "price", formatCurrency(total));
    }

    private int sellMatching(Player player, String id, int requestedAmount) {
        double unitPrice = sellPrice(id, player);
        if (unitPrice <= 0) {
            return 0;
        }
        ItemStack prototype = shopItem(id);
        List<ItemStack> removed = removeMatching(player.getInventory(), id, requestedAmount);
        int amount = removed.stream().mapToInt(ItemStack::getAmount).sum();
        if (amount == 0) {
            return 0;
        }
        double total = unitPrice * amount;
        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            restoreItems(player, removed);
            send(player, "economy-error");
            return 0;
        }
        send(player, "shop-sold", "amount", Integer.toString(amount),
                "item", displayName(prototype), "price", formatCurrency(total));
        return amount;
    }

    private void sellHeld(Player player, String id, ItemStack held, int requestedAmount) {
        double unitPrice = sellPrice(id, player);
        int amount = Math.min(held.getAmount(), requestedAmount);
        double total = unitPrice * amount;
        if (unitPrice <= 0 || !Double.isFinite(total) || total <= 0) {
            send(player, "unknown-item");
            return;
        }
        ItemStack sold = held.clone();
        sold.setAmount(amount);
        if (held.getAmount() == amount) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - amount);
        }
        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            player.getInventory().addItem(sold);
            send(player, "economy-error");
            return;
        }
        send(player, "shop-sold", "amount", Integer.toString(sold.getAmount()),
                "item", displayName(sold), "price", formatCurrency(total));
    }

    private boolean createListing(Player player, String rawPrice, int requestedAmount) {
        double price = parsePositive(rawPrice);
        double min = getConfig().getDouble("auction.minimum-price", 0.01);
        double max = getConfig().getDouble("auction.maximum-price", 1_000_000_000.0);
        if (Double.isNaN(price) || price < min || price > max) {
            send(player, "ah-price-range", "min", formatCurrency(min), "max", formatCurrency(max));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            send(player, "admin-held-item");
            return true;
        }
        if (requestedAmount > hand.getAmount()) {
            send(player, "invalid-number");
            return true;
        }
        try {
            int limit = Math.max(1, getConfig().getInt("auction.max-active-listings", 10));
            if (auctionDatabase.countBySeller(player.getUniqueId()) >= limit) {
                send(player, "ah-limit");
                return true;
            }
            double fee = Math.max(0, getConfig().getDouble("auction.listing-fee", 0.0));
            if (fee > 0 && !economy.withdrawPlayer(player, fee).transactionSuccess()) {
                send(player, "ah-fee-error");
                return true;
            }
            ItemStack listingItem = hand.clone();
            listingItem.setAmount(requestedAmount);
            long days = Math.max(1, getConfig().getLong("auction.expiry-days", 7));
            long expiresAt = System.currentTimeMillis() + days * 86_400_000L;
            try {
                long id = auctionDatabase.create(player.getUniqueId(), player.getName(), listingItem, price, expiresAt);
                if (requestedAmount == hand.getAmount()) {
                    player.getInventory().setItemInMainHand(null);
                } else {
                    hand.setAmount(hand.getAmount() - requestedAmount);
                }
                send(player, "ah-listed", "item", displayName(listingItem),
                        "price", formatCurrency(price), "id", Long.toString(id));
                notifyWatchers(player, listingItem, price, id);
            } catch (SQLException exception) {
                if (fee > 0) {
                    EconomyResponse refund = economy.depositPlayer(player, fee);
                    if (!refund.transactionSuccess()) {
                        getLogger().severe("Could not refund the listing fee to " + player.getName());
                    }
                }
                getLogger().log(Level.SEVERE, "Could not create an auction listing.", exception);
                send(player, "economy-error");
            }
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not check the player's auction listing limit.", exception);
            send(player, "economy-error");
        }
        return true;
    }

    private void buyListing(Player buyer, long id) {
        try {
            AuctionListing listing = auctionDatabase.get(id);
            if (listing == null) {
                send(buyer, "ah-not-found");
                return;
            }
            if (listing.sellerId().equals(buyer.getUniqueId())) {
                send(buyer, "ah-own-listing");
                return;
            }
            if (!canFit(buyer.getInventory(), listing.item(), listing.item().getAmount())) {
                send(buyer, "inventory-full");
                return;
            }
            EconomyResponse withdrawal = economy.withdrawPlayer(buyer, listing.price());
            if (!withdrawal.transactionSuccess()) {
                send(buyer, "insufficient-funds");
                return;
            }
            AuctionListing taken;
            try {
                taken = auctionDatabase.take(id);
            } catch (SQLException exception) {
                refundBuyer(buyer, listing.price());
                throw exception;
            }
            if (taken == null) {
                refundBuyer(buyer, listing.price());
                send(buyer, "ah-not-found");
                return;
            }
            double feePercent = Math.min(100.0, Math.max(0.0,
                    getConfig().getDouble("auction.sale-fee-percent", 5.0)));
            double payout = listing.price() * (1.0 - feePercent / 100.0);
            EconomyResponse deposit = economy.depositPlayer(Bukkit.getOfflinePlayer(listing.sellerId()), payout);
            if (!deposit.transactionSuccess()) {
                refundBuyer(buyer, listing.price());
                restoreListing(taken);
                send(buyer, "economy-error");
                return;
            }
            Map<Integer, ItemStack> remainder = buyer.getInventory().addItem(taken.item().clone());
            remainder.values().forEach(item -> buyer.getWorld().dropItemNaturally(buyer.getLocation(), item));
            send(buyer, "ah-bought", "id", Long.toString(id), "price", formatCurrency(listing.price()));
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not complete auction purchase #" + id, exception);
            send(buyer, "economy-error");
        }
    }

    private void refundBuyer(Player buyer, double amount) {
        EconomyResponse refund = economy.depositPlayer(buyer, amount);
        if (!refund.transactionSuccess()) {
            getLogger().severe("Could not refund " + amount + " to " + buyer.getName()
                    + " after an auction transaction failed.");
        }
    }

    private void restoreListing(AuctionListing listing) {
        try {
            auctionDatabase.create(listing.sellerId(), listing.sellerName(), listing.item(), listing.price(),
                    listing.expiresAt());
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not restore auction listing #" + listing.id()
                    + "; returning its item to the seller mailbox.", exception);
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("item", listing.item());
                // Preserve the item in the mailbox if the listing could not be restored.
                auctionDatabase.addToMailbox(listing.sellerId(), yaml.saveToString());
            } catch (SQLException mailboxException) {
                getLogger().log(Level.SEVERE, "Could not preserve the failed listing item.", mailboxException);
            }
        }
    }

    private void cancelListing(Player player, String rawId) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            send(player, "invalid-number");
            return;
        }
        try {
            AuctionListing listing = auctionDatabase.get(id);
            if (listing == null || !listing.sellerId().equals(player.getUniqueId())
                    || !auctionDatabase.delete(id, player.getUniqueId())) {
                send(player, "ah-not-found");
                return;
            }
            giveOrDrop(player, listing.item());
            send(player, "ah-cancelled", "id", Long.toString(id));
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not cancel auction listing #" + id, exception);
            send(player, "economy-error");
        }
    }

    private void openOwnListings(Player player, int page) {
        try {
            List<AuctionListing> listings = auctionDatabase.listBySeller(player.getUniqueId());
            if (listings.isEmpty()) {
                send(player, "ah-mine-empty");
                return;
            }
            MenuHolder holder = new MenuHolder(MenuHolder.Kind.AUCTION_MINE, player.getUniqueId(), null, "", page);
            Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Your Auctions"));
            holder.inventory = inventory;
            int start = Math.max(0, page) * PAGE_SIZE;
            for (int i = start; i < Math.min(start + PAGE_SIZE, listings.size()); i++) {
                AuctionListing listing = listings.get(i);
                ItemStack display = listing.item().clone();
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add("");
                    lore.add(color("&7Price: &a" + formatCurrency(listing.price())));
                    lore.add(color("&7Expires: &f" + remainingTime(listing.expiresAt())));
                    lore.add(color("&cClick to cancel and return this item."));
                    lore.add(color("&8Listing #" + listing.id()));
                    meta.setLore(lore);
                    display.setItemMeta(meta);
                }
                inventory.setItem(i - start, display);
                holder.listingsBySlot.put(i - start, listing.id());
            }
            addNavigation(inventory, page, listings.size(), false);
            inventory.setItem(49, namedItem(Material.PAPER, "&bYour active listings",
                    List.of("&7Click a listing to cancel it.")));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not load the player's auction listings.", exception);
            send(player, "economy-error");
        }
    }

    private String auctionSortLabel(String sort) {
        return switch (sort) {
            case "cheapest" -> "Lowest price";
            case "expensive" -> "Highest price";
            case "ending" -> "Ending soon";
            default -> "Newest";
        };
    }

    private void toggleWatchedSeller(Player player, String seller, boolean watch) {
        String path = "players." + player.getUniqueId() + ".watched-sellers";
        List<String> watched = new ArrayList<>(playerData.getStringList(path));
        boolean changed = watch ? !watched.contains(seller) && watched.add(seller) : watched.removeIf(
                name -> name.equalsIgnoreCase(seller));
        if (changed) {
            playerData.set(path, watched);
            savePlayerData();
        }
        send(player, watch ? "ah-watch-added" : "ah-watch-removed", "seller", seller);
    }

    private void notifyWatchers(Player seller, ItemStack item, double price, long listingId) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(seller.getUniqueId())) {
                continue;
            }
            boolean watching = playerData.getStringList("players." + online.getUniqueId() + ".watched-sellers")
                    .stream().anyMatch(name -> name.equalsIgnoreCase(seller.getName()));
            if (watching) {
                send(online, "ah-watched-listing", "seller", seller.getName(),
                        "item", displayName(item), "price", formatCurrency(price), "id", Long.toString(listingId));
            }
        }
    }

    private void showAuctionStats(Player player) {
        try {
            int active = auctionDatabase.countActive("");
            int mine = auctionDatabase.countBySeller(player.getUniqueId());
            send(player, "ah-stats", "active", Integer.toString(active), "mine", Integer.toString(mine));
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not load auction statistics.", exception);
            send(player, "economy-error");
        }
    }

    private void showListingInfo(Player player, String rawId) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            send(player, "invalid-usage", "usage", "/ah info <listing-id>");
            return;
        }
        try {
            AuctionListing listing = auctionDatabase.get(id);
            if (listing == null) {
                send(player, "ah-not-found");
                return;
            }
            send(player, "ah-listing-info", "id", Long.toString(id), "seller", listing.sellerName(),
                    "item", displayName(listing.item()), "price", formatCurrency(listing.price()),
                    "expires", remainingTime(listing.expiresAt()));
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not load auction listing details.", exception);
            send(player, "economy-error");
        }
    }

    private void cancelAllListings(Player player, boolean confirmed) {
        if (!confirmed) {
            send(player, "invalid-usage", "usage", "/ah cancelall confirm");
            return;
        }
        try {
            List<AuctionListing> listings = auctionDatabase.listBySeller(player.getUniqueId());
            int cancelled = 0;
            for (AuctionListing listing : listings) {
                if (auctionDatabase.delete(listing.id(), player.getUniqueId())) {
                    giveOrDrop(player, listing.item());
                    cancelled++;
                }
            }
            send(player, "ah-cancelall", "amount", Integer.toString(cancelled));
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not cancel all auction listings.", exception);
            send(player, "economy-error");
        }
    }

    private void expireListings() {
        if (auctionDatabase == null) {
            return;
        }
        try {
            auctionDatabase.expireListings();
            for (Player player : Bukkit.getOnlinePlayers()) {
                claimMailbox(player, true);
            }
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not expire auction listings.", exception);
        }
    }

    private void claimMailbox(Player player, boolean quiet) {
        try {
            List<ItemStack> items = auctionDatabase.claimMailbox(player.getUniqueId());
            if (items.isEmpty()) {
                return;
            }
            for (ItemStack item : items) {
                giveOrDrop(player, item);
            }
            if (!quiet) {
                send(player, "ah-mail", "amount", Integer.toString(items.size()));
            } else {
                send(player, "ah-expired");
            }
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not claim auction mailbox for " + player.getName(), exception);
            send(player, "economy-error");
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> remainder = player.getInventory().addItem(item.clone());
        remainder.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    private boolean canFit(PlayerInventory inventory, ItemStack item, int amount) {
        int remaining = amount;
        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= Math.min(item.getMaxStackSize(), remaining);
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private Map<Integer, ItemStack> addItems(PlayerInventory inventory, ItemStack prototype, int requestedAmount) {
        Map<Integer, ItemStack> overflow = new HashMap<>();
        int remaining = requestedAmount;
        while (remaining > 0) {
            int amount = Math.min(remaining, prototype.getMaxStackSize());
            ItemStack stack = prototype.clone();
            stack.setAmount(amount);
            Map<Integer, ItemStack> part = inventory.addItem(stack);
            if (!part.isEmpty()) {
                overflow.putAll(part);
                break;
            }
            remaining -= amount;
        }
        return overflow;
    }

    private List<ItemStack> removeMatching(PlayerInventory inventory, String itemId, int requested) {
        return removeMatching(inventory, itemId, requested, inventory.getStorageContents().length);
    }

    private List<ItemStack> removeMatching(PlayerInventory inventory, String itemId, int requested, int slots) {
        List<ItemStack> removed = new ArrayList<>();
        int remaining = requested;
        for (int slot = 0; slot < Math.min(slots, inventory.getStorageContents().length) && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || !matchesShopItem(itemId, current)) {
                continue;
            }
            int take = Math.min(current.getAmount(), remaining);
            ItemStack taken = current.clone();
            taken.setAmount(take);
            removed.add(taken);
            if (current.getAmount() == take) {
                inventory.setItem(slot, null);
            } else {
                current.setAmount(current.getAmount() - take);
            }
            remaining -= take;
        }
        return removed;
    }

    private void restoreItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            giveOrDrop(player, item);
        }
    }

    private String itemAtSlot(MenuHolder holder, int slot) {
        List<String> matching = itemIds().stream()
                .filter(id -> holder.category == null || holder.category.equalsIgnoreCase(
                        getConfig().getString("shop.items." + id + ".category", "")))
                .filter(id -> matchesSearch(id, holder.query))
                .filter(id -> !holder.favoritesOnly || isFavorite(Bukkit.getPlayer(holder.owner), id))
                .sorted(shopComparator(holder.sort, Bukkit.getPlayer(holder.owner)))
                .toList();
        int index = holder.page * PAGE_SIZE + slot;
        return index >= 0 && index < matching.size() ? matching.get(index) : null;
    }

    private String categoryAtSlot(int slot) {
        ConfigurationSection categories = getConfig().getConfigurationSection("shop.categories");
        if (categories == null) {
            return null;
        }
        for (String id : categories.getKeys(false)) {
            if (getConfig().getInt("shop.categories." + id + ".slot", -1) == slot) {
                return id;
            }
        }
        return null;
    }

    private List<String> itemIds() {
        ConfigurationSection section = getConfig().getConfigurationSection("shop.items");
        if (section == null) {
            return Collections.emptyList();
        }
        return section.getKeys(false).stream().sorted().toList();
    }

    private java.util.Comparator<String> shopComparator(String sort, Player player) {
        return switch (sort) {
            case "buy" -> java.util.Comparator.<String>comparingDouble(id -> player == null ? 0 : buyPrice(id, player));
            case "sell" -> java.util.Comparator.<String>comparingDouble(id ->
                    player == null ? 0 : sellPrice(id, player)).reversed();
            default -> java.util.Comparator.comparing(id ->
                    ChatColor.stripColor(color(getConfig().getString("shop.items." + id + ".name", prettify(id))))
                            .toLowerCase(Locale.ROOT));
        };
    }

    private String sortLabel(String sort) {
        return switch (sort) {
            case "buy" -> "Lowest buy price";
            case "sell" -> "Highest sell price";
            default -> "Name";
        };
    }

    private boolean isFavorite(Player player, String itemId) {
        return player != null && playerData.getStringList("players." + player.getUniqueId() + ".favorites")
                .contains(itemId);
    }

    private void toggleFavorite(Player player, String itemId) {
        String path = "players." + player.getUniqueId() + ".favorites";
        List<String> favorites = new ArrayList<>(playerData.getStringList(path));
        if (favorites.remove(itemId)) {
            playerData.set(path, favorites);
            send(player, "shop-favorite-removed", "item", itemId);
        } else {
            favorites.add(itemId);
            playerData.set(path, favorites);
            send(player, "shop-favorite-added", "item", itemId);
        }
        savePlayerData();
    }

    private void savePlayerData() {
        try {
            playerData.save(playerDataFile);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save player shop preferences.", exception);
        }
    }

    private String findItemId(String value) {
        ConfigurationSection section = getConfig().getConfigurationSection("shop.items");
        if (section == null) {
            return null;
        }
        return section.getKeys(false).stream().filter(id -> id.equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    private int parseAmount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private int sellMatching(Player player, String id, int requestedAmount, int slots) {
        double unitPrice = sellPrice(id, player);
        if (unitPrice <= 0) {
            return 0;
        }
        ItemStack prototype = shopItem(id);
        List<ItemStack> removed = removeMatching(player.getInventory(), id, requestedAmount, slots);
        int amount = removed.stream().mapToInt(ItemStack::getAmount).sum();
        if (amount == 0) {
            return 0;
        }
        double total = unitPrice * amount;
        if (!Double.isFinite(total) || total <= 0) {
            restoreItems(player, removed);
            send(player, "invalid-number");
            return 0;
        }
        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            restoreItems(player, removed);
            send(player, "economy-error");
            return 0;
        }
        send(player, "shop-sold", "amount", Integer.toString(amount),
                "item", displayName(prototype), "price", formatCurrency(total));
        return amount;
    }

    private Map<String, Integer> sellableCounts(Player player, String category) {
        Map<String, Integer> counts = new HashMap<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (String id : itemIds()) {
            if (category != null && !category.equalsIgnoreCase(
                    getConfig().getString("shop.items." + id + ".category", ""))) {
                continue;
            }
            int amount = 0;
            for (ItemStack stack : contents) {
                if (stack != null && matchesShopItem(id, stack)) {
                    amount += stack.getAmount();
                }
            }
            if (amount > 0 && sellPrice(id, player) > 0) {
                counts.put(id, amount);
            }
        }
        return counts;
    }

    private double sellableValue(Player player, Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .mapToDouble(entry -> sellPrice(entry.getKey(), player) * entry.getValue()).sum();
    }

    private void requestSellConfirmation(Player player, String category, boolean hotbarOnly) {
        Map<String, Integer> counts = sellableCounts(player, category);
        if (hotbarOnly) {
            Map<String, Integer> hotbarCounts = new HashMap<>();
            for (String id : counts.keySet()) {
                int amount = 0;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = player.getInventory().getItem(slot);
                    if (stack != null && matchesShopItem(id, stack)) {
                        amount += stack.getAmount();
                    }
                }
                if (amount > 0) {
                    hotbarCounts.put(id, amount);
                }
            }
            counts = hotbarCounts;
        }
        if (counts.isEmpty()) {
            send(player, "sell-empty");
            return;
        }
        sellConfirmations.put(player.getUniqueId(),
                new SellConfirmation(Map.copyOf(counts), hotbarOnly, System.currentTimeMillis() + 30_000L));
        send(player, "sell-confirm-prompt", "amount", Integer.toString(counts.values().stream()
                        .mapToInt(Integer::intValue).sum()),
                "total", formatCurrency(sellableValue(player, counts)));
    }

    private void sendSellPreview(Player player) {
        Map<String, Integer> counts = sellableCounts(player, null);
        if (counts.isEmpty()) {
            send(player, "sell-empty");
            return;
        }
        send(player, "sell-preview", "amount", Integer.toString(counts.values().stream()
                        .mapToInt(Integer::intValue).sum()),
                "total", formatCurrency(sellableValue(player, counts)));
    }

    private void confirmSell(Player player) {
        SellConfirmation confirmation = sellConfirmations.remove(player.getUniqueId());
        if (confirmation == null || confirmation.expiresAt() < System.currentTimeMillis()) {
            send(player, "sell-confirm-expired");
            return;
        }
        int sold = 0;
        for (Map.Entry<String, Integer> entry : confirmation.amounts().entrySet()) {
            int slots = confirmation.hotbarOnly() ? 9 : player.getInventory().getStorageContents().length;
            sold += sellMatching(player, entry.getKey(), entry.getValue(), slots);
        }
        if (sold == 0) {
            send(player, "sell-empty");
        }
    }

    private ItemStack shopItem(String id) {
        String path = "shop.items." + id;
        ItemStack configured = getConfig().getItemStack(path + ".item");
        if (configured != null && !configured.getType().isAir()) {
            return configured.clone();
        }
        Material material = Material.matchMaterial(getConfig().getString(path + ".material", "STONE"));
        if (material == null || !material.isItem()) {
            material = Material.STONE;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString(path + ".name", prettify(id))));
            List<String> lore = getConfig().getStringList(path + ".lore").stream().map(this::color).toList();
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String findShopItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return itemIds().stream().filter(id -> matchesShopItem(id, item)).findFirst().orElse(null);
    }

    private boolean matchesShopItem(String id, ItemStack candidate) {
        ItemStack configured = getConfig().getItemStack("shop.items." + id + ".item");
        if (configured != null && !configured.getType().isAir()) {
            return configured.isSimilar(candidate);
        }
        Material material = Material.matchMaterial(
                getConfig().getString("shop.items." + id + ".material", "STONE"));
        return material != null && candidate.getType() == material;
    }

    private double buyPrice(String id, Player player) {
        double base = Math.max(0, getConfig().getDouble("shop.items." + id + ".buy-price", 0));
        return base * (1.0 - bestPriceModifier(player, "buy-discount-percent") / 100.0);
    }

    private double sellPrice(String id, Player player) {
        double base = Math.max(0, getConfig().getDouble("shop.items." + id + ".sell-price", 0));
        return base * (1.0 + bestPriceModifier(player, "sell-bonus-percent") / 100.0);
    }

    private double bestPriceModifier(Player player, String modifier) {
        ConfigurationSection modifiers = getConfig().getConfigurationSection("pricing.permission-modifiers");
        if (modifiers == null) {
            return 0;
        }
        double best = 0;
        double maximum = modifier.equals("buy-discount-percent") ? 90.0 : 100.0;
        for (String id : modifiers.getKeys(false)) {
            String path = "pricing.permission-modifiers." + id;
            String permission = getConfig().getString(path + ".permission", "");
            if (!permission.isBlank() && player.hasPermission(permission)) {
                double configured = getConfig().getDouble(path + "." + modifier, 0);
                if (Double.isFinite(configured)) {
                    best = Math.max(best, Math.min(maximum, Math.max(0, configured)));
                }
            }
        }
        return best;
    }

    private boolean matchesSearch(String id, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        String name = ChatColor.stripColor(color(getConfig().getString("shop.items." + id + ".name", prettify(id))));
        String material = getConfig().getString("shop.items." + id + ".material", "");
        return id.toLowerCase(Locale.ROOT).contains(normalized)
                || name.toLowerCase(Locale.ROOT).contains(normalized)
                || material.toLowerCase(Locale.ROOT).contains(normalized);
    }

    private String findCategory(String value) {
        ConfigurationSection categories = getConfig().getConfigurationSection("shop.categories");
        if (categories == null) {
            return null;
        }
        return categories.getKeys(false).stream().filter(id -> id.equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    private int nextCategorySlot() {
        ConfigurationSection categories = getConfig().getConfigurationSection("shop.categories");
        if (categories == null) {
            return 11;
        }
        boolean[] used = new boolean[27];
        for (String id : categories.getKeys(false)) {
            int slot = getConfig().getInt("shop.categories." + id + ".slot", -1);
            if (slot >= 0 && slot < used.length) {
                used[slot] = true;
            }
        }
        for (int slot = 10; slot <= 16; slot += 2) {
            if (!used[slot]) {
                return slot;
            }
        }
        for (int slot = 0; slot < used.length; slot++) {
            if (!used[slot]) {
                return slot;
            }
        }
        return 0;
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(this::color).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatCurrency(double amount) {
        return economy == null ? getConfig().getString("settings.currency-fallback", "$") + amount
                : economy.format(amount);
    }

    private String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return prettify(item.getType().name());
    }

    private String remainingTime(long expiresAt) {
        long hours = Math.max(0, (expiresAt - System.currentTimeMillis()) / 3_600_000L);
        if (hours >= 24) {
            return (hours / 24) + "d " + (hours % 24) + "h";
        }
        return hours + "h";
    }

    private double parsePositive(String value) {
        try {
            double result = Double.parseDouble(value);
            return Double.isFinite(result) && result > 0 ? result : Double.NaN;
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private double parseNonNegative(String value) {
        try {
            double result = Double.parseDouble(value);
            return Double.isFinite(result) && result >= 0 ? result : Double.NaN;
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private boolean validId(String id) {
        return id.matches("[a-z0-9_-]{1,32}");
    }

    private String prettify(String value) {
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private String prefix() {
        return color(getConfig().getString("settings.prefix", "&8[&bHampta Shop&8] &7"));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void send(CommandSender sender, String key, String... replacements) {
        String message = messages.getString(key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        sender.sendMessage(prefix() + color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("shop") && args.length == 1) {
            ConfigurationSection categories = getConfig().getConfigurationSection("shop.categories");
            if (categories != null) {
                choices.addAll(categories.getKeys(false));
            }
            choices.add("search");
        } else if (command.getName().equalsIgnoreCase("sell") && args.length == 1) {
            choices.addAll(List.of("hand", "all", "hotbar", "category", "preview", "confirm", "cancel"));
        } else if (command.getName().equalsIgnoreCase("ah") && args.length == 1) {
            choices.addAll(List.of("sell", "mine", "cancel", "cancelall", "search", "sort",
                    "watch", "unwatch", "stats", "info", "refresh", "claim"));
        } else if (command.getName().equalsIgnoreCase("ah") && args.length == 2
                && (args[0].equalsIgnoreCase("watch") || args[0].equalsIgnoreCase("unwatch"))) {
            choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        } else if (command.getName().equalsIgnoreCase("ah") && args.length == 2
                && args[0].equalsIgnoreCase("cancelall")) {
            choices.add("confirm");
        } else if (command.getName().equalsIgnoreCase("ah") && args.length == 2
                && args[0].equalsIgnoreCase("sort")) {
            choices.addAll(List.of("newest", "cheapest", "expensive", "ending"));
        } else if (command.getName().equalsIgnoreCase("shop") && args.length == 2
                && args[0].equalsIgnoreCase("sort")) {
            choices.addAll(List.of("name", "buy", "sell"));
        } else if (command.getName().equalsIgnoreCase("hamptashop")) {
            if (args.length == 1) {
                choices.addAll(List.of("reload", "preset", "admin"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("preset")) {
                choices.addAll(List.of("list", "save", "delete"));
                choices.addAll(presetNames());
            } else if (args.length == 3 && args[0].equalsIgnoreCase("preset")
                    && args[1].equalsIgnoreCase("delete")) {
                ConfigurationSection customPresets = presets.getConfigurationSection("custom-presets");
                if (customPresets != null) {
                    choices.addAll(customPresets.getKeys(false));
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("preset")
                    && args[1].equalsIgnoreCase("save")) {
                choices.addAll(List.of("survival", "creative", "hardcore", "generous"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
                choices.addAll(List.of("addcategory", "additem", "setprice"));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                    && args[1].equalsIgnoreCase("additem")) {
                ConfigurationSection categories = getConfig().getConfigurationSection("shop.categories");
                if (categories != null) {
                    choices.addAll(categories.getKeys(false));
                }
            }
        }
        return StringUtil.copyPartialMatches(args[args.length - 1], choices, new ArrayList<>());
    }
}
