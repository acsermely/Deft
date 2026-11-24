<script lang="ts">
	import PhoneMock from '$lib/components/PhoneMock.svelte';
	import { agentTools, capabilities, sampleUrl, samples, site, steps } from '$lib/content';
	import wordmark from '$lib/assets/deft-wordmark.svg?raw';
	import mark from '$lib/assets/deft-mark.svg';

	const install = [
		'Download the APK',
		'Let your browser install unknown apps',
		'Open the file and install',
		'Settings → Default apps → Home'
	];
</script>

<header class="sticky top-0 z-20 border-b border-line bg-base-100">
	<div class="mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-5">
		<a href="/" class="flex items-center text-bone" aria-label="{site.title} home">
			<img src={mark} alt="" class="size-10" />
		</a>
		<nav class="flex items-center gap-1">
			<a class="btn btn-ghost btn-sm hidden font-medium sm:inline-flex" href="#how">How it works</a>
			<a class="btn btn-ghost btn-sm hidden font-medium md:inline-flex" href="#install">Install</a>
			<a
				class="btn btn-sm btn-outline ml-2 border-line-strong font-medium"
				href={site.downloadHref}
				download="Deft.apk"
				rel="external"
			>
				Download
			</a>
		</nav>
	</div>
</header>

<main>
	<section class="relative overflow-hidden border-b border-line">
		<div
			class="dot-grid pointer-events-none absolute inset-y-0 right-0 w-full opacity-40 [mask-image:linear-gradient(to_left,black_25%,transparent_85%)] lg:w-3/4"
			aria-hidden="true"
		></div>
		<div
			class="relative mx-auto grid min-h-[calc(100dvh-4rem)] w-full max-w-6xl items-center gap-16 px-5 py-20 lg:grid-cols-[1.15fr_0.85fr]"
		>
			<div>
				<span class="block h-8 text-bone sm:h-9 [&>svg]:h-full [&>svg]:w-auto" role="img" aria-label={site.title}
					>{@html wordmark}</span
				>
				<h1 class="display mt-10 text-6xl leading-[0.95] sm:text-7xl lg:text-8xl">
					<span class="text-ink-muted">Say it.</span><br />
					<span class="dot-text">Shape it.</span><br />
					<span class="text-ember-text">Keep it.</span>
				</h1>
				<p class="mt-8 max-w-md text-lg leading-relaxed text-ink-muted">
					Deft is a home screen where every app is plain HTML. Describe what you want, shape it with the
					agent, and keep every file on your phone.
				</p>
				<div class="mt-10 flex flex-wrap items-center gap-3">
					<a
						class="btn btn-primary btn-lg font-semibold"
						href={site.downloadHref}
						download="Deft.apk"
						rel="external"
					>
						Download APK
					</a>
					<a class="btn btn-ghost btn-lg font-medium" href="#how">How it works</a>
				</div>
			</div>
			<div class="flex justify-center lg:justify-end">
				<PhoneMock />
			</div>
		</div>
	</section>

	<section id="how" class="mx-auto w-full max-w-6xl scroll-mt-16 px-5 py-24">
		<h2 class="display text-4xl sm:text-5xl">Your phone, as plain text.</h2>
		<ol class="mt-14 grid gap-px overflow-hidden rounded-box border border-line bg-line md:grid-cols-3">
			{#each steps as step, i}
				<li class="bg-base-100 p-8">
					<p class="font-dot text-5xl leading-none font-black text-ink-muted">0{i + 1}</p>
					<h3 class="display mt-8 text-2xl">{step.title}</h3>
					<p class="mt-3 leading-relaxed text-ink-muted">{step.body}</p>
				</li>
			{/each}
		</ol>
	</section>

	<section id="agent" class="mx-auto w-full max-w-6xl scroll-mt-16 px-5 py-24">
		<div class="grid gap-10 lg:grid-cols-[0.8fr_1.2fr]">
			<div>
				<h2 class="display text-4xl">Works inside your launcher.</h2>
				<p class="mt-4 max-w-sm leading-relaxed text-ink-muted">
					It builds, edits and restyles with these tools. New apps, settings and permissions wait for your Apply, and every file edit can be undone. Runs on your own OpenRouter key.
				</p>
			</div>
			<div class="grid gap-3 sm:grid-cols-2">
				{#each agentTools as group}
					<div class="rounded-box border border-line bg-base-200 p-5">
						<h3 class="font-semibold">{group.group}</h3>
						<ul class="mt-3 space-y-1 font-mono text-xs text-ink-muted">
							{#each group.items as item}
								<li>{item}</li>
							{/each}
						</ul>
					</div>
				{/each}
			</div>
		</div>
	</section>

	<section class="mx-auto w-full max-w-6xl px-5 py-24">
		<h2 class="display text-4xl">What your apps can reach.</h2>
		<p class="mt-4 max-w-md leading-relaxed text-ink-muted">
			Deft holds the phone roles. Each app asks before it uses any of them.
		</p>
		<div class="mt-12 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
			{#each capabilities as cap}
				<div class="rounded-box border border-line px-4 py-4">
					<p class="font-semibold">{cap.title}</p>
					<p class="mt-0.5 text-xs text-ink-muted">{cap.note}</p>
				</div>
			{/each}
		</div>
	</section>

	<section id="samples" class="mx-auto w-full max-w-6xl scroll-mt-16 px-5 py-24">
		<h2 class="display text-4xl">Start from one of these.</h2>
		<p class="mt-4 max-w-md leading-relaxed text-ink-muted">
			Every sample ships inside Deft. Look around here, then copy one to your apps and make it yours.
		</p>
		<div class="mt-12 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
			{#each samples as app}
				<a
					class="group rounded-box border border-line bg-base-200 p-5 transition-colors hover:border-line-strong"
					href={sampleUrl(app.id)}
				>
					<div class="flex items-center justify-between">
						<h3 class="text-lg font-semibold">{app.name}</h3>
						<span class="text-ink-muted transition-colors group-hover:text-bone" aria-hidden="true">↗</span>
					</div>
					<p class="mt-2 truncate font-mono text-xs text-ink-muted">{sampleUrl(app.id)}</p>
				</a>
			{/each}
		</div>
	</section>

	<section id="install" class="mx-auto w-full max-w-6xl scroll-mt-16 px-5 py-24">
		<h2 class="display text-4xl">Four steps, no store.</h2>
		<ol class="mt-12 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
			{#each install as text, i}
				<li class="rounded-box border border-line p-6">
					<p class="font-dot text-4xl leading-none font-black text-ink-muted">{i + 1}</p>
					<p class="mt-6 font-medium">{text}</p>
				</li>
			{/each}
		</ol>
		<div class="mt-10 flex flex-wrap items-center gap-4">
			<a class="btn btn-primary btn-lg font-semibold" href={site.downloadHref} download="Deft.apk" rel="external">
				Download APK
			</a>
			<p class="text-sm text-ink-muted">{site.android}. Not a ROM. No root.</p>
		</div>
	</section>
</main>

<footer class="mt-auto border-t border-line">
	<div class="mx-auto flex w-full max-w-6xl flex-col gap-4 px-5 py-10 sm:flex-row sm:items-center sm:justify-between">
		<span class="block h-5 text-bone [&>svg]:h-full [&>svg]:w-auto" aria-label={site.title}>{@html wordmark}</span>
		<p class="text-sm text-ink-muted">{site.tagline}</p>
	</div>
</footer>
