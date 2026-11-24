// W5: the agent tool page's one script. The launcher calls __deftToolRun(id, module, fn, params) and reads the answer
// from the deftTool message port: {id, ok, json} or {id, ok:false, error}. Not part of app code: it only imports the
// app's own handler module (same origin) and returns JSON. `params` arrives as JSON text.
const MAX_JSON = 1024 * 1024;
const post = (o) => self.deftTool.postMessage(JSON.stringify(o));
self.__deftToolRun = async (id, mod, fn, params) => {
  try {
    const f = (await import(mod))[fn];
    if (typeof f !== "function") throw new Error(`${fn} is not exported by ${mod}`);
    const v = await f(JSON.parse(params));
    const json = JSON.stringify(v === undefined ? null : v);
    if (json !== undefined && json.length > MAX_JSON) throw new Error("result larger than 1 MB");
    post({ id, ok: true, json: json === undefined ? "null" : json });
  } catch (e) {
    post({ id, ok: false, error: String((e && e.message) || e).slice(0, 300) });
  }
};
post({ ready: true });
