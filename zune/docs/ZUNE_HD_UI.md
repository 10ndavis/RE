# Zune HD interface, reconstructed

A clean-room reconstruction from how the Zune HD (2009, firmware 4.x) looked and behaved. No
Microsoft firmware, code, images or fonts are used. Sizes are in Zune pixels: the device was
272 x 480 px in portrait, and the launcher scales 1 Zune pixel to `screen width / 272`, so on a
1080 px wide phone 1 u ≈ 4 px.

## Language

- Everything lowercase. Type is the interface: almost no chrome, icons or boxes.
- Two weights: light for headers, menus and titles; regular for small secondary text.
- White on black or on a dark, colourful backdrop. Secondary text gray (#8C8C8C), inactive headers
  white at 40%.
- Text is allowed to run off the right edge; the cut-off hints there is more to the side.
- Motion explains navigation: the outgoing list sweeps left item by item, the new screen slides in
  from the right, items lean ("tilt") toward the finger when pressed.

## Screens and metrics

| Element | Size (u) | Notes |
|---|---|---|
| Status strip | 24 tall, 13 text | Clock left; battery and playing mark right |
| Home menu word | 46 text, ~54 pitch, 12 left margin | Pane is 86% of width; long words cut at its edge |
| Quickplay pane | 92% of width | Section headers 30; art tiles in two columns, 6 gap |
| Pivot header | 42 text, 16 gap, 58 band | Current white, others dimmed; strip shifts with the page |
| List row | 23 primary, 13 secondary, 46 art, 6 vertical padding | |
| Now playing art | width − 32, top 40 | Square, centre-cropped |
| Now playing giant artist | 168 text, ~28% white, 22 u/s drift | Behind the art, above the titles |
| Now playing titles | 27 / 15 / 14 | Title, artist, album; bottom-left, lowest at height − 32 |
| Controls | play ring 32 radius, skip rings 23, 82 apart | Scrubber 3 thick under the art, times 12 |
| Quick controls | 136 tall | Song, transport rings, stepped volume bar |

## Behaviour

- Home: vertical scroll of the menu; horizontal pan snaps between menu and quickplay.
  Home button returns to the top of the menu.
- Pivots: drag sideways with rubber banding at the ends; a flick moves one page; tapping a
  header jumps to it.
- Now playing: tap shows controls, which fade after 5 s while playing; swipe left/right for
  next/previous; drag the scrubber to seek; pull down to close.
- Hardware volume keys show the Zune volume overlay instead of the system slider.

## Deliberate differences

- Phones are taller than 480 u, so the extra height goes to the menu list and to the space
  between the art and the titles on now playing.
- Android's system navigation stays visible outside now playing, so back and home gestures work.
- The Zune's marketplace, radio tuner and social features are mapped to installed Android apps.
