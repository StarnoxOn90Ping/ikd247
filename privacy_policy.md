# Privacy Policy

Last updated: November 10, 2026

## Introduction

This policy explains what Kiwami does with your information. Kiwami is an AniList tracking client for Android. It is an independent fork of [Dantotsu](https://git.rebelonion.dev/rebelonion/Dantotsu) and is not operated by the Dantotsu developers.

The short version: Kiwami keeps what it needs on your device, talks only to the services you choose to use, and does not sell your data.

## Information stored on your device

Kiwami stores the following locally, on your device only:

- Your AniList authentication token, so you stay signed in
- Your Discord login token, only if you connect Discord
- A short-lived sign-in token for the comments service, only if you turn comments on
- App settings and preferences
- Your streak, along with your watch and reading time for the day
- Calendar and airing data, cached so they work offline
- Custom covers and banners you set for titles
- Downloaded anime, manga and novels, and your download queue
- Backups you create
- Log files, only if you turn on logging

None of this is sent to the Kiwami developer. Clearing the app's data, or uninstalling the app, removes it.

## Services Kiwami connects to

### AniList
When you sign in, Kiwami uses the official AniList API to read and update your lists, profile, activity and airing schedule. Your login happens through AniList. Kiwami does not see or store your AniList password. What AniList collects is governed by [AniList's terms and privacy policy](https://anilist.co/terms).

### Comments (optional, run by Dantotsu)
Kiwami can show and post comments on titles. This feature uses the comments server of the Dantotsu project, **not a server run by the Kiwami developer**. It is controlled by the "Enable Comments" switch in Settings → Accounts, and Kiwami only contacts the comments server when that switch is on.

When comments are on, Kiwami sends the following to that server:

- Your AniList access token, to sign you in to the comments service
- Your AniList username and avatar, which are shown next to comments you post
- The text of comments you write, edit or delete, along with the title and episode they belong to
- Your votes and any reports you submit
- Regular background requests to check for replies to your comments

The server is operated and moderated by the Dantotsu team, and how it handles this information is up to them. See [Dantotsu's privacy policy](https://github.com/rebelonion/Dantotsu/blob/main/privacy_policy.md). If you do not want any of this shared, leave comments switched off, or switch them off at any time.

### Discord (optional)
If you turn on Discord Rich Presence, Kiwami shares what you are currently watching or reading (the series and episode) with Discord for as long as you are using the app. This is off unless you enable it. See [Discord's privacy policy](https://discord.com/privacy).

### GitHub
Kiwami contacts GitHub to check whether a newer release is available, to show the contributors list on the Developers screen, and to load this policy when you open it. GitHub can see your IP address when this happens. See [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

### Extensions and addons
Kiwami supports extensions made by independent third parties. They are not provided, reviewed or endorsed by Kiwami, and they have their own behaviour and their own data practices. Review any extension you install and the services it connects to.

Optional addons, such as the download and torrent addons, are installed separately. Addons are downloaded from the Dantotsu project's addon repository on GitHub. If you use torrents, note that torrenting by its nature makes your IP address visible to other peers.

### Casting
If you cast to a compatible device on your network, the video you are watching is streamed to that device.

## What we do not do

- The Kiwami developer does not run any server that stores your personal information.
- We do not use analytics or crash-reporting services. Crash information stays on your device unless you choose to share a log yourself.
- We do not sell or rent your data.
- We do not track what you watch or read.
- We do not include advertising.

## Permissions

Kiwami asks for permissions such as internet and network access, notifications, exact alarms (for airing reminders), starting at boot, foreground services and battery-optimization exemption (for background checks and downloads), audio and Bluetooth (for playback), and package installation and removal (for extensions and updates). You can review or revoke permissions at any time in your device settings.

## Data deletion

Most of your data lives on your device, so you can delete it yourself by clearing the app's storage or uninstalling Kiwami.

- Comments: you can delete your own comments in the app, and switch comments off in Settings → Accounts. To have other data held by the comments server removed, contact the Dantotsu team.
- AniList and Discord: use those services directly.

## Children's privacy

Kiwami is not directed to children under 13, and we do not knowingly collect information from them.

## Changes to this policy

We may update this policy from time to time. Changes will be posted on this page with a new "Last updated" date.

## Contact

Questions about this policy: [Kiwami Issues](https://github.com/StarnoxOn90Ping/Kiwami/issues)
