<script lang="ts">
	import '../app.css';
	import favicon from '$lib/assets/favicon.svg';
	import { site } from '$lib/content';

	let { children } = $props();
</script>

<svelte:head>
	<title>{site.title} · {site.tagline}</title>
	<meta name="description" content={site.description} />
	<meta property="og:title" content={site.title} />
	<meta property="og:description" content={site.description} />
	<meta property="og:type" content="website" />
	<meta name="twitter:card" content="summary_large_image" />
	<link rel="icon" href={favicon} type="image/svg+xml" />
</svelte:head>

<div class="flex min-h-dvh flex-col">
	{@render children()}
</div>
