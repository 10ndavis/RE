# Zune HD interface, reconstructed

Sources: contemporary reviews ([yipcanjo](https://yipcanjo.wordpress.com/2009/10/01/quick-review-zune-hd/),
[SlashGear](https://www.slashgear.com/microsoft-zune-hd-review-1757141/),
[Bright Hub](https://brighthub.com/electronics/mp3/articles/102064.aspx)) and recollection of the device.

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
| Home menu word | 46 text, ~54 pitch, 12 left margin | Right-hand pane, 86% of width; long words cut at the screen edge |
| Play circle | 19 radius ring, 22 label | Top of the menu; plays all music, or opens now playing while playing |
| Quickplay pane | 86% of width, on the left | Now playing (art with playing/paused), pins, history; square tiles three across |
| Side pane | scaled to 82%, 35% opacity | Shrinks toward its outer edge |
| Section title | menu word × 1.9, top 34 u cropped | The tapped word zooms up into it |
| Pivot header | 42 text, 16 gap, 58 band | Current white, others dimmed; strip shifts with the page |
| List row | 23 primary, 13 secondary, 46 art, 6 vertical padding | |
| Now playing artist / album | 25 bold / 15 gray | Above the art |
| Now playing art | width − 32, top 76 | Square, centre-cropped |
| Current song / next three | 23 white / 16 gray, 21 pitch | Under the art |
| Extras row | 26 icons | Shuffle, repeat, rating: the player's custom actions, along the bottom |
| Controls | play ring 32 radius, skip rings 23, 74 apart | Over the art on tap; scrubber under the art |
| Quick controls | 136 tall | Song, transport rings, stepped volume bar |

## Behaviour

- Home: vertical scroll of the menu; horizontal pan snaps between quickplay and the menu.
  The home button switches between them.
- Transitions: list items swing out like a turnstile hinged on the left edge, staggered top to
  bottom; the tapped word zooms up to become the next screen's cropped title; new lists swing in.
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
