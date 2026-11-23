<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Recipe ingredients (US 1.2, #339)

`RecipeIngredientProvider` takes an `ItemRepository`. `ingredients(householdId,
userId)` reads the server-refreshed fridge snapshot and returns sorted, unique ingredient
names. `suggest(householdId, userId, request)` passes those names to the supplied
suspending recipe request and returns its results. If no usable ingredients
remain, it returns an empty list without invoking the request.

The service adapter from #338 can supply its repository method as `request`.
That task owns the service model, transport encoding and network error mapping;
this task adds no service dependency or UI. Read failures and cancellation
propagate rather than being displayed as an empty fridge.

The one-shot `ItemRepository.getFridgeItemsFromServer` read explicitly requires a
server response, using Firestore `Source.SERVER`; it never falls back to a cached
snapshot. Offline/unavailable and permission failures propagate, and cancellation
stops waiting. Firestore can still overlay locally pending writes on the server
result. The regular fridge listener and offline-first writes are unchanged.

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
scope, read failure and cancellation. Repository-boundary regressions cover
empty/stale cached snapshots followed by nonempty/empty server results, and a
server failure after a cached snapshot. There are no UI changes.

## Validation

The initial revision passed hosted CI and SonarCloud (100% new-code coverage).
The review follow-up adds a server-only read and cached-then-server regression
tests. See the PR description for verification of the latest revision.

OpenAI Codex generated the implementation, tests and these notes. Personal human
review and product-owner confirmation of the expired-item policy remain pending.
