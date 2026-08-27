# OTP Extractor 1.0.0-alpha.2

Release candidate: `generic-local-seed17-4bit.cact`

- Model SHA-256: `3268ef45dbce50778c7f67cc26baaa8cc4a7745011c5e228fa4b8bfd05beb04e`
- Model size: 23,174,991 bytes
- Pack SHA-256: `49128df81230aaeea80de99a832223565f608ff39dd8c194766f5212197a8b5d`
- Pack size: 21,811,980 bytes
- Tool contract: Needle V2 no-call schema

Fresh evaluation on August 27, 2026:

| Evaluation | Code exact | Negative rejection |
| --- | ---: | ---: |
| Original held-out (200) | 170 / 176 (96.59%) | 24 / 24 (100%) |
| Development (402) | 244 / 261 (93.49%) | 141 / 141 (100%) |
| Locked (401) | 274 / 285 (96.14%) | 116 / 116 (100%) |
| Five challenge messages | 5 / 5 (100%) | n/a |

Optional source exact is lower than the previous official model and is accepted
for this alpha because grounded code extraction and rejection are the
product-critical outputs. NeedleBub falls back to the notification sender when
the model omits or fails to ground a source.

The detailed evaluation predictions remain in the private OTPNeedle workspace
and are not included in this public repository or Android package.
