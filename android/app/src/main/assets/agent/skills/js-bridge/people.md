# JS bridge: contacts, SMS, calls

<!-- generated:methods -->
- `contacts.read` (high): Read the address book. `contacts.list`, `contacts.search`, `contacts.get`; events `contacts.changed`
- `contacts.write` (high): Change the address book. `contacts.create`, `contacts.update`, `contacts.delete`
- `sms.read` (high): Read SMS. `sms.listThreads`, `sms.listMessages`; events `sms.received`, `sms.mmsReceived`, `sms.composeRequested`
- `sms.write` (high): Change SMS. `sms.markRead`, `sms.delete`
- `sms.send` (high): Send SMS. `sms.send`, `sms.sendMms`
- `calls.place` (high): Place a call, choosing the SIM. `telephony.call`, `telephony.listSims`
- `calls.block` (high): Read and change blocked numbers. `telephony.blockedList`, `telephony.block`, `telephony.unblock`
- `calls.control` (high): Control a call. `telephony.answer`, `telephony.reject`, `telephony.end`, `telephony.hold`, `telephony.dtmf`; events `telephony.incoming`, `telephony.state`, `telephony.ended`
- `telephony.ussd` (high): Send carrier codes such as *100#, after you confirm each one. `telephony.ussd`
- `calllog.read` (high): Read the call log. `telephony.listCalls`
- `calllog.write` (high): Change the call log. `telephony.deleteCall`
<!-- /generated -->

## Arguments and behaviour

Use `call(method, args)` from `/_deft/v1/bridge.js`. Basics, errors and events: `skill.read name=js-bridge/basics`.

- contacts: `list`/`search` `{query?, limit?}`, `get` `{id}`, `create` `{name, phone?, email?, org?}`, `update` `{id, name?, phone?, email?, org?}`, `delete` `{id}`
- sms: `listThreads` `{limit?, offset?}` (default 100), `listMessages` `{threadId?, limit?, offset?}` (default 50), both newest first; `limit` 1 to 50 000, `offset` 0 to 50 000; each returns rows as `[{...}]`. A read above 500 rows asks the user first ("Read up to N messages at once"), and a no gives `cancelled`; paging counts too: once an app has read 500 rows within 10 minutes, the next page asks once for that stretch (a background run gets `needs_foreground` instead). For `listMessages`, `offset + limit` must be at most 10 000, else `invalid_args`. The audit log keeps the number of rows returned, not the content. `send` `{to, body, subscriptionId?}`, `sendMms` `{to, body?, base64?, mime?}`, `markRead`, `delete`
- telephony: `call` `{e164, subscriptionId?}`, `listSims` → `[{subscriptionId, displayName, carrierName, slot}]` (display and carrier names and slot only; never the phone number, IMSI or ICCID; needs READ_PHONE_STATE, else `os_permission_missing`), `answer`, `reject`, `end`, `hold`, `dtmf` `{digit}` (one of `0`-`9`, `*`, `#`, sent to the active call), `listCalls` `{limit?, offset?}` (default 50; `limit` 1 to 50 000, `offset` 0 to 50 000; above 500 rows asks the user first, a no gives `cancelled`; pages past 500 rows within 10 minutes ask once, as for `sms`), `deleteCall`, `blockedList` → `[{number}]` (needs the dialer role), `block` `{e164}`, `unblock` `{e164}` (same number text as `blockedList`), `ussd` `{code, subscriptionId?}` (`code` like `*100#` or `*123*1#`; codes that change call settings — starting with `**`, `##` or `*#`, or a forwarding, barring, waiting, caller id or PIN code such as `*21*…#` or `#31#` — give `invalid_args`; confirmed every time; → `{response}` the carrier's reply text, or `unsupported` if the carrier does not answer within 30 seconds)
