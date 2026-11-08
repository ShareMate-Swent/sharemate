# Migros receipt regression fixture

`inge-migros-receipt.jpg` is the receipt image shared by Inge in the review of
ShareMate-Swent/sharemate PR #227. It is retained unchanged to reproduce the OCR
case reported in that review, with the contributing user's explicit instruction to use it
as a regression fixture.

Source: https://github.com/user-attachments/assets/73644fe3-0272-4bc7-bec1-aa93cbfa2a70

The receipt visibly contains product names, prices and trailing tax-group digits.
The instrumented test checks selected printed product/price pairs using real ML
Kit recognition; it does not claim all receipt layouts are supported. The
generated-image test remains a separate, controlled OCR regression case.

The source did not provide a license statement; this note does not assert one.
The image includes store, transaction and cashier identifiers and a purchase
timestamp, but no visible customer name or unmasked payment-card number.

Co-authored-by: Codex
