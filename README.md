# MineStormParty
**Cross-server Party System** — Spigot / Paper / Bukkit 1.8+ · BungeeCord · Velocity

> Created by **Muvixo**

## Architecture
```
            +--------------------+          +---------------------+
            |  Bungee / Velocity |  <--->   |  Backend (Spigot)   |
            |  MineStormParty    |          |  MineStormParty     |
            |  (proxy relay)     |          |  SQLite: guilds.db  |
            +--------------------+          +---------------------+
```

- The **backend** plugin owns all data in `plugins/MineStormParty/guilds.db` (SQLite).
- The **proxy** plugin relays party messages (invite/accept/chat) between backends so
  a player on any server can invite someone on another server.
- Works standalone on a single server — the proxy plugin is optional.

## Build
```bash
mvn clean package
```
Artifacts:
- `bukkit/target/MineStormParty-Bukkit-<v>.jar`
- `bungee/target/MineStormParty-Bungee-<v>.jar`
- `velocity/target/MineStormParty-Velocity-<v>.jar`

## Commands
### Player — `/party` (aliases `/p`, `/minestormparty`)
`create · invite · accept · deny · leave · disband · kick · transfer · list · chat · color · creator · help`

### Admin — `/mspa`
- **OP players bypass all permissions.**
- Non-OPs need `minestormparty.admin` or granular nodes (LuckPerms friendly):
  `minestormparty.admin.reload`, `.party.create`, `.party.delete`, `.party.color`,
  `.party.member.add`, `.party.member.remove`, `.player.info`, `.player.remove`.

## Creator
```
/minestormparty creator
```
> Created by **Muvixo**
