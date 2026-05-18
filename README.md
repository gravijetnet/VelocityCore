# VelocityCore

A Velocity proxy plugin providing JoinMe broadcasting, token management, in-game support ticketing with Discord integration, bug reporting, player tracking, advertising, and utility commands.

---

## Table of Contents

- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration](#configuration)
- [Features](#features)

---

## Commands

### Player Commands

#### `/joinme`
Broadcasts a clickable message to all online players announcing your current server. Costs one token and is subject to a cooldown.

#### `/tokens`
Displays your current JoinMe token balance (monthly and permanent) and remaining cooldown time.

#### `/joinmecolor <color|reset>`
Sets the color of your JoinMe broadcast message.
- Use legacy color codes: `&c`, `&a`, `&b`, etc.
- Use hex colors: `&#rrggbb`
- Use `reset` to remove your custom color

#### `/ping [player]`
Displays your current ping in milliseconds. Provide a player name to check someone else's ping. Results are color-coded: green (<70 ms), yellow (<200 ms), red (200+ ms).

#### `/find <player>`
Shows which server an online player is currently on. If the player is offline, shows their last known server and the time they were last seen.

#### `/hoster`
Displays the network's hosting information.

#### `/bug <title> <description>`
Submits a bug report directly to the configured Discord channel.

---

### Support Commands

#### `/support de`
Opens a new German-language support ticket.

#### `/support en`
Opens a new English-language support ticket.

#### `/support chat <message>`
Sends a message inside your active support ticket session.

#### `/support rate <1-5>`
Rates a closed support session on a scale of 1–5.

#### `/support help`
Displays the support help menu.

#### `/spc <message>`
Shorthand for `/support chat <message>`.

---

### Staff Support Commands
Require the `velocitycore.support.staff` permission group.

#### `/support claim <player> [--force]`
Claims an open support ticket. Use `--force` to take over a ticket already claimed by another staff member (requires the `.force` sub-permission).

#### `/support close`
Closes the currently active support session.

#### `/support ban <player> <duration>`
Temporarily bans a player from creating support tickets. Duration format: `1d`, `2h`, `30m`.

#### `/support unban <player>`
Removes a support ban from a player.

---

### Admin Commands

#### `/vcore` (alias: `/velocitycore`)
Reloads the plugin configuration live without restarting the proxy.

#### `/adminjoinme` (alias: `/ajm`)
Admin management of the JoinMe system.

| Subcommand | Arguments | Description |
|---|---|---|
| `tokens` | `<player>` | View a player's token balance |
| `forcejoinme` | `<player>` | Trigger a JoinMe broadcast on behalf of a player |
| `addtokens` | `<player> <amount>` | Add permanent tokens to a player |
| `removetokens` | `<player> <amount>` | Remove permanent tokens from a player |

---

## Permissions

### JoinMe

| Permission | Description |
|---|---|
| `velocitycore.joinme.use` | Use `/joinme` |
| `velocitycore.joinme.color` | Use `/joinmecolor` |
| `velocitycore.joinme.cooldown.bypass` | Bypass the JoinMe cooldown |

### Tokens

| Permission | Description |
|---|---|
| `velocitycore.tokens.view` | Use `/tokens` to view balance |
| `core.joinme.tokens.<number>` | Grant a specific number of monthly tokens (e.g. `core.joinme.tokens.5`) |
| `core.joinme.tokens.unlimited` | Unlimited tokens — never consumed on use |
| `core.joinme.tokens.*` | All token permissions |

### Utility

| Permission | Description |
|---|---|
| `velocitycore.ping` | Use `/ping` |
| `velocitycore.find` | Use `/find` |

### Support (Staff)

| Permission | Description |
|---|---|
| `velocitycore.support.staff.view` | Access the staff support interface |
| `velocitycore.support.staff.claim` | Claim support tickets |
| `velocitycore.support.staff.close` | Close support sessions |
| `velocitycore.support.staff.ban` | Ban players from support |
| `velocitycore.support.staff.unban` | Unban players from support |
| `velocitycore.support.staff.force` | Force-claim tickets with `--force` |
| `velocitycore.support.staff.*` | All staff support permissions |

### Admin

| Permission | Description |
|---|---|
| `velocitycore.admin` | Use `/vcore` reload |
| `velocitycore.admin.joinme` | Full access to `/adminjoinme` |

---

## Configuration

### `config.yml`

#### Database
```yaml
database:
  host: localhost
  port: 3306
  database: velocity
  username: velocity
  password: velocity
```

#### JoinMe
```yaml
joinme:
  cooldown: 60          # Seconds between broadcasts
  tokens:
    initial: 1          # Tokens given on a player's very first join
    monthly: 1          # Tokens each player receives on monthly reset
    permanent: 0        # Permanent (non-expiring) tokens
```

#### Advertising
```yaml
advertising:
  enabled: true
  interval_minutes: 5   # How often to broadcast (minutes)
  messages:
    - "Check out our website: example.invalid"
```

#### Support & Discord
```yaml
support:
  enabled: true
  discord:
    enabled: true
    bot-token: "YOUR_BOT_TOKEN"
    status: "example.invalid"
    guild-id: "YOUR_GUILD_ID"
    support-category-id: "YOUR_CATEGORY_ID"
    transcript-channel-id: "YOUR_TRANSCRIPT_CHANNEL_ID"
    staff-role-id: "YOUR_STAFF_ROLE_ID"
    management-role-id: "YOUR_MANAGEMENT_ROLE_ID"
    channel-name-format: "{status}-{language}-{player}-{server}"
    open-color: "BLUE"
    claimed-color: "YELLOW"
    transcript-color: "PURPLE"
  bug-channel-id: "YOUR_BUG_CHANNEL_ID"
  bug-tag-id: ""
  max-open-tickets: 3           # Max concurrent open tickets per player
  ticket-cooldown-minutes: 10   # Cooldown between opening new tickets
  staff-permission: "velocitycore.support.staff"
```

### `messages.yml`
All player-facing messages. Supports MiniMessage formatting (`<red>`, `<bold>`, etc.) as well as legacy `&` color codes.

---

## Features

### JoinMe
Players can broadcast a clickable message to all online players announcing their current server. The message includes the player's name, their server, and a click-to-join link. Broadcasts consume a token and enforce a configurable cooldown. Players can customize the color of their own broadcast.

### Token System
Each player has a monthly token balance (reset each month) and a permanent balance (never expires). Token counts can be granted via permission nodes (`core.joinme.tokens.<number>`), assigned manually via `/adminjoinme`, or set to unlimited with `core.joinme.tokens.unlimited`. Monthly tokens reset automatically.

### Support Tickets
Players open a ticket in either German or English. A Discord channel is created automatically for the session. Staff can claim, chat in, and close tickets from in-game or Discord. Closed sessions produce a full transcript posted to a configured channel. Players can rate the session after it closes. Staff can temporarily ban players from opening tickets. Sessions auto-close after 1 hour of inactivity.

### Bug Reports
Players submit a title and description with `/bug`. The report is posted as an embed to a dedicated Discord channel.

### Player Tracking
The plugin records each player's join time, last known server, and disconnect time. This data powers the `/find` command's offline lookup.

### Advertising
A configurable list of messages is broadcast to all players on a repeating interval. Can be disabled entirely from the config.

### Discord Integration
A Discord bot runs alongside the plugin. It manages support ticket channels (create, update, archive), posts transcripts, displays staff and management roles, and receives bug reports. The bot status is configurable.
