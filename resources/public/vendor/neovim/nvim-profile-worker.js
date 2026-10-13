// Install pinned plugins before the unmodified upstream worker starts Neovim.
// Emscripten preRun is the filesystem boundary; no plugin bytes travel over RPC.
let profile, workspace;
const upstreamImportScripts = self.importScripts.bind(self);
self.importScripts = (...paths) => {
  upstreamImportScripts(...paths);
  if (paths.includes('./nvim.js')) {
    const create = self.createNvim;
    self.createNvim = options => create({
      ...options,
      preRun: [...options.preRun, module => {
        const root = '/home/user/.config/nvim/wasm';
        for (const file of profile.files) {
          const path = root + '/' + file.path;
          module.FS.mkdirTree(path.slice(0, path.lastIndexOf('/')));
          module.FS.writeFile(path, file.text);
        }
        for (const file of workspace.files) {
          const path = '/workspace/tolgrAVen/' + file.path;
          module.FS.mkdirTree(path.slice(0, path.lastIndexOf('/')));
          module.FS.writeFile(path, file.text);
        }
      }],
    });
    self.importScripts = upstreamImportScripts;
  }
};
upstreamImportScripts('./nvim-worker.js');
const upstreamMessage = self.onmessage;
self.onmessage = async event => {
  if (event.data.type === 'init') {
    try {
      self.postMessage({ type: 'status', text: 'Loading bundled Bruvbox and Metabuffer…' });
      const load = async path => {
        const response = await fetch(path);
        if (!response.ok) throw new Error('Could not load bundled Neovim files');
        return response.json();
      };
      [profile, workspace] = await Promise.all([load('./profile.json'), load('./workspace.json')]);
      if (profile.version !== 1 || !Array.isArray(profile.files) || !profile.files.length)
        throw new Error('Unsupported Neovim profile');
      if (workspace.version !== 1 || workspace.root !== 'tolgrAVen' || !Array.isArray(workspace.files))
        throw new Error('Unsupported Neovim workspace');
      for (const file of [...profile.files, ...workspace.files]) {
        if (typeof file.path !== 'string' || typeof file.text !== 'string' ||
            !file.path || file.path.startsWith('/') || file.path.includes('\\') ||
            file.path.includes('\0') || file.path.split('/').some(part => !part || part === '.' || part === '..'))
          throw new Error('Invalid Neovim profile file');
      }
      return await upstreamMessage(event);
    } catch (error) {
      self.postMessage({ type: 'profile-error', text: error.message });
      return;
    }
  }
  return upstreamMessage(event);
};
