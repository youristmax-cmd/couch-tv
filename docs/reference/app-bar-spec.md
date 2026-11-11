# App bar — measured from docs/reference/app-bar.png

Reference image is 2000×668. The bar itself spans roughly x 40–1960, y 210–455.
All numbers below are ratios of the bar's own height (H) so they scale cleanly.

| Element | Ratio | At H = 170 px (1080p) |
|---|---|---|
| Bar width : height | ~7.8 : 1 | 1330 × 170 |
| Bar corner radius | fully rounded (H/2) | 85 |
| Horizontal padding (edge → first tile) | 0.20 H | 34 |
| App tile size | 0.78 H square | 132 |
| App tile corner radius | 0.21 × tile | 28 |
| Gap between tiles | 0.10 H | 17 |
| Divider | 1 px, 0.50 H tall, 0.22 H margin each side | 85 tall, 37 margins |
| Utility button | 0.60 H wide × 0.52 H tall, outlined, radius 0.14 H | 102 × 88, radius 24 |
| Gap between utility buttons | 0.10 H | 17 |
| Icon inside utility button | 0.28 H | 48 |

Placement on a 1920×1080 screen: centered horizontally, bottom edge 64 px above the screen bottom.
With 5 apps + 2 utilities the bar is narrower than the reference (7 apps) — keep H and the ratios, let width follow content.

## Material (iOS 26 Liquid Glass look)
- Fill: white at 10–14 % over `backdrop-filter: blur(24px) saturate(1.4)`.
- Border: 1 px, white at 22–28 %.
- Inner highlight: 1 px inset line along the top edge, white at 35 %, fading out by 20 % of H.
- Soft outer shadow: 0 12px 40px rgba(0,0,0,0.35) so it lifts off bright daytime frames.
- Utility buttons: no fill, same border as the bar at 35 %, icon white at 85 %.
- If backdrop-filter is unsupported on the TV WebView: fallback fill white 18 % + border, no blur. Report it.

## App tiles
- Brand mark centered on the app's own tile color, matching the reference: Netflix cream tile with red wordmark, YouTube cream tile with red play, Hulu dark green with green wordmark).
- Source logos from `remote/src/app-logos/` (SVG preferred). Render at exact device pixels, never upscale a raster.
- Focus: scale 1.08, border white 60 %, glow 0 0 0 3px white 25 %. 150 ms ease-out. Neighbours do not move.

## Mapping to Max's config
Apps, in order: Netflix, YouTube, Prime Video, Hulu, Max — from `config.json` `home.apps`, not from the reference.
Utilities: input/display icon → TV inputs; gear → existing Settings tile.
