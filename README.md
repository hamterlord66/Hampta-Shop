# Hampta Shop

Hampta Shop is a standalone economy, server shop, and player auction house for Minecraft Java servers. It targets the Spigot API 1.20.4 baseline and is intended for Paper and Spigot 1.20.4 and newer.

## Requirements

- Java 17 or newer. Minecraft 1.21 servers themselves require Java 21.
- Paper or Spigot 1.20.4+
- PlaceholderAPI (optional)
- LuckPerms (optional; recommended for per-rank staff permissions)

Player balances and auction listings use local SQLite databases. Their JDBC driver is bundled into the plugin JAR. No Vault plugin or external economy provider is required.

## Install

1. Download `Hampta-Shop-1.6.0.jar` from the GitHub Actions **hampta-shop** artifact or build it locally.
2. Put the JAR in the server's `plugins` folder.
3. Restart the server; no Vault plugin or economy provider is needed.
4. Configure the economy, currency symbol, general settings, and auction fees in `plugins/HamptaShop/config.yml`. Shop categories and items are stored separately in `plugins/HamptaShop/Categories/`.

## Build

```sh
mvn --batch-mode clean verify
```

The distributable JAR is `target/Hampta-Shop-1.6.0.jar`. The GitHub Actions workflow at `.github/workflows/build.yml` builds the plugin on pushes, pull requests, tags, or manual dispatch and uploads the JAR as an artifact.

## Commands

| Command | Description |
| --- | --- |
| `/shop` | Open the category browser |
| `/shop <category>` | Open a category |
| `/shop search <words>` | Search the configured shop |
| `/shop buy <item-id> [amount]` | Buy a configured item directly; supports up to 2,304 items if they fit |
| `/shop sell <item-id> [amount\|all]` | Sell a configured item directly |
| `/shop sort <name\|buy\|sell>` | Browse the whole shop sorted by name or price |
| `/shop favorite <item-id>` | Toggle an item in your saved favorites |
| `/shop favorites` | Browse only your saved favorite items |
| `/balance` (`/bal`) | Check your balance |
| `/balance <player>` | View another player's balance (permission required) |
| `/pay <player> <amount>` | Pay an online player |
| `/sell hand [amount]` | Sell some or all of a matching held stack |
| `/sell all` | Preview a sale and request confirmation |
| `/sell confirm` | Confirm a pending bulk sale within 30 seconds |
| `/sell cancel` | Cancel a pending bulk sale |
| `/sell preview` | Preview the current inventory's sell value |
| `/sell hotbar` | Sell configured shop items in the hotbar |
| `/sell category <name>` | Sell configured items from one category |
| `/ah` | Browse active buy-now listings |
| `/ah sell <price> [amount]` | List the held item stack or a chosen quantity |
| `/ah search <words>` | Search active listings by seller or serialized item details |
| `/ah sort <newest\|cheapest\|expensive\|ending>` | Sort the market |
| `/ah mine` | Open your active listings; click one to cancel and return it |
| `/ah cancel <id>` | Cancel your listing and return its item |
| `/ah cancelall confirm` | Cancel all your active listings |
| `/ah watch <player>` / `/ah unwatch <player>` | Subscribe or unsubscribe to online seller listing alerts |
| `/ah info <id>` | Inspect listing details |
| `/ah stats` | Show active-market and personal listing counts |
| `/ah refresh` | Refresh the market |
| `/ah claim` | Claim returned auction items |
| `/ec` | Open your personal ender chest |
| `/craft` | Open a functional virtual crafting table |
| `/anvil` | Open a functional virtual anvil |
| `/hamptashop reload` | Reload the shop configuration |
| `/hamptashop preset list` | List built-in and saved economy presets |
| `/hamptashop preset <name>` | Apply a preset (creates a config backup first) |
| `/hamptashop preset save <name>` | Save current shop and auction settings as a custom preset |
| `/hamptashop preset delete <name>` | Delete a custom preset |
| `/hamptashop admin balance <player> <set\|add\|remove> <amount>` | Manage a player's balance |
| `/hs admin addcategory <id> <name> <material>` | Add a category in-game |
| `/hs admin additem <id> <category> <buy> <sell>` | Add the held item to the shop |
| `/hs admin setprice <id> <buy> <sell>` | Update an item's prices |

Shop GUI: left-click buys one item, shift-left-click buys 16, right-click sells one, and shift-right-click sells all matching items. Categories are **Blocks**, **Misc (Items)**, **Spawners**, **PVP**, **Food**, **Mob drops**, and **Potions**. Each category and its items live in an individual YAML file such as `plugins/HamptaShop/Categories/blocks.yml`:

```yaml
category:
  name: "&aBlocks"
  material: BRICKS
  slot: 10
items:
  oak_planks:
    material: OAK_PLANKS
    name: "&fOak Planks"
    buy-price: 4.0
    sell-price: 1.0
```

The item ID is its YAML key; the category is inferred from the file name. Item fields such as `lore`, `potion-type`, `extended`, and `upgraded` are optional. The starter catalog includes configurable examples for each category; potions preserve their configured potion effects, and spawn eggs have distinct entries. The generic monster-spawner item does not assign a mob type: that behavior depends on the server. Use `/hamptashop reload` after manually editing category files; `/hs admin addcategory`, `/hs admin additem`, and `/hs admin setprice` write changes to those files.

