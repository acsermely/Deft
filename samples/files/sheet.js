// The file sheet: Open, Share, Rename, Copy, Delete for the file it was opened for. Rename and Copy ask with deft.prompt, Delete with confirm.
import { call } from '/_deft/v1/bridge.js';
import { confirm, prompt } from '/_deft/v1/app.js';

const $ = (id) => document.getElementById(id);
// ctx: { target(), dir(), reload(dir), err(message) }
export function wireSheet(ctx) {
  const dest = (name) => (ctx.dir() ? ctx.dir() + '/' + name : name);
  $('close').onclick = () => $('sheet').close();
  $('open').onclick = () => call('files.openWith', { path: ctx.target().path }).catch((e) => ctx.err(e.message));
  $('share').onclick = () => call('files.share', { path: ctx.target().path }).catch((e) => ctx.err(e.message));
  $('rename').onclick = async () => {
    const name = await prompt('New name', { value: ctx.target().name });
    if (!name) return;
    try {
      await call('files.rename', { from: ctx.target().path, to: dest(name) });
      $('sheet').close();
      await ctx.reload(ctx.dir());
    } catch (e) { ctx.err(e.message); }
  };
  $('copy').onclick = async () => {
    const name = await prompt('Copy as', { value: 'copy-' + ctx.target().name });
    if (!name) return;
    try {
      await call('files.copy', { from: ctx.target().path, to: dest(name) });
      $('sheet').close();
      await ctx.reload(ctx.dir());
    } catch (e) { ctx.err(e.message); }
  };
  $('del').onclick = async () => {
    if (!(await confirm('Delete ' + ctx.target().name + '?', { destructive: true, ok: 'Delete' }))) return;
    try {
      await call('files.delete', { path: ctx.target().path });
      $('sheet').close();
      await ctx.reload(ctx.dir());
    } catch (e) { ctx.err(e.message); }
  };
}
