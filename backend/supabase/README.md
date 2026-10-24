# Sync backend (Supabase)

Optional sync of watch progress, library, settings, remembered sources, repositories and installed
extensions between the Mac, phone and TV apps. Users sign in under Settings, Sync, with an emailed
code (or the sign-in link from the email, pasted in).

## Setup

1. Run `schema.sql` in the project's SQL editor. It creates `hs_sync_items` (one row per synced
   item per user, protected by row level security) and `hs_push`, which saves a batch of changes
   and keeps whichever version of each item is newer. Run it again after updating, then deploy
   `functions/tv-sign-in` again: newer versions of the function need `hs_redeem_tv_code` from it.
2. Put the project URL and publishable key in `SyncConfig` (`appCore/.../sync/SupabaseClient.kt`).
   The publishable key is meant to ship in apps; row level security protects the data.

## Sign-in

- **Mac and phone:** the app emails a sign-in link. Opening it on the same device signs the app in by
  itself: the link returns to `http://127.0.0.1:53682/auth`, a page the Mac app serves, or to
  `hyperstream://auth`, which opens the Android app. Both are in the project's allowed redirect URLs
  (Authentication, URL Configuration). The link, or the address it opened, can also be pasted in.
- **TV:** a signed-in phone or Mac shows a one-time 8-digit code (Settings, Sync, Sign in another device,
  from `hs_create_tv_code`). The TV sends it to the `tv-sign-in` edge function
  (`functions/tv-sign-in`, deployed with "Verify JWT" off), which signs the TV in to the same account.
- Turn **"Confirm email" on** (Authentication, Sign In / Providers). With it off, anyone can create
  an account for someone else's email address with a password they choose, before that person
  ever signs in. When the real owner later signs in with an emailed link, they land in that
  account, and whoever made it can see their library. (The app also never adds a repository just
  because the account lists it: Settings, Sync asks first, since extensions run code.) Confirming
  needs email sending, as below.

- **Password:** an account can also be made with an email and password in the app; the account
  works once the address is confirmed from the email Supabase sends. Accounts made with a link can
  set a password under Settings, Sync.
- **Forgot password:** under Password, Forgot password, either email a reset link (which signs the
  device in and asks for a new password; it needs email sending, as below) or enter a code from
  another device that's still signed in (Sign in another device), with no email.

Supabase's built-in email sender only sends to members of the project's organization, a few per
hour, so other people should use a password, or you can set up custom SMTP under Authentication,
Emails (Resend, Brevo and others have free plans) to send them links.

## How sync works

Each device keeps its own files and syncs in the background: on every pass it pulls what changed
on the server since last time, applies what's newer than its own, then pushes its own changes.
Deletions are sent as deleted rows, so they reach other devices. Downloads, extension files and
snapshot pictures stay on each device; extensions are reinstalled from their repositories, once
the user has agreed to add those repositories on that device.
