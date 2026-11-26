package com.hampta.shop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShopCatalog {
    static final int CATALOG_FILES_VERSION = 2;
    private static final List<String> DEFAULT_FILES = List.of(
            "blocks.yml", "misc.yml", "spawners.yml", "pvp.yml",
            "food.yml", "mob_drops.yml", "potions.yml");
    private final JavaPlugin plugin;
    private final File directory;
    private final YamlConfiguration catalog = new YamlConfiguration();
    private final Set<String> supportedItemIds = new HashSet<>();

    ShopCatalog(JavaPlugin plugin, File directory) {
        this.plugin = plugin;
        this.directory = directory;
    }

    void initialize(FileConfiguration mainConfig) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create Categories directory: " + directory);
        }
        File[] existingFiles = categoryFiles();
        boolean hasExistingFiles = existingFiles.length > 0;
        int catalogFilesVersion = mainConfig.getInt("shop.catalog-files-version", 0);
        if (catalogFilesVersion < 1) {
            if (!hasExistingFiles) {
                importLegacyCatalog(mainConfig);
            }
            catalogFilesVersion = 1;
        }
        if (catalogFilesVersion < CATALOG_FILES_VERSION) {
            upgradeDefaultFiles();
            catalogFilesVersion = CATALOG_FILES_VERSION;
        }
        mainConfig.set("shop.catalog-files-version", catalogFilesVersion);
        removeLegacyEntries(mainConfig);
        plugin.saveConfig();
        loadFiles();
    }

    boolean hasCategoryFiles() {
        return categoryFiles().length > 0;
    }

    YamlConfiguration configuration() {
        return catalog;
    }

    YamlConfiguration defaultConfiguration() throws IOException {
        YamlConfiguration defaults = new YamlConfiguration();
        for (String resource : DEFAULT_FILES) {
            try (var stream = plugin.getResource("Categories/" + resource)) {
                if (stream == null) {
                    throw new IOException("Missing default category resource: " + resource);
                }
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                String id = resource.substring(0, resource.length() - 4);
                ConfigurationSection category = yaml.getConfigurationSection("category");
                if (category == null) {
                    throw new IOException("Missing category section in " + resource);
                }
                defaults.set("shop.categories." + id, category.getValues(false));
                ConfigurationSection items = yaml.getConfigurationSection("items");
                if (items == null) {
                    continue;
                }
                for (String itemId : items.getKeys(false)) {
                    ConfigurationSection item = items.getConfigurationSection(itemId);
                    if (item == null) {
                        continue;
                    }
                    Map<String, Object> values = new java.util.HashMap<>(item.getValues(false));
                    values.put("category", id);
                    defaults.set("shop.items." + itemId, values);
                }
            }
        }
        return defaults;
    }

    void reload() throws IOException {
        loadFiles();
    }

    List<String> categoryIds() {
        ConfigurationSection section = catalog.getConfigurationSection("shop.categories");
        if (section == null) {
            return Collections.emptyList();
        }
        return section.getKeys(false).stream().sorted().toList();
    }

    List<String> itemIds() {
        ConfigurationSection section = catalog.getConfigurationSection("shop.items");
        if (section == null) {
            return Collections.emptyList();
        }
        return section.getKeys(false).stream().sorted().toList();
    }

    String categoryForItem(String itemId) {
        return catalog.getString("shop.items." + itemId + ".category");
    }

    boolean supportsItem(String itemId) {
        return supportedItemIds.contains(itemId);
    }

    void saveCategory(String id) throws IOException {
        ConfigurationSection category = catalog.getConfigurationSection("shop.categories." + id);
        if (category == null) {
            throw new IOException("Unknown category: " + id);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : category.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                yaml.set("category." + entry.getKey(), entry.getValue());
            }
        }
        ConfigurationSection items = catalog.getConfigurationSection("shop.items");
        if (items != null) {
            for (String itemId : items.getKeys(false)) {
                if (!id.equals(catalog.getString("shop.items." + itemId + ".category"))) {
                    continue;
                }
                ConfigurationSection item = items.getConfigurationSection(itemId);
                if (item != null) {
                    for (Map.Entry<String, Object> entry : item.getValues(true).entrySet()) {
                        if (!entry.getKey().equals("category")
                                && !(entry.getValue() instanceof ConfigurationSection)) {
                            yaml.set("items." + itemId + "." + entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        }
        yaml.save(categoryFile(id));
        if (items != null) {
            for (String itemId : items.getKeys(false)) {
                if (!id.equals(catalog.getString("shop.items." + itemId + ".category"))) {
                    continue;
                }
                ConfigurationSection item = items.getConfigurationSection(itemId);
                if (item != null && isSupportedItem(item)) {
                    supportedItemIds.add(itemId);
                } else {
                    supportedItemIds.remove(itemId);
                }
            }
        }
    }

    void replaceFrom(ConfigurationSection source) throws IOException {
        ConfigurationSection categories = source.getConfigurationSection("categories");
        ConfigurationSection items = source.getConfigurationSection("items");
        if (categories == null || items == null) {
            throw new IOException("Preset catalog must define categories and items.");
        }
        catalog.set("shop.categories", null);
        catalog.set("shop.items", null);
        copySection(categories, "shop.categories");
        copySection(items, "shop.items");

        File[] files = categoryFiles();
        for (File file : files) {
            if (!file.delete()) {
                throw new IOException("Could not replace old category file: " + file.getName());
            }
        }
        for (String id : categoryIds()) {
            saveCategory(id);
        }
        loadFiles();
    }

    void backupTo(File destination) throws IOException {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Could not create catalog backup directory: " + destination);
        }
        for (File file : categoryFiles()) {
            java.nio.file.Files.copy(file.toPath(), new File(destination, file.getName()).toPath());
        }
    }

    void restoreFrom(File source) throws IOException {
        File[] currentFiles = categoryFiles();
        for (File file : currentFiles) {
            if (!file.delete()) {
                throw new IOException("Could not remove category file during restore: " + file.getName());
            }
        }
        File[] backupFiles = source.listFiles((dir, name) ->
                name.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (backupFiles == null) {
            throw new IOException("Could not read category backup directory: " + source);
        }
        for (File file : backupFiles) {
            java.nio.file.Files.copy(file.toPath(), new File(directory, file.getName()).toPath());
        }
        loadFiles();
    }

    private void importLegacyCatalog(FileConfiguration mainConfig) throws IOException {
        ConfigurationSection categories = mainConfig.getConfigurationSection("shop.categories");
        ConfigurationSection items = mainConfig.getConfigurationSection("shop.items");
        if (categories == null || categories.getKeys(false).isEmpty()) {
            return;
        }
        for (String id : categories.getKeys(false)) {
            ConfigurationSection category = categories.getConfigurationSection(id);
            if (category == null || !id.matches("[a-z0-9_-]{1,32}")) {
                continue;
            }
            catalog.set("shop.categories." + id, category.getValues(false));
        }
        if (items != null) {
            for (String itemId : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(itemId);
                if (item == null) {
                    continue;
                }
                String categoryId = item.getString("category", "");
                if (!catalog.isConfigurationSection("shop.categories." + categoryId)) {
                    continue;
                }
                catalog.set("shop.items." + itemId, item.getValues(false));
            }
        }
        if (categoryIds().isEmpty()) {
            return;
        }
        for (String id : categoryIds()) {
            saveCategory(id);
        }
        plugin.getLogger().info("Moved the legacy shop catalog into individual Categories/*.yml files.");
    }

    private void seedDefaults() {
        for (String resource : DEFAULT_FILES) {
            plugin.saveResource("Categories/" + resource, false);
        }
    }

    private void upgradeDefaultFiles() throws IOException {
        seedDefaults();
        Set<String> existingItemIds = new HashSet<>();
        for (File file : categoryFiles()) {
            YamlConfiguration installed = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection items = installed.getConfigurationSection("items");
            if (items != null) {
                existingItemIds.addAll(items.getKeys(false));
            }
        }

        for (String resource : DEFAULT_FILES) {
            String id = resource.substring(0, resource.length() - 4);
            File installedFile = categoryFile(id);
            YamlConfiguration installed = YamlConfiguration.loadConfiguration(installedFile);
            if (installed.getConfigurationSection("category") == null) {
                plugin.getLogger().warning("Could not add default items to " + installedFile.getName()
                        + ": missing category metadata.");
                continue;
            }

            try (var stream = plugin.getResource("Categories/" + resource)) {
                if (stream == null) {
                    throw new IOException("Missing default category resource: " + resource);
                }
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                ConfigurationSection items = defaults.getConfigurationSection("items");
                if (items == null) {
                    continue;
                }
                boolean changed = false;
                for (String itemId : items.getKeys(false)) {
                    if (existingItemIds.add(itemId)) {
                        ConfigurationSection defaultItem = items.getConfigurationSection(itemId);
                        if (defaultItem != null) {
                            installed.set("items." + itemId, defaultItem.getValues(false));
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    installed.save(installedFile);
                }
            }
        }
    }

    private void loadFiles() throws IOException {
        catalog.set("shop.categories", null);
        catalog.set("shop.items", null);
        supportedItemIds.clear();
        int unsupportedItems = 0;
        for (File file : categoryFiles()) {
            String fileName = file.getName();
            String id = fileName.substring(0, fileName.length() - 4).toLowerCase(java.util.Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                plugin.getLogger().warning("Ignoring category file with invalid name: " + fileName);
                continue;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection category = yaml.getConfigurationSection("category");
            if (category == null) {
                plugin.getLogger().warning("Ignoring " + fileName + ": missing category section.");
                continue;
            }
            catalog.set("shop.categories." + id, new java.util.HashMap<>(category.getValues(false)));
            ConfigurationSection items = yaml.getConfigurationSection("items");
            if (items == null) {
                continue;
            }
            for (String itemId : items.getKeys(false)) {
                if (!itemId.matches("[a-z0-9_-]{1,32}")) {
                    plugin.getLogger().warning("Ignoring invalid item ID '" + itemId + "' in " + fileName);
                    continue;
                }
                if (catalog.isConfigurationSection("shop.items." + itemId)) {
                    throw new IOException("Duplicate shop item ID '" + itemId + "' in " + fileName);
                }
                ConfigurationSection item = items.getConfigurationSection(itemId);
                if (item == null) {
                    plugin.getLogger().warning("Ignoring invalid item '" + itemId + "' in " + fileName);
                    continue;
                }
                if (!isSupportedItem(item)) {
                    unsupportedItems++;
                } else {
                    supportedItemIds.add(itemId);
                }
                Map<String, Object> values = new java.util.HashMap<>(item.getValues(false));
                values.put("category", id);
                catalog.set("shop.items." + itemId, values);
            }
        }
        if (unsupportedItems > 0) {
            plugin.getLogger().info("Skipped " + unsupportedItems
                    + " catalog items not supported by this server's Minecraft version.");
        }
    }

    private boolean isSupportedItem(ConfigurationSection item) {
        String materialName = item.getString("material", "");
        if (!materialName.isBlank()) {
            Material material = Material.matchMaterial(materialName);
            if (material == null || !material.isItem()) {
                return false;
            }
        } else {
            var configuredItem = item.getItemStack("item");
            if (configuredItem == null || configuredItem.getType().isAir()) {
                return false;
            }
        }

        String potionType = item.getString("potion-type", "");
        if (!potionType.isBlank()) {
            try {
                PotionType.valueOf(potionType.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return true;
    }

    private void copySection(ConfigurationSection source, String targetPath) {
        for (Map.Entry<String, Object> entry : source.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                catalog.set(targetPath + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    private void removeLegacyEntries(FileConfiguration mainConfig) {
        mainConfig.set("shop.categories", null);
        mainConfig.set("shop.items", null);
    }

    private File[] categoryFiles() {
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return new File[0];
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private File categoryFile(String id) {
        return new File(directory, id + ".yml");
    }
}
