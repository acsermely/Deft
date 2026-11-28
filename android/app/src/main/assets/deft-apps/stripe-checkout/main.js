// Stripe test checkout . Stripe forbids self-hosting Stripe.js, so the app lists its
// origins in manifest `embeds` and declares `web.embed`; the user approves each origin.
// The publishable key comes from ./config.json, which is not committed (copy config.example.json).
// Without a clientSecret the form only creates a test PaymentMethod; with the client secret of a
// test PaymentIntent it confirms the payment, including the 3-D Secure challenge.
import '/_deft/v1/motion.js';
import { readConfig, keyProblem, loadStripeJs } from './stripe.js';
const $ = (id) => document.getElementById(id);
const STATUS_ICON = { err: 'circle-alert', ok: 'circle-check' };

function status(text, kind) {
  const el = $('status');
  el.dataset.tone = kind === 'err' ? 'error' : kind === 'ok' ? 'success' : 'info';
  el.querySelector('deft-icon').setAttribute('name', STATUS_ICON[kind] || 'info');
  $('status-text').textContent = text;
}

async function start() {
  const config = await readConfig();
  const problem = keyProblem(config);
  if (problem) {
    status(problem, 'err');
    return;
  }
  let StripeCtor;
  try {
    StripeCtor = await loadStripeJs();
  } catch (e) {
    status(e.message, 'err');
    return;
  }
  const stripe = StripeCtor(config.publishableKey.trim());
  const clientSecret = typeof config.clientSecret === 'string' ? config.clientSecret.trim() : '';
  const elements = clientSecret
    ? stripe.elements({ clientSecret })
    : stripe.elements({ mode: 'payment', amount: 1099, currency: 'usd', paymentMethodTypes: ['card'] });
  const card = elements.create('payment');
  card.mount('#card');
  $('pay').hidden = false;
  status(clientSecret ? 'Enter a test card to pay.' : 'Enter a test card. Without a clientSecret in config.json this only checks the card.');

  $('pay').addEventListener('submit', async (event) => {
    event.preventDefault();
    $('submit').disabled = true;
    status('Working…');
    try {
      const submitted = await elements.submit();
      if (submitted.error) throw new Error(submitted.error.message);
      if (clientSecret) {
        const result = await stripe.confirmPayment({ elements, redirect: 'if_required' });
        if (result.error) throw new Error(result.error.message);
        status('Payment ' + result.paymentIntent.status + ' (' + result.paymentIntent.id + ').', 'ok');
      } else {
        const result = await stripe.createPaymentMethod({ elements });
        if (result.error) throw new Error(result.error.message);
        status('Card accepted: test payment method ' + result.paymentMethod.id + '.', 'ok');
      }
    } catch (e) {
      status(e.message || 'The payment failed.', 'err');
    } finally {
      $('submit').disabled = false;
    }
  });
}

start();
