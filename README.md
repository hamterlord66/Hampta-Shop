# Hampta Shop

Hampta Shop is a Vault-powered server shop and player auction house for Minecraft Java servers. It targets the Spigot API 1.20.4 baseline and is intended for Paper and Spigot 1.20.4 and newer.

## Requirements

- Java 17 or newer. Minecraft 1.21 servers themselves require Java 21.
- Paper or Spigot 1.20.4+
- Vault and a Vault-compatible economy provider
- PlaceholderAPI (optional)

The auction house uses a local SQLite database. Its JDBC driver is bundled into the plugin JAR.

## Install

1. Download `Hampta-Shop-1.0.0.jar` from the GitHub Actions **hampta-shop** artifact or build it locally.
2. Put the JAR in the server's `plugins` folder.
3. Ensure Vault and an economy provider are installed, then restart the server.
4. Configure shop items and auction fees in `plugins/HamptaShop/config.yml`.

## Build

```sh
mvn --batch-mode clean verify
```

The distributable JAR is `target/Hampta-Shop-1.0.0.jar`. The GitHub Actions workflow at `.github/workflows/build.yml` builds the plugin on pushes, pull requests, tags, or manual dispatch and uploads the JAR as an artifact.

## Commands

| Command | Description |
| --- | --- |
| `/shop` | Open the category browser |
| `/shop <category>` | Open a category |
| `/shop search <words>` | Search the configured shop |
| `/sell hand` | Sell the held stack when it matches a shop item |
| `/sell all` | Sell all configured shop items in your inventory |
| `/ah` | Browse active buy-now listings |
| `/ah sell <price>` | List the held item stack |
| `/ah mine` | Show your active listing IDs |
| `/ah cancel <id>` | Cancel your listing and return its item |
| `/ah claim` | Claim returned auction items |
| `/hamptashop reload` | Reload the shop configuration |
| `/hs admin addcategory <id> <name> <material>` | Add a category in-game |
| `/hs admin additem <id> <category> <buy> <sell>` | Add the held item to the shop |
| `/hs admin setprice <id> <buy> <sell>` | Update an item's prices |

Shop GUI: left-click buys one item, shift-left-click buys 16, right-click sells one, and shift-right-click sells all matching items. Use permission `hamptashop.admin` for configuration commands; player permissions are `hamptashop.use`, `hamptashop.sell`, and `hamptashop.auction`.

## Configuration and placeholders

The default shop catalog, auction expiry, listing fee, sale fee, and listing limits are in `config.yml`. Shop entries use `buy-price` and `sell-price` per item; set either to `0` to disable that direction. Categories and entries can also be added or repriced in-game.

When PlaceholderAPI is installed, `%hamptashop_balance%` returns the player's Vault-formatted balance. PlaceholderAPI is optional; Vault and an active Vault economy provider are required.
