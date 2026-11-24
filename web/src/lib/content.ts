export const site = {
	title: 'Deft',
	tagline: 'Say it. Shape it. Keep it.',
	description:
		'Deft is an Android home screen where every app is plain HTML. Say what you want, shape it with the agent, keep every file. Sideload, not on Play.',
	version: '0.1.0',
	android: 'Android 11+',
	downloadHref: '/freedom-launcher.apk',
	samplesHost: 'app.acsermely.hu'
};

export const steps = [
	{ title: 'Say it.', body: 'Tell the agent what you want: a notes app, a quieter clock, a new home screen.' },
	{ title: 'Shape it.', body: 'It writes ordinary HTML and runs it in a live preview. Keep talking until it feels right.' },
	{ title: 'Keep it.', body: 'Your apps live on your phone as plain files. Export them as signed zips, take them anywhere.' }
];

export const capabilities = [
	{ title: 'SMS', note: 'As default Messages' },
	{ title: 'Calls', note: 'As default Phone' },
	{ title: 'Contacts', note: 'Address book' },
	{ title: 'Files', note: 'Folders you pick' },
	{ title: 'Calendar', note: 'Device events' },
	{ title: 'Alarms', note: 'Fire when closed' },
	{ title: 'Camera & mic', note: 'getUserMedia' },
	{ title: 'Location', note: 'geolocation' },
	{ title: 'NFC / BT / USB', note: 'Read, connect, open' },
	{ title: 'Notify', note: 'Native, not FCM' }
];

export const agentTools = [
	{ group: 'Your apps', items: ['apps.create', 'apps.preview', 'apps.verify', 'files.write', 'files.edit', 'history.undo'] },
	{ group: 'Home & look', items: ['canvas.write', 'canvas.edit', 'theme.set', 'config.write'] },
	{ group: 'Installed apps', items: ['webapps.list', 'webapps.grants', 'webapps.uninstall'] },
	{ group: 'Device', items: ['alarms.schedule', 'notify.post', 'intents.open', 'system.androidSettings'] },
	{ group: 'Harness', items: ['skill.read', 'sessions.open', 'web.fetch', 'question'] }
];

export const samples = [
	{ id: 'three-tps', name: 'Fireball Duel' },
	{ id: 'calendar', name: 'Calendar' },
	{ id: 'sms', name: 'Messages' },
	{ id: 'contacts', name: 'Contacts' }
];

export function sampleUrl(id: string): string {
	return `https://${id}.${site.samplesHost}/`;
}
