<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Recipe ingredients (US 1.2, #339)

`RecipeIngredientProvider` takes an `ItemRepository`. `ingredients(householdId,
userId)` reads the current fridge snapshot and returns sorted, unique ingredient
names. `suggest(householdId, userId, request)` passes those names to the supplied
suspending recipe request and returns its results. If no usable ingredients
remain, it returns an empty list without invoking the request.

The service adapter from #338 can supply its repository method as `request`.
That task owns the service model, transport encoding and network error mapping;
this task adds no service dependency or UI. Read failures and cancellation
propagate rather than being displayed as an empty fridge.

Availability follows the existing fridge policy: only ACTIVE items with positive
quantity, belonging to the requested household, and shared or owned by the
requesting user. Other members' personal items are excluded. Expired ACTIVE
items remain eligible, as in the fridge screen; expiration is not an automatic
discard. This resolves the issue's open policy point provisionally and can be
changed if the product owner chooses a different expiry policy.

Normalization applies Unicode NFKC, collapses whitespace, trims, lowercases with
Locale.ROOT, drops blank names and deduplicates. Accents and meaningful
punctuation are retained. No translation, brand stripping or singularization is
guessed. Quantities do not duplicate ingredients. Names are a list of raw values,
not a manually joined/encoded URL; the eventual service adapter owns encoding.

Unit tests cover empty/single/mixed inventories, lifecycle and visibility,
expiration, quantities, Unicode and locale handling, fresh snapshots, blank
scope, read failure and cancellation. No Firestore implementation or UI changes
are introduced.

## Validation

- All 10 `RecipeIngredientProviderTest` tests pass, including in the full debug
  suite and the targeted rerun.
- `ktfmtCheck` and `lintDebug` pass.
- `check` was attempted: the debug suite ran 1,402 tests with one failure in the
  unchanged `FridgeItemTest.localFilePathAndFileUriAreDecodedByCoil` (a 5-second
  Compose timeout). That test also fails in isolation on this Windows machine.
  The remaining release-variant work was stopped after the debug failure; a
  green full `check` is not claimed.
- No Android/Firebase emulator tests were run for this pure ingredient adapter.

OpenAI Codex (GPT-6) generated the implementation, tests and these notes and ran
the checks above. Human review has not yet been performed.
