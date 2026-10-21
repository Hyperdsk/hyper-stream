# Sync backend (Supabase)

Optional sync of watch progress, library, settings, remembered sources, repositories and installed
extensions between the Mac, phone and TV apps. Users sign in under Settings, Sync, with an emailed
code (or the sign-in link from the email, pasted in).

## Setup

1. Run `schema.sql` in the project's SQL editor. It creates `hs_sync_items` (one row per synced
   item per user, protected by row level security) and `hs_push`, which saves a batch of changes
   and keeps whichever version of each item is newer.
2. Put the project URL and publishable key in `SyncConfig` (`appCore/.../sync/SupabaseClient.kt`).
   The publishable key is meant to ship in apps; row level security protects the data.

## Sign-in emails

Supabase's built-in email sender has limits: it only sends to members of the project's
organization, only a few emails per hour, and its template can't be edited, so the email has a
sign-in link but no 6-digit code. The apps accept the link pasted in, which works on the Mac and
phone.

For codes (easiest on a TV) and for other people to sign in, set up custom SMTP under
Authentication, Emails (Resend, Brevo, Gmail and others have free plans), then edit the
"Magic link or OTP" template to include the code, for example:

```html
<h2>Your Hyper Stream sign-in code</h2>
<p>Enter this code in the app: <strong>{{ .Token }}</strong></p>
<p>Or <a href="{{ .ConfirmationURL }}">sign in with this link</a>.</p>
```

## How sync works

Each device keeps its own files and syncs in the background: on every pass it pulls what changed
on the server since last time, applies what's newer than its own, then pushes its own changes.
Deletions are sent as deleted rows, so they reach other devices. Downloads, extension files and
snapshot pictures stay on each device; extensions are reinstalled from their repositories.
