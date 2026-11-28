import { describe, expect, test } from "bun:test";
import { adoptFragmentEntry, backAction, threadFromHash } from "../../samples/sms/nav.js";

// The host (WebViewHostActivity) steps WebView history first and asks the page's back() handler only when
// there is none; a false answer lets the activity leave. These cover the page's side of that contract.
describe("sms back", () => {
  test("at the list the page declines, so the host leaves the app", () => {
    expect(backAction({ sheetOpen: false, chatShown: false, view: "inbox" })).toBe(false);
    expect(backAction({ sheetOpen: false, chatShown: false, view: undefined })).toBe(false);
  });

  test("sheet and thread are handled by the page", () => {
    expect(backAction({ sheetOpen: true, chatShown: false, view: "compose" })).toBe("pop");
    expect(backAction({ sheetOpen: true, chatShown: false, view: "inbox" })).toBe("close");
    expect(backAction({ sheetOpen: false, chatShown: true, view: "chat" })).toBe("pop");
    expect(backAction({ sheetOpen: false, chatShown: true, view: "inbox" })).toBe("inbox");
  });
});

function fakeHistory(state) {
  const calls = [];
  return {
    state,
    calls,
    replaceState(next, _title, url) {
      this.state = next;
      calls.push([next, url]);
    },
  };
}

describe("sms fragment entry", () => {
  test("a notification URL loaded into the open page becomes the thread entry", () => {
    const history = fakeHistory(null);
    const location = { hash: "#t/42%2F7", href: "https://sms.app.deft.internal/index.html#t/42%2F7" };
    expect(adoptFragmentEntry(history, location)).toBe("42/7");
    expect(history.state).toEqual({ view: "chat", threadId: "42/7" });
    expect(history.calls[0][1]).toBe(location.href);
  });

  test("entries the page pushed itself are left alone", () => {
    const history = fakeHistory({ view: "inbox" });
    expect(adoptFragmentEntry(history, { hash: "#t/1", href: "x#t/1" })).toBe("");
    expect(history.calls).toHaveLength(0);
  });

  test("other hashes and bad escapes do nothing", () => {
    const history = fakeHistory(null);
    expect(adoptFragmentEntry(history, { hash: "#new", href: "x#new" })).toBe("");
    expect(adoptFragmentEntry(history, { hash: "#t/%E0%A4%A", href: "x" })).toBe("");
    expect(history.calls).toHaveLength(0);
    expect(threadFromHash("")).toBe("");
    expect(threadFromHash("#t/9")).toBe("9");
  });
});
