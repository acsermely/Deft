import { existsSync, createReadStream, statSync } from 'node:fs';
import { resolve } from 'node:path';
import adapter from '@sveltejs/adapter-cloudflare';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, type Plugin } from 'vite';

const apkCandidates = [
	resolve(import.meta.dirname, '../android/app/build/outputs/apk/release/app-release.apk'),
	resolve(import.meta.dirname, '../android/app/build/outputs/apk/debug/app-debug.apk')
];

function localApkDev(): Plugin {
	return {
		name: 'freedom-local-apk',
		configureServer(server) {
			server.middlewares.use((req, res, next) => {
				if (req.url?.split('?')[0] !== '/freedom-launcher.apk') return next();
				const file = apkCandidates.find((path) => existsSync(path));
				if (!file) {
					res.statusCode = 404;
					res.setHeader('content-type', 'text/plain; charset=utf-8');
					res.end('No APK on this machine yet. Build one with ./gradlew :app:assembleDebug');
					return;
				}
				res.statusCode = 200;
				res.setHeader('content-type', 'application/vnd.android.package-archive');
				res.setHeader('content-disposition', 'attachment; filename="Deft.apk"');
				res.setHeader('content-length', String(statSync(file).size));
				createReadStream(file).pipe(res);
			});
		}
	};
}

export default defineConfig({
	plugins: [
		localApkDev(),
		tailwindcss(),
		sveltekit({
			adapter: adapter({
				fallback: 'plaintext',
				routes: {
					include: ['/*'],
					exclude: ['<build>', '<files>', '<prerendered>']
				}
			}),
			prerender: {
				handleHttpError: ({ path, message }) => {
					if (path === '/freedom-launcher.apk') return;
					throw new Error(message);
				}
			},
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			}
		})
	]
});
