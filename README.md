# MineStormParty
**Cross-server Party System** - Spigot / Paper / Bukkit 1.8+ - BungeeCord - Velocity

> Created by **Muvixo**

## Architecture
```
   +----------------------+            +------------------------+
   |  Bungee / Velocity   |  <------>  |  Backend servers       |
   |  MineStormParty      |            |  MineStormParty-Bukkit |
   |  (stateless relay)   |            |  SQLite: guilds.db     |
   +----------------------+            +------------------------+
```

- Every **backend** keeps the parties in memory and persists them to its own
  `plugins/MineStormParty/guilds.db` (SQLite, written asynchronously).
- Every change (create / add / remove / leader / color / disband / chat) is broadcast
  through the **proxy relay** to all other backends, so a party works across servers.
- The proxy plugin is **stateless**: it only relays messages. It never trusts messages
  that come from players, only from backend servers.
- Works standalone on a single server too (set `settings.cross-server: false`).

### Cross-server notes
- Plugin messages need at least one player online on the sending server.
- With MySQL, pending invites are also stored in the `msp_invites` table and delivered by a
  poller, so invites reach players on other servers even when the proxy relay can't carry them.
- If a backend was offline while changes happened, run `/mspa sync` on a server that
  has the correct data to re-broadcast every party.

## Build
Requires JDK 17+ (the Velocity module is skipped automatically on older JDKs).
```bash
mvn clean package
```
Artifacts:
- `bukkit/target/MineStormParty-Bukkit-<v>.jar`
- `bungee/target/MineStormParty-Bungee-<v>.jar`
- `velocity/target/MineStormParty-Velocity-<v>.jar`

## Commands
### Player - `/party` (aliases `/p`, `/minestormparty`, `/msp`) and `/pc <msg>`
`create - invite - accept - deny - leave - disband - kick - transfer - list - info - chat - color - creator - help`

Inviting while not in a party auto-creates one (`settings.auto-create-on-invite`).

### Admin - `/mspa`
- **OP players bypass all permissions.**
- Non-OPs need `minestormparty.admin` or granular nodes (LuckPerms friendly):
  `minestormparty.admin.reload`, `.save`, `.sync`, `.party.create`, `.party.delete`,
  `.party.color`, `.party.member.add`, `.party.member.remove`, `.player.info`, `.player.remove`.

## PlaceholderAPI
`%minestormparty_has%`, `_leader`, `_leader_colored`, `_is_leader`, `_color`, `_color_code`,
`_size`, `_members`, `_online`, `_prefix`

## Creator
```
/party creator
```
> Created by **Muvixo**
