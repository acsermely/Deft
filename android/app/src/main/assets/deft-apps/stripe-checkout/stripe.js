// stripe.js: reads the test key from config.json and loads Stripe.js from the approved origin.
// Stripe forbids self-hosting Stripe.js; the origin is listed in the manifest `embeds`.

export const STRIPE_JS = 'https://js.stripe.com/v3/';
export const PLACEHOLDER = 'pk_test_REPLACE_ME';

export async function readConfig() {
  let res;
  try {
    res = await fetch('./config.json', { cache: 'no-store' });
  } catch (e) {
    return null;
  }
  if (!res.ok) return null;
  try {
    return await res.json();
  } catch (e) {
    return null;
  }
}

export function keyProblem(config) {
  const key = config && typeof config.publishableKey === 'string' ? config.publishableKey.trim() : '';
  if (!config) return 'No Stripe key yet. Copy config.example.json to config.json and put your Stripe test publishable key (pk_test_…) in it.';
  if (!key || key === PLACEHOLDER) return 'No Stripe key yet. Put your Stripe test publishable key (pk_test_…) in config.json instead of ' + PLACEHOLDER + '.';
  if (!/^pk_test_[A-Za-z0-9]+$/.test(key)) return 'This sample only runs in test mode. Use a publishable key that starts with pk_test_.';
  return null;
}

// Trusted Types: the host's default policy lets script URLs through only for approved embed origins.
export function loadStripeJs() {
  if (window.Stripe) return Promise.resolve(window.Stripe);
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = STRIPE_JS;
    s.onload = () => (window.Stripe ? resolve(window.Stripe) : reject(new Error('Stripe.js did not start')));
    s.onerror = () => reject(new Error('Stripe.js could not load. Check that js.stripe.com is allowed in Settings and that you are online.'));
    document.head.append(s);
  });
}

