# Keyboard (CptKeyboard)

A minimal on-screen keyboard (`com.plaything.keyboard`), for example for the PIN that a car
stereo can ask for at Bluetooth pairing. AOSP's LatinIME is much larger (dictionaries, tens of
MB of memory while open).

Source: `device/spotify/superbird/apps/CptKeyboard/`: one `InputMethodService`
(`KeyboardService`) and one custom view (`KeyboardView`). Colors from the home screen design,
sizes in pixels (density 240).

| Field | Layout | Height |
|---|---|---|
| Number, phone, date/time | One row: `1`-`9`, `0`, backspace, done | 106 px |
| Text | QWERTY: 3 letter rows with shift and backspace, then `?123`, `,`, space, `.`, done | 233 px |
| `?123` | Digits, `@#$%&-+()/`, `*"':;!?_`, backspace, then `ABC`, `,`, space, `.`, done | 233 px |

- **Preview bar** (38 px, above the keys): the field's text around the cursor, with a cursor
  bar; the field's hint when it is empty; dots for password fields. The keyboard covers about
  half of the screen, so the field itself can be hidden.
- **Touch:** a key types when the finger lifts on it. Backspace types at once and repeats while
  held (after 400 ms, every 60 ms). Shift: off, once, locked.
- **Knob:** while the keyboard shows, it gets the key events before the app. Turning moves a
  highlight over the keys in reading order (it wraps at the ends); pressing types the highlighted
  key. The first press without a highlight only shows it.
- **Back** (front button) closes the keyboard.
- **Done:** the field's action, else a new line in a multi-line field, else it closes the
  keyboard.
- No full-screen text box in landscape: the field stays visible above the keyboard.
- **Selection:** `method.xml` has `android:isDefault="true"` and an `en_US` subtype, so Android
  selects it when no input method is selected.
- Icons: Phosphor `backspace`, `arrow-fat-up`, `arrow-fat-lines-up`, `check` (MIT,
  `CptKeyboard/NOTICE`).

Bluetooth PIN: Settings shows a number field; the checkbox "PIN contains letters or symbols"
makes it a text field.
