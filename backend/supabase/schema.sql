-- Hyper Stream sync. Run once in the Supabase SQL editor (safe to run again).
--
-- One row per synced item per user: watch progress, library bookmarks, settings, the source each
-- title last played from, repositories and installed extensions. The app keeps its own files and
-- syncs in the background; deletions are rows with deleted = true, so they reach other devices.
-- Each user only ever sees their own rows (row level security).

create table if not exists public.hs_sync_items (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind text not null check (kind in ('history', 'bookmark', 'settings', 'source', 'repository', 'extension')),
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
-- device coming back online can't overwrite newer progress. Returns how many rows changed.
create or replace function public.hs_push(items jsonb)
returns integer
language plpgsql
security invoker
set search_path = ''
as $$
declare
  changed integer;
begin
  insert into public.hs_sync_items (user_id, kind, item_key, data, deleted, client_updated_at, updated_at)
  select auth.uid(), i ->> 'kind', i ->> 'key', i -> 'data', coalesce((i ->> 'deleted')::boolean, false), (i ->> 'at')::bigint, now()
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
    new_code := lpad(floor(random() * 100000000)::bigint::text, 8, '0');
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
