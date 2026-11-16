# Branding

AshFall has one mark and one palette. The source of truth is
[`assets/brand/`](../assets/brand/README.md): `ashfall-icon.svg` (circular tile)
and `ashfall-mark.svg` (the same glyph, one colour via `currentColor`). Every
surface below is derived from those two files; nothing is redrawn for GitHub.

| Surface | File | Size |
|---|---|---|
| Org avatar | `assets/brand/png/ashfall-avatar-512.png` | 512x512, < 1 MB |
| Org profile README | `ashfall-rom/.github` → `profile/ashfall-icon.png` | 512x512 |
| Repo README headers | `assets/brand/ashfall-icon.svg` in each repo | 88-104 px |
| Repo social previews | `assets/brand/social/<repo>.png` | 1280x640, < 1 MB |

Regenerate the rasters after changing either SVG:

```sh
assets/brand/render.sh          # writes png/ and social/
assets/brand/render.sh --check  # fails if the committed PNGs are stale
```

The cards carry the mark, not the tile: a tile composited onto a Soot card
shows its own edge, so the card draws the bloom once and places the glyph in it.
The avatar is the tile flattened onto Soot, because GitHub crops avatars to a
circle and a transparent corner would show the page underneath.

## What is committed where

* `ashfall-rom/ashfall` — the full brand set (`assets/brand/`), plus
  `docs/branding.md` (this file).
* `ashfall-rom/ashfall-install`, `ashfall-rom/rp5-vendor_boot` — `assets/brand/`
  with the icon, the mark, their own social card and a pointer back here, so
  their READMEs render without depending on another repository.
* `ashfall-rom/.github` — `profile/README.md` (the org landing page) and
  `profile/ashfall-icon.png` (the logo it shows).

## What still needs the web UI

GitHub exposes no API for an organisation avatar or a repository social
preview, so those two uploads are manual. Everything else is committed or set
with `gh`.

1. **Org avatar** — <https://github.com/organizations/ashfall-rom/settings/profile>
   → *Upload new picture* → `assets/brand/png/ashfall-avatar-512.png`.
2. **Social previews** — for each repository, *Settings → Social preview →
   Edit → Upload an image*:
   * <https://github.com/ashfall-rom/ashfall/settings> → `assets/brand/social/ashfall.png`
   * <https://github.com/ashfall-rom/ashfall-install/settings> → `assets/brand/social/ashfall-install.png`
   * <https://github.com/ashfall-rom/rp5-vendor_boot/settings> → `assets/brand/social/rp5-vendor_boot.png`

The org profile text is API-managed, but only with a token carrying
`admin:org`. With a token that has it:

```sh
gh api -X PATCH orgs/ashfall-rom \
  -f name='AshFall' \
  -f description='A LineageOS 24.0 (Android 17) ROM for the Retroid Pocket 5 (Snapdragon 865 / SM8250).'
```

## Verify

```sh
gh api graphql -f query='query { repository(owner:"ashfall-rom", name:"ashfall") {
  usesCustomOpenGraphImage openGraphImageUrl } }'
gh api orgs/ashfall-rom --jq '{avatar_url, description}'
```

`usesCustomOpenGraphImage: true` means the uploaded card is live. An avatar
change is visible immediately on the org page; GitHub's CDN may serve the old
`?v=` URL for a few minutes.

## Adding a repository

1. Add a row to `CARDS` in `assets/brand/render.sh` with the repo name and a
   two-line tagline, run the script, commit the card.
2. Copy `assets/brand/` (icon, mark, card, README pointer) into the new repo and
   put the icon at the top of its README.
3. Upload the card through the repo's social preview settings.
