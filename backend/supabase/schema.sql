-- Hyper Stream sync. Run once in the Supabase SQL editor (safe to run again).
--
-- One row per synced item per user: watch progress, library bookmarks, settings, the source each
-- title last played from, repositories and installed extensions. The app keeps its own files and
-- syncs in the background; deletions are rows with deleted = true, so they reach other devices.
-- Each user only ever sees their own rows (row level security).

create table if not exists public.hs_sync_items (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- history, bookmark, setting(s), source, repository, extension, search, and kinds later versions add
  kind text not null constraint hs_sync_items_kind_check check (kind ~ '^[a-z]{1,32}$'),
  item_key text not null,
  data jsonb,
  deleted boolean not null default false,
  -- When the change was made on the device, in ms; the newer change wins
  client_updated_at bigint not null,
  -- When the server stored it; devices pull everything after the last time they saw
  updated_at timestamptz not null default now(),
  primary key (user_id, kind, item_key)
);

create index if not exists hs_sync_items_pull on public.hs_sync_items (user_id, updated_at);

-- Projects made before 2026-10-22 only allowed a fixed list of kinds, which rejected search history
-- and made every sync fail: allow any short lowercase kind
alter table public.hs_sync_items drop constraint if exists hs_sync_items_kind_check;
alter table public.hs_sync_items add constraint hs_sync_items_kind_check check (kind ~ '^[a-z]{1,32}$');

-- Limits on what one row may hold. The app sends very long keys as their hash and keeps items
-- over 64 KB on the device, so these only stop a client (or anyone with an account) from filling
-- the database. "not valid": rows already stored aren't checked
alter table public.hs_sync_items drop constraint if exists hs_sync_items_key_size;
alter table public.hs_sync_items add constraint hs_sync_items_key_size check (length(item_key) <= 2048) not valid;
alter table public.hs_sync_items drop constraint if exists hs_sync_items_data_size;
alter table public.hs_sync_items add constraint hs_sync_items_data_size check (data is null or octet_length(data::text) <= 262144) not valid;

alter table public.hs_sync_items enable row level security;

drop policy if exists "Read own sync items" on public.hs_sync_items;
create policy "Read own sync items" on public.hs_sync_items
  for select to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "Add own sync items" on public.hs_sync_items;
create policy "Add own sync items" on public.hs_sync_items
  for insert to authenticated with check ((select auth.uid()) = user_id);

drop policy if exists "Change own sync items" on public.hs_sync_items;
create policy "Change own sync items" on public.hs_sync_items
  for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "Remove own sync items" on public.hs_sync_items;
create policy "Remove own sync items" on public.hs_sync_items
  for delete to authenticated using ((select auth.uid()) = user_id);

-- Saves a batch of changes. A change only replaces what's stored if it was made later, so an old
-- device coming back online can't overwrite newer progress. Times more than 5 minutes ahead of the
-- server's clock are cut back to it, so a device with a wrong clock can't make its changes win
-- forever. Returns how many rows changed.
create or replace function public.hs_push(items jsonb)
returns integer
language plpgsql
security invoker
set search_path = ''
as $$
declare
  changed integer;
begin
  -- Far more than a real library needs; stops one account from filling the database
  if jsonb_array_length(items) > 1000
     or (select count(*) from public.hs_sync_items where user_id = auth.uid()) > 200000 then
    raise exception 'Too many sync items';
  end if;
  insert into public.hs_sync_items (user_id, kind, item_key, data, deleted, client_updated_at, updated_at)
  select auth.uid(), i ->> 'kind', i ->> 'key', i -> 'data', coalesce((i ->> 'deleted')::boolean, false),
         least((i ->> 'at')::bigint, (extract(epoch from now()) * 1000)::bigint + 300000), now()
  from jsonb_array_elements(items) as i
  on conflict (user_id, kind, item_key) do update
    set data = excluded.data,
        deleted = excluded.deleted,
        client_updated_at = excluded.client_updated_at,
        updated_at = now()
    where excluded.client_updated_at > public.hs_sync_items.client_updated_at;
  get diagnostics changed = row_count;
  return changed;
end;
$$;

revoke all on function public.hs_push(jsonb) from public, anon;
grant execute on function public.hs_push(jsonb) to authenticated;

-- TV sign-in. A signed-in phone or Mac asks for a one-time 8-digit code (hs_create_tv_code); the TV
-- sends it to the tv-sign-in edge function, which signs the TV in to the same account. Codes last
-- 10 minutes and work once. Only that function (with the service role) reads this table.
-- Codes come from a secure random source, and the function allows only a few wrong tries per
-- address (hs_tv_attempts), so guessing a live code isn't practical.
create table if not exists public.hs_tv_codes (
  code text primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  expires_at timestamptz not null
);

alter table public.hs_tv_codes enable row level security;

create or replace function public.hs_create_tv_code()
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  new_code text;
begin
  if auth.uid() is null then
    raise exception 'Sign in first';
  end if;
  delete from public.hs_tv_codes where user_id = auth.uid() or expires_at < now();
  loop
    new_code := lpad(((('x' || encode(extensions.gen_random_bytes(4), 'hex'))::bit(32)::bigint) % 100000000)::text, 8, '0');
    begin
      insert into public.hs_tv_codes (code, user_id, expires_at) values (new_code, auth.uid(), now() + interval '10 minutes');
      exit;
    exception when unique_violation then
      -- Taken by someone else right now: pick another
    end;
  end loop;
  return new_code;
end;
$$;

revoke all on function public.hs_create_tv_code() from public, anon;
grant execute on function public.hs_create_tv_code() to authenticated;

-- Wrong TV codes, by address, for the tv-sign-in function's limit. Service role only.
create table if not exists public.hs_tv_attempts (
  ip text not null,
  at timestamptz not null default now()
);
create index if not exists hs_tv_attempts_by_ip on public.hs_tv_attempts (ip, at);
create index if not exists hs_tv_attempts_by_time on public.hs_tv_attempts (at);
alter table public.hs_tv_attempts enable row level security;

-- Takes a TV code (once) and returns its account, or null for a wrong or expired code. Checking
-- the limits, using the code and noting a wrong try happen in one step, one call at a time, so
-- many calls at once can't get past the limits. Each address gets 10 wrong codes per 15 minutes;
-- everyone together 1000 a minute, which keeps guessing a live code (10 minutes, 1 in 100 million
-- a try) impractical even from many addresses, while leaving plenty of room for real sign-ins.
create or replace function public.hs_redeem_tv_code(p_code text, p_ip text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  found uuid;
begin
  perform pg_advisory_xact_lock(hashtext('hs_redeem_tv_code'));
  if (select count(*) from public.hs_tv_attempts where ip = p_ip and at > now() - interval '15 minutes') >= 10
     or (select count(*) from public.hs_tv_attempts where at > now() - interval '1 minute') >= 1000 then
    raise exception 'too_many_attempts';
  end if;
  delete from public.hs_tv_codes where code = p_code and expires_at > now() returning user_id into found;
  if found is null then
    insert into public.hs_tv_attempts (ip) values (p_ip);
    -- Old attempts aren't needed once they're out of every window
    delete from public.hs_tv_attempts where at < now() - interval '1 day';
  end if;
  return found;
end;
$$;

revoke all on function public.hs_redeem_tv_code(text, text) from public, anon, authenticated;
grant execute on function public.hs_redeem_tv_code(text, text) to service_role;
