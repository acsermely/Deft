<script lang="ts">
	const turns = [
		{ kind: 'user', text: 'Make me a notes app with big type.' },
		{ kind: 'assistant', text: 'A notes app with large type. I’ll build it in a new tab.' },
		{ kind: 'tool', text: 'apps.create notes · needs Apply' }
	];
</script>

<div
	class="relative h-[620px] w-[300px] shrink-0 overflow-hidden rounded-[48px] border-[7px] border-base-200 bg-base-100 outline outline-1 outline-line"
	aria-hidden="true"
>
	<div class="absolute top-3 left-1/2 h-6 w-24 -translate-x-1/2 rounded-full bg-base-200"></div>
	<div class="h-full">
		<div class="flex h-full flex-col px-4 pt-11 pb-5">
			<div class="micro flex items-center justify-between text-ink-muted">
				<span>9:41</span>
				<span>Deft</span>
			</div>
			<p class="display mt-6 text-3xl leading-none">Agent</p>
			<p class="mt-2 text-xs text-ink-muted">Home tab</p>

			<div class="mt-6 flex min-h-0 flex-1 flex-col gap-2.5">
				{#each turns as turn}
					{#if turn.kind === 'user'}
						<div class="max-w-[85%] self-end rounded-[14px] bg-bone px-3 py-2 text-sm text-ink">
							{turn.text}
						</div>
					{:else if turn.kind === 'tool'}
						<div class="font-mono text-[11px] text-ink-muted">→ {turn.text}</div>
					{:else}
						<div class="max-w-[90%] rounded-[14px] border border-line bg-base-200 px-3 py-2 text-sm">
							{turn.text}
						</div>
					{/if}
				{/each}
			</div>

			<div class="mt-4 flex gap-2">
				<span class="btn btn-sm flex-1 border-line-strong bg-transparent font-medium">Apply</span>
				<span class="btn btn-ghost btn-sm font-medium text-ink-muted">Reject</span>
			</div>

			<div class="mt-3 flex items-center gap-2 rounded-full border border-line bg-base-200 py-1.5 pr-1.5 pl-4">
				<span class="flex-1 text-sm text-ink-muted">Say it…</span>
				<span class="grid size-8 place-items-center rounded-full bg-ember">
					<svg viewBox="0 0 22 22" class="size-8" aria-hidden="true">
						<path
							d="M8.68 5.99 L13.69 11 L8.68 16.01"
							fill="none"
							stroke="#14120F"
							stroke-width="3.42"
							stroke-linecap="round"
							stroke-linejoin="round"
						/>
					</svg>
				</span>
			</div>
		</div>
	</div>
</div>
