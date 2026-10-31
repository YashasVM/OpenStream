# OpenStream-IRL progress

Public status page: https://a.yash0.in/irl

This is the separate `openstream-irl-progress` Worker. It owns only the exact
`a.yash0.in/irl` route and `a.yash0.in/irl/*`. The existing
`openstream-quality-report` custom domain and all other routes stay in place.
There are no secrets or backend data bindings in this Worker.

## Update the checklist

Edit `public/status.json`, including its `updated` date. Each item has a title,
detail, status (`implemented`, `in-progress`, or `pending`), and boolean
`deviceTested`. The page calculates counts from the items. Device testing is
an independent count that overlaps the three implementation states.

Keep a feature in progress until its code and build checks have been verified.
Only set `deviceTested` after real-phone verification. Build success does not
establish field reliability. Android emulator testing is excluded.

## Check and publish

From the repository root, using the Wrangler dependency in `openstream-irl`:

```sh
node openstream-irl/progress/check.mjs
node openstream-irl/node_modules/wrangler/bin/wrangler.js deploy --config openstream-irl/progress/wrangler.jsonc --dry-run
node openstream-irl/node_modules/wrangler/bin/wrangler.js deploy --config openstream-irl/progress/wrangler.jsonc
```

The final command publishes both the page and JSON. Edit-and-deploy is required;
this page does not automatically discover code changes or build results.
Before the first deployment, the zone's routes were inspected and neither IRL
route existed. Do not change this Worker to use a whole-host custom domain.

For local preview:

```sh
node openstream-irl/node_modules/wrangler/bin/wrangler.js dev --config openstream-irl/progress/wrangler.jsonc --port 8792
```

Open `http://localhost:8792/irl`. If the installed local runtime does not yet
support the configured date, use `--compatibility-date` with its supported date
for local development. Production deployed with `2026-10-21`.