The bundled catalog contains all 1,657 non-air item IDs in Minecraft Java 26.3, sorted into these categories, plus purchasable potion-effect variants (regular, splash, lingering, tipped-arrow, and available long/strong forms). Names and IDs use the [Minecraft 26.3 registry dataset](https://github.com/DallasCarraher/minecraft-data/tree/924e26d6b3bb8f97a5d1e73d6190b09879c2e1e2/data/pc/26.3); the release's new content is described in the [official Java 26.3 notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3). Default prices for newly added entries are starter estimates and can be changed in their category files. Technical/command/test items are included but disabled by default with `enabled: false`; enable them only if appropriate for your server. Older servers automatically skip materials or potion types their API does not support.

Built-in shop price presets are `normal` (base prices), `inflation` (4x all shop purchase prices), and `sale` (90% of all shop purchase prices). They scale prices dynamically across every category and item—including items added later—without rewriting category files or changing sell-to-server payouts. Applying one backs up `config.yml` and leaves shop edits and auction settings intact. Custom presets can still save and restore the shop catalog and auction settings. On upgrade, existing custom presets are preserved and the built-in presets are updated.

The category browser and item shop menus display the player's live Hampta Shop balance.

On upgrade from older versions, the plugin replaces the previous built-in categories and shop items once with this catalog. It backs up both the old config and preset files under `plugins/HamptaShop/backups/` first. Later restarts will not overwrite shop edits.

Price modifiers can give permission groups discounts and improved sale prices. Assign the configured permission to a LuckPerms group (for example, `/lp group vip permission set hamptashop.discount.vip true`). Only the highest applicable discount and highest sell bonus are used; they do not stack. Discounts are capped at 90% and sell bonuses at 100%.

### Shop: 20 capabilities

1. Category GUI; 2. category commands; 3. paginated item browsing; 4. search by ID, name, or material; 5. one-click buy; 6. shift-buy 16; 7. direct quantity buys; 8. buys split into multiple stacks; 9. inventory-fit precheck; 10. one-click sell; 11. shift-sell matching items; 12. direct quantity sells; 13. item-specific selling; 14. sort by name; 15. sort by buy price; 16. sort by sell price; 17. favorites toggle in the GUI; 18. favorite commands; 19. persistent favorite-only browsing; 20. LuckPerms discounts and sell bonuses.

### Selling: 15 capabilities

1. Sell the held stack; 2. sell an exact held quantity; 3. preview sell value; 4. preview bulk inventory sales; 5. confirm bulk sales; 6. cancel pending sales; 7. 30-second confirmation expiry; 8. sell hotbar items; 9. sell a category; 10. sell configured catalog items only; 11. direct `/shop sell`; 12. partial direct quantities; 13. right-click one item in shop; 14. shift-right-click all matching items; 15. restore items if a payout cannot be credited.

### Auction house: 21 capabilities

1. Paginated buy-now market; 2. search listings; 3. sort newest; 4. sort cheapest; 5. sort most expensive; 6. sort ending soon; 7. list the held stack; 8. list a partial stack; 9. per-player listing cap; 10. configurable price bounds; 11. expiry; 12. listing fee; 13. sale fee; 14. two-click purchase confirmation; 15. personal listings GUI; 16. click-to-cancel own listing; 17. cancel by ID; 18. cancel-all confirmation; 19. seller watch subscriptions; 20. online watched-seller alerts; 21. listing info and market statistics.

These features are backed by SQLite auction persistence and player preferences in `players.yml`. Expired or cancelled items are returned safely, and players can refresh the market with `/ah refresh`.

## Configuration and placeholders

The internal economy stores accounts in `plugins/HamptaShop/economy.db`; balances are keyed by Minecraft player UUID and persist across restarts. `economy.starting-balance` applies only when a player's account is first created, while `economy.maximum-balance` caps each account. `settings.currency-symbol` sets the currency prefix. Use `/pay` for player-to-player transfers and `/hamptashop admin balance` to set, add, or remove funds. Existing balances from Vault or another economy are not imported automatically; set balances with the admin command if you need to migrate players.

General settings, auction expiry, listing fee, sale fee, and listing limits are in `config.yml`. Shop entries use `buy-price` and `sell-price` per item; set either to `0` to disable that direction. Category and item definitions are not stored in `config.yml`; edit their individual files under `Categories/` or use the in-game admin commands.

When PlaceholderAPI is installed, `%hamptashop_balance%` returns the player's formatted Hampta Shop balance. PlaceholderAPI is optional.

### LuckPerms staff roles

LuckPerms works through Bukkit's standard permission system; no LuckPerms API dependency is needed. Grant only the capabilities each staff rank needs, for example:

```text
/lp group shop-editor permission set hamptashop.shop.edit true
/lp group economy-manager permission set hamptashop.preset.apply true
/lp group economy-manager permission set hamptashop.preset.manage true
/lp group economy-manager permission set hamptashop.economy.admin true
/lp group admin permission set hamptashop.admin true
```

`hamptashop.shop.edit` allows adding categories/items and changing prices. `hamptashop.preset.apply` allows applying presets. `hamptashop.preset.manage` allows saving/deleting custom presets. `hamptashop.economy.admin` allows managing account balances; `hamptashop.economy.view-others` allows viewing other balances. `hamptashop.admin.reload` allows reloads, and `hamptashop.admin` remains the full-access permission for compatibility.

Preset definitions are in `plugins/HamptaShop/presets.yml`. Server owners can edit them there, apply them in-game, or save the active shop/auction configuration as a named custom preset. Existing inline catalogs are migrated into category files on first startup, with backups created before migration.
