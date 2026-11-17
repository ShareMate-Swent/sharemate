<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Receipt confirmation (#370)

The coordinator uses the separate-write contract in `purchase-expense-submission.md`:
inventory first, then expense. Before enqueueing, the scoped journal retains reviewed
rows, payer, exact shares and one UUID shared by inventory and expense.
Inventory confirmation is persisted before the expense call; confirmed inventory is never replayed.
Unknown outcomes retry the same payload with 5–60 second backoff or after owner recreation.
Rejected expense attempts require explicit retry, even after restart. Neither cancellation
nor rejection proves an earlier attempt did not commit. Durable expense acknowledgement allows a new scan. Users can also explicitly set a rejected
expense aside: after all active journal writers finish, the store atomically archives the original
operation and releases the active slot. The scoped archive survives restarts and new scans. The
receipt screen offers recovery of a retained expense when there is no active draft;
recovery keeps its original ID and split and never repeats inventory. Rejection is not cancellation
and an earlier attempt may still have committed. Legacy journals remain inventory-only; private scopes cannot resume purchases.
Member loading never blocks inventory review. Submitted reviews show the immutable retained split.
Deploy `recordExpense` from #368 before release. The emulator scenario covers offline acceptance,
owner recreation, reconnect, second-member inventory/debt and settlement through the existing ledger.
