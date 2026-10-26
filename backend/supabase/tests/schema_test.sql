-- Checks schema.sql's rules on a plain Postgres (see run.sh). Any failed assert stops with an error.
\set ON_ERROR_STOP on

insert into auth.users values
  ('00000000-0000-0000-0000-00000000000a', 'a@example.com'),
  ('00000000-0000-0000-0000-00000000000b', 'b@example.com');
grant select, insert, update, delete on all tables in schema public to authenticated, service_role;

-- ---------- hs_push: newer wins, clocks far ahead are cut back ----------
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', false);

do $$
declare n integer; at bigint; d jsonb;
begin
  n := public.hs_push('[{"kind":"history","key":"k1","data":{"p":1},"at":1000}]');
  assert n = 1, 'first save stored';
  n := public.hs_push('[{"kind":"history","key":"k1","data":{"p":0},"at":500}]');
  assert n = 0, 'an older change is ignored';
  n := public.hs_push('[{"kind":"history","key":"k1","data":{"p":2},"at":2000}]');
  assert n = 1, 'a newer change replaces it';
  select data into d from public.hs_sync_items where kind = 'history' and item_key = 'k1';
  assert d = '{"p":2}', 'the newer data is kept';

  perform public.hs_push('[{"kind":"setting","key":"far","data":{},"at":99999999999999}]');
  select client_updated_at into at from public.hs_sync_items where item_key = 'far';
  assert at <= (extract(epoch from now()) * 1000)::bigint + 300000, 'a time far ahead is cut back';
end $$;

-- ---------- limits on keys and data ----------
do $$ begin
  perform public.hs_push(jsonb_build_array(jsonb_build_object('kind', 'history', 'key', repeat('x', 3000), 'data', '{}'::jsonb, 'at', 1)));
  raise exception 'a 3000-character key should be refused';
exception when check_violation then null; end $$;

do $$ begin
  perform public.hs_push(jsonb_build_array(jsonb_build_object('kind', 'history', 'key', 'big', 'data', jsonb_build_object('x', repeat('y', 300000)), 'at', 1)));
  raise exception 'a 300 KB item should be refused';
exception when check_violation then null; end $$;

do $$ begin
  perform public.hs_push('[{"kind":"Bad Kind","key":"k","data":{},"at":1}]');
  raise exception 'a kind outside the pattern should be refused';
exception when check_violation then null; end $$;

-- ---------- row level security: each account sees only its own rows ----------
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', false);
do $$
declare n integer;
begin
  select count(*) into n from public.hs_sync_items;
  assert n = 0, 'another account sees none of the rows';
  update public.hs_sync_items set data = '{"hacked":true}';
  get diagnostics n = row_count;
  assert n = 0, 'another account changes none of the rows';
end $$;

-- ---------- TV codes ----------
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', false);
create temporary table tv_code as select public.hs_create_tv_code() as code;
grant select on tv_code to service_role;

-- Only the sign-in function's server key may take codes
do $$ begin
  perform public.hs_redeem_tv_code('12345678', '1.1.1.1');
  raise exception 'a signed-in user should not be able to redeem codes';
exception when insufficient_privilege then null; end $$;

reset role;
select set_config('request.jwt.claim.sub', '', false);
set role service_role;
do $$
declare code text; who uuid; i integer;
begin
  select t.code into code from tv_code t;
  assert code ~ '^[0-9]{8}$', 'codes are 8 digits';

  -- Nine wrong tries from one address are allowed, each returning nothing
  for i in 1..9 loop
    who := public.hs_redeem_tv_code(lpad(((code::bigint + i) % 100000000)::text, 8, '0'), '9.9.9.9');
    assert who is null, 'a wrong code signs nobody in';
  end loop;

  -- The right code works, once
  who := public.hs_redeem_tv_code(code, '9.9.9.9');
  assert who = '00000000-0000-0000-0000-00000000000a', 'the right code gives its account';
  who := public.hs_redeem_tv_code(code, '9.9.9.9');
  assert who is null, 'a code works only once';

  -- That was the tenth wrong try: the address is now blocked, even for a right code
  begin
    perform public.hs_redeem_tv_code('00000000', '9.9.9.9');
    raise exception 'the eleventh try should be refused';
  exception when raise_exception then
    if sqlerrm <> 'too_many_attempts' then raise; end if;
  end;

  -- Other addresses aren't affected
  who := public.hs_redeem_tv_code('00000000', '8.8.8.8');
  assert who is null, 'another address can still try';
end $$;
reset role;

select 'schema tests passed' as result;
