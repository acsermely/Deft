// Streams the APK from R2 (Pages static assets cap files at 25 MB). scripts/publish-apk.sh uploads it.
// If APK_DOWNLOAD_URL is set on the Pages project, redirect there instead.
import { error, redirect } from '@sveltejs/kit';
import type { RequestHandler } from './$types';

export const prerender = false;

const KEY = 'deft.apk';

export const GET: RequestHandler = async ({ platform }) => {
	const env = platform?.env;
	if (env?.APK_DOWNLOAD_URL) redirect(302, env.APK_DOWNLOAD_URL);
	if (!env?.APK) error(503, 'APK storage is not configured');

	const object = await env.APK.get(KEY);
	if (!object) error(404, 'APK not published yet');

	return new Response(object.body as unknown as ReadableStream, {
		headers: {
			'content-type': 'application/vnd.android.package-archive',
			'content-length': String(object.size),
			'content-disposition': 'attachment; filename="Deft.apk"',
			'cache-control': 'public, max-age=300',
			etag: object.httpEtag
		}
	});
};
