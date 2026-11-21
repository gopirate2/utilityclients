# Community Discord: webhook or custom bot

Start with an **incoming webhook for release announcements**. It can post a version, change list and download link in an updates channel without running a bot service. The existing GitHub Actions release workflow is the natural place to trigger an announcement after publication. [Discord's webhook documentation](https://docs.discord.com/developers/resources/webhook) describes this channel-posting API.

Use a custom app/bot when the community needs interactions such as `/help`, `/version`, `/theme submit`, theme browsing, support tickets or role management. An ordinary incoming channel webhook does not implement those commands. Bots/apps support slash commands and interactive components; Discord also supports HTTP interaction endpoints, so a slash-command-only app can run as a request-driven service rather than a permanent Gateway connection. See [Discord's first-app guide](https://docs.discord.com/developers/quick-start/getting-started).

## Webhook setup

1. Create an updates channel and create a webhook through its Integrations settings.
2. Store its URL in GitHub Actions secrets as `DISCORD_WEBHOOK_URL`, not in this repository or the client.
3. Add a notification step after a complete release has been published. Include the version, release page and latest installer link. Disable automatic mentions unless an announcement intentionally requests them.
4. Keep announcements separate from a community `#themes` channel where members can post `UCTHEME1:` codes or JSON attachments themselves.

No Discord webhook has been configured or called by this change.

## A small custom bot when it becomes useful

1. Create an application in the [Discord Developer Portal](https://discord.com/developers/applications). Configure a test-server installation and the permissions its features actually need; avoid Administrator.
2. Start with `/help`, `/version` and `/theme submit`. Read version/download data from GitHub Releases so it does not need manual maintenance. Validate submitted themes against [format 1](THEMES.md) and put them into a review queue before promoting them to a community gallery.
3. For a normal connected bot, use a maintained library such as discord.js or discord.py and host the process continuously. Keep its token in environment secrets. For command-only interactions, follow Discord's HTTP tutorial, validate request signatures, and use a request-driven host. This is a different hosting model from a Gateway bot.
4. Use a small persistent database for approved theme IDs, submissions and ticket state; keep command responses private when they contain support details. Test in a private server before installing the app in the community server.

Keep generic moderation in an established moderation tool initially. Build community-specific features only when members need them; use the release webhook alongside the bot so release announcements remain independent.
