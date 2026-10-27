#!/bin/sh
set -eu

attempts=${SEED_WAIT_ATTEMPTS:-150}
i=0
until [ "$(psql -Atc "SELECT to_regclass('public.tb_inventory') IS NOT NULL")" = "t" ]; do
  i=$((i + 1))
  if [ "$i" -ge "$attempts" ]; then
    echo "seed inventory: tb_inventory não existe; suba o inventory-service e rode o seed de novo" >&2
    exit 1
  fi
  sleep 2
done

psql -v ON_ERROR_STOP=1 -q -f /seed/inventory.sql
echo "seed inventory: $(psql -Atc 'SELECT count(*) FROM tb_inventory') SKUs na tabela"
