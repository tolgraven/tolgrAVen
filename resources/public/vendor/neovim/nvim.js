// This code implements the `-sMODULARIZE` settings by taking the generated
// JS program code (INNER_JS_CODE) and wrapping it in a factory function.

// Single threaded MINIMAL_RUNTIME programs do not need access to
// document.currentScript, so a simple export declaration is enough.
var createNvim = (() => {
  // When MODULARIZE this JS may be executed later,
  // after document.currentScript is gone, so we save it.
  // In EXPORT_ES6 mode we can just use 'import.meta.url'.
  var _scriptName = globalThis.document?.currentScript?.src;
  return async function(moduleArg = {}) {
    var Module = moduleArg;
// include: shell.js
// include: minimum_runtime_check.js
(function() {
  // "30.0.0" -> 300000
  function humanReadableVersionToPacked(str) {
    str = str.split('-')[0]; // Remove any trailing part from e.g. "12.53.3-alpha"
    var vers = str.split('.').slice(0, 3);
    while(vers.length < 3) vers.push('00');
    vers = vers.map((n, i, arr) => n.padStart(2, '0'));
    return vers.join('');
  }
  // 300000 -> "30.0.0"
  var packedVersionToHumanReadable = n => [n / 10000 | 0, (n / 100 | 0) % 100, n % 100].join('.');

  var TARGET_NOT_SUPPORTED = 2147483647;

  // Note: We use a typeof check here instead of optional chaining using
  // globalThis because older browsers might not have globalThis defined.
  var currentNodeVersion = typeof process !== 'undefined' && process.versions?.node ? humanReadableVersionToPacked(process.versions.node) : TARGET_NOT_SUPPORTED;
  if (currentNodeVersion < 180300) {
    throw new Error(`This emscripten-generated code requires node v${ packedVersionToHumanReadable(180300) } (detected v${packedVersionToHumanReadable(currentNodeVersion)})`);
  }

  var userAgent = typeof navigator !== 'undefined' && navigator.userAgent;
  if (!userAgent) {
    return;
  }

  var currentSafariVersion = userAgent.includes("Safari/") && !userAgent.includes("Chrome/") && userAgent.match(/Version\/(\d+\.?\d*\.?\d*)/) ? humanReadableVersionToPacked(userAgent.match(/Version\/(\d+\.?\d*\.?\d*)/)[1]) : TARGET_NOT_SUPPORTED;
  if (currentSafariVersion < 150000) {
    throw new Error(`This emscripten-generated code requires Safari v${ packedVersionToHumanReadable(150000) } (detected v${currentSafariVersion})`);
  }

  var currentFirefoxVersion = userAgent.match(/Firefox\/(\d+(?:\.\d+)?)/) ? parseFloat(userAgent.match(/Firefox\/(\d+(?:\.\d+)?)/)[1]) : TARGET_NOT_SUPPORTED;
  if (currentFirefoxVersion < 79) {
    throw new Error(`This emscripten-generated code requires Firefox v79 (detected v${currentFirefoxVersion})`);
  }

  var currentChromeVersion = userAgent.match(/Chrome\/(\d+(?:\.\d+)?)/) ? parseFloat(userAgent.match(/Chrome\/(\d+(?:\.\d+)?)/)[1]) : TARGET_NOT_SUPPORTED;
  if (currentChromeVersion < 85) {
    throw new Error(`This emscripten-generated code requires Chrome v85 (detected v${currentChromeVersion})`);
  }
})();

// end include: minimum_runtime_check.js
// The Module object: Our interface to the outside world. We import
// and export values on it. There are various ways Module can be used:
// 1. Not defined. We create it here
// 2. A function parameter, function(moduleArg) => Promise<Module>
// 3. pre-run appended it, var Module = {}; ..generated code..
// 4. External script tag defines var Module.
// We need to check if Module already exists (e.g. case 3 above).
// Substitution will be replaced with actual code on later stage of the build,
// this way Closure Compiler will not mangle it (e.g. case 4. above).
// Note that if you want to run closure, and also to use Module
// after the generated code, you will need to define   var Module = {};
// before the code. Then that object will be used in the code, and you
// can continue to use Module afterwards as well.

// Determine the runtime environment we are in. You can customize this by
// setting the ENVIRONMENT setting at compile time (see settings.js).

// Attempt to auto-detect the environment
var ENVIRONMENT_IS_WEB = !!globalThis.window;
var ENVIRONMENT_IS_WORKER = !!globalThis.WorkerGlobalScope;
// N.b. Electron.js environment is simultaneously a NODE-environment, but
// also a web environment.
var ENVIRONMENT_IS_NODE = globalThis.process?.versions?.node && globalThis.process?.type != 'renderer';
var ENVIRONMENT_IS_SHELL = !ENVIRONMENT_IS_WEB && !ENVIRONMENT_IS_NODE && !ENVIRONMENT_IS_WORKER;

// Three configurations we can be running in:
// 1) We could be the application main() thread running in the main JS UI thread. (ENVIRONMENT_IS_WORKER == false and ENVIRONMENT_IS_PTHREAD == false)
// 2) We could be the application main() running directly in a worker. (ENVIRONMENT_IS_WORKER == true, ENVIRONMENT_IS_PTHREAD == false)
// 3) We could be an application pthread running in a worker. (ENVIRONMENT_IS_WORKER == true and ENVIRONMENT_IS_PTHREAD == true)

// The way we signal to a worker that it is hosting a pthread is to construct
// it with a specific name.
var ENVIRONMENT_IS_PTHREAD = ENVIRONMENT_IS_WORKER && globalThis.name?.startsWith('em-pthread')

if (ENVIRONMENT_IS_PTHREAD) {
  assert(!globalThis.moduleLoaded, 'module should only be loaded once on each pthread worker');
  globalThis.moduleLoaded = true;
}

if (ENVIRONMENT_IS_NODE) {

  var worker_threads = require('node:worker_threads');
  globalThis.Worker = worker_threads.Worker;
  ENVIRONMENT_IS_WORKER = !worker_threads.isMainThread;
  // Under node we set `workerData` to `em-pthread` to signal that the worker
  // is hosting a pthread.
  ENVIRONMENT_IS_PTHREAD = ENVIRONMENT_IS_WORKER && worker_threads.workerData == 'em-pthread'
}

// --pre-jses are emitted after the Module integration code, so that they can
// refer to Module (if they choose; they can also define Module)
// include: /tmp/tmpwyme55lu.js

  if (!Module['expectedDataFileDownloads']) Module['expectedDataFileDownloads'] = 0;
  Module['expectedDataFileDownloads']++;
  (() => {
    // Do not attempt to redownload the virtual filesystem data when in a pthread or a Wasm Worker context.
    var isPthread = typeof ENVIRONMENT_IS_PTHREAD != 'undefined' && ENVIRONMENT_IS_PTHREAD;
    var isWasmWorker = typeof ENVIRONMENT_IS_WASM_WORKER != 'undefined' && ENVIRONMENT_IS_WASM_WORKER;
    if (isPthread || isWasmWorker) return;
    var isNode = globalThis.process && globalThis.process.versions && globalThis.process.versions.node && globalThis.process.type != 'renderer';
    async function loadPackage(metadata) {

      var PACKAGE_PATH = '';
      if (typeof window === 'object') {
        PACKAGE_PATH = window['encodeURIComponent'](window.location.pathname.substring(0, window.location.pathname.lastIndexOf('/')) + '/');
      } else if (typeof process === 'undefined' && typeof location !== 'undefined') {
        // web worker
        PACKAGE_PATH = encodeURIComponent(location.pathname.substring(0, location.pathname.lastIndexOf('/')) + '/');
      }
      var PACKAGE_NAME = '/home/runner/work/neovim/neovim/.zig-cache/o/3c5c516037009d2af6b07777b9f9bcaa/nvim.data';
      var REMOTE_PACKAGE_BASE = 'nvim.data';
      var REMOTE_PACKAGE_NAME = Module['locateFile'] ? Module['locateFile'](REMOTE_PACKAGE_BASE, '') : REMOTE_PACKAGE_BASE;
      var REMOTE_PACKAGE_SIZE = metadata['remote_package_size'];

      async function fetchRemotePackage(packageName, packageSize) {
        if (isNode) {
          var contents = require('fs').readFileSync(packageName);
          return new Uint8Array(contents).buffer;
        }
        if (!Module['dataFileDownloads']) Module['dataFileDownloads'] = {};
        try {
          var response = await fetch(packageName);
        } catch (e) {
          throw new Error(`Network Error: ${packageName}`, {e});
        }
        if (!response.ok) {
          throw new Error(`${response.status}: ${response.url}`);
        }

        const chunks = [];
        const headers = response.headers;
        const total = Number(headers.get('Content-Length') || packageSize);
        let loaded = 0;

        Module['setStatus'] && Module['setStatus']('Downloading data...');
        const reader = response.body.getReader();

        while (1) {
          var {done, value} = await reader.read();
          if (done) break;
          chunks.push(value);
          loaded += value.length;
          Module['dataFileDownloads'][packageName] = {loaded, total};

          let totalLoaded = 0;
          let totalSize = 0;

          for (const download of Object.values(Module['dataFileDownloads'])) {
            totalLoaded += download.loaded;
            totalSize += download.total;
          }

          Module['setStatus'] && Module['setStatus'](`Downloading data... (${totalLoaded}/${totalSize})`);
        }

        const packageData = new Uint8Array(chunks.map((c) => c.length).reduce((a, b) => a + b, 0));
        let offset = 0;
        for (const chunk of chunks) {
          packageData.set(chunk, offset);
          offset += chunk.length;
        }
        return packageData.buffer;
      }

      var fetchPromise;
      var fetched = Module['getPreloadedPackage'] && Module['getPreloadedPackage'](REMOTE_PACKAGE_NAME, REMOTE_PACKAGE_SIZE);

      if (!fetched) {
        // Note that we don't use await here because we want to execute the
        // the rest of this function immediately.
        fetchPromise = fetchRemotePackage(REMOTE_PACKAGE_NAME, REMOTE_PACKAGE_SIZE);
      }

    async function runWithFS(Module) {

      function assert(check, msg) {
        if (!check) throw new Error(msg);
      }
Module['FS_createPath']("/", "runtime", true, true);
Module['FS_createPath']("/runtime", "autoload", true, true);
Module['FS_createPath']("/runtime/autoload", "cargo", true, true);
Module['FS_createPath']("/runtime/autoload", "dist", true, true);
Module['FS_createPath']("/runtime/autoload", "provider", true, true);
Module['FS_createPath']("/runtime/autoload", "remote", true, true);
Module['FS_createPath']("/runtime/autoload", "rust", true, true);
Module['FS_createPath']("/runtime/autoload", "xml", true, true);
Module['FS_createPath']("/runtime", "colors", true, true);
Module['FS_createPath']("/runtime", "compiler", true, true);
Module['FS_createPath']("/runtime", "doc", true, true);
Module['FS_createPath']("/runtime", "ftplugin", true, true);
Module['FS_createPath']("/runtime", "indent", true, true);
Module['FS_createPath']("/runtime", "keymap", true, true);
Module['FS_createPath']("/runtime", "lua", true, true);
Module['FS_createPath']("/runtime/lua", "nvim", true, true);
Module['FS_createPath']("/runtime/lua/nvim", "dir", true, true);
Module['FS_createPath']("/runtime/lua/nvim", "zip", true, true);
Module['FS_createPath']("/runtime/lua", "uv", true, true);
Module['FS_createPath']("/runtime/lua", "vim", true, true);
Module['FS_createPath']("/runtime/lua/vim", "_core", true, true);
Module['FS_createPath']("/runtime/lua/vim/_core", "ui2", true, true);
Module['FS_createPath']("/runtime/lua/vim", "_ftplugin", true, true);
Module['FS_createPath']("/runtime/lua/vim", "_meta", true, true);
Module['FS_createPath']("/runtime/lua/vim", "async", true, true);
Module['FS_createPath']("/runtime/lua/vim", "deprecated", true, true);
Module['FS_createPath']("/runtime/lua/vim", "diagnostic", true, true);
Module['FS_createPath']("/runtime/lua/vim", "filetype", true, true);
Module['FS_createPath']("/runtime/lua/vim", "func", true, true);
Module['FS_createPath']("/runtime/lua/vim", "health", true, true);
Module['FS_createPath']("/runtime/lua/vim", "lsp", true, true);
Module['FS_createPath']("/runtime/lua/vim/lsp", "_meta", true, true);
Module['FS_createPath']("/runtime/lua/vim", "net", true, true);
Module['FS_createPath']("/runtime/lua/vim", "pack", true, true);
Module['FS_createPath']("/runtime/lua/vim", "pos", true, true);
Module['FS_createPath']("/runtime/lua/vim", "provider", true, true);
Module['FS_createPath']("/runtime/lua/vim", "treesitter", true, true);
Module['FS_createPath']("/runtime/lua/vim/treesitter", "_meta", true, true);
Module['FS_createPath']("/runtime/lua/vim", "ui", true, true);
Module['FS_createPath']("/runtime/lua/vim/ui", "clipboard", true, true);
Module['FS_createPath']("/runtime/lua/vim/ui", "img", true, true);
Module['FS_createPath']("/runtime", "pack", true, true);
Module['FS_createPath']("/runtime/pack", "dist", true, true);
Module['FS_createPath']("/runtime/pack/dist", "opt", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "cfilter", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/cfilter", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "justify", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/justify", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "matchit", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/matchit", "autoload", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/matchit", "doc", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/matchit", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "netrw", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/netrw", "autoload", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/netrw/autoload", "netrw", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/netrw", "doc", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/netrw", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/netrw", "syntax", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "nohlsearch", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/nohlsearch", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "nvim.difftool", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/nvim.difftool", "lua", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/nvim.difftool", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "nvim.tohtml", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/nvim.tohtml", "lua", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/nvim.tohtml", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "nvim.undotree", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/nvim.undotree", "lua", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/nvim.undotree", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "old-zip", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/old-zip", "autoload", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/old-zip", "doc", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/old-zip", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "swapmouse", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/swapmouse", "plugin", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt", "termdebug", true, true);
Module['FS_createPath']("/runtime/pack/dist/opt/termdebug", "plugin", true, true);
Module['FS_createPath']("/runtime", "plugin", true, true);
Module['FS_createPath']("/runtime", "queries", true, true);
Module['FS_createPath']("/runtime/queries", "c", true, true);
Module['FS_createPath']("/runtime/queries", "diff", true, true);
Module['FS_createPath']("/runtime/queries", "lua", true, true);
Module['FS_createPath']("/runtime/queries", "markdown", true, true);
Module['FS_createPath']("/runtime/queries", "markdown_inline", true, true);
Module['FS_createPath']("/runtime/queries", "query", true, true);
Module['FS_createPath']("/runtime/queries", "vim", true, true);
Module['FS_createPath']("/runtime/queries", "vimdoc", true, true);
Module['FS_createPath']("/runtime", "scripts", true, true);
Module['FS_createPath']("/runtime", "spell", true, true);
Module['FS_createPath']("/runtime", "syntax", true, true);
Module['FS_createPath']("/runtime/syntax", "modula2", true, true);
Module['FS_createPath']("/runtime/syntax/modula2", "opt", true, true);
Module['FS_createPath']("/runtime/syntax", "shared", true, true);
Module['FS_createPath']("/runtime/syntax", "vim", true, true);
Module['FS_createPath']("/runtime", "tutor", true, true);
Module['FS_createPath']("/runtime/tutor", "en", true, true);
Module['FS_createPath']("/runtime/tutor", "ja", true, true);
Module['FS_createPath']("/runtime/tutor", "zh", true, true);

      async function processPackageData(arrayBuffer) {
        assert(arrayBuffer, 'Loading data file failed.');
        assert(arrayBuffer.constructor.name === ArrayBuffer.name, 'bad input to processPackageData ' + arrayBuffer.constructor.name);
        var byteArray = new Uint8Array(arrayBuffer);
        var curr;
        // Reuse the bytearray from the XHR as the source for file reads.
          for (var file of metadata['files']) {
            var name = file['filename'];
            var data = byteArray.subarray(file['start'], file['end']);
            // canOwn this data in the filesystem, it is a slice into the heap that will never change
        Module['FS_createDataFile'](name, null, data, true, true, true);
          }
          Module['removeRunDependency']('datafile_/home/runner/work/neovim/neovim/.zig-cache/o/3c5c516037009d2af6b07777b9f9bcaa/nvim.data');
      }
      Module['addRunDependency']('datafile_/home/runner/work/neovim/neovim/.zig-cache/o/3c5c516037009d2af6b07777b9f9bcaa/nvim.data');

      if (!Module['preloadResults']) Module['preloadResults'] = {};

      Module['preloadResults'][PACKAGE_NAME] = {fromCache: false};
      if (!fetched) {
        fetched = await fetchPromise;
      }
      await processPackageData(fetched);

    }
    // Detect whether the module JS file has already been loaded.
    if (Module['FS_createPath']) {
      runWithFS(Module);
    } else {
      if (!Module['preRun']) Module['preRun'] = [];
      Module['preRun'].push(runWithFS); // FS is not initialized yet, wait for it
    }

    }
    loadPackage({"files": [{"filename": "/runtime/CMakeLists.txt", "start": 0, "end": 4819}, {"filename": "/runtime/autoload/RstFold.vim", "start": 4819, "end": 6719}, {"filename": "/runtime/autoload/ada.vim", "start": 6719, "end": 29366}, {"filename": "/runtime/autoload/adacomplete.vim", "start": 29366, "end": 33036}, {"filename": "/runtime/autoload/beancount.vim", "start": 33036, "end": 35707}, {"filename": "/runtime/autoload/beancountcomplete.vim", "start": 35707, "end": 43079}, {"filename": "/runtime/autoload/bitbake.vim", "start": 43079, "end": 46176}, {"filename": "/runtime/autoload/cargo.vim", "start": 46176, "end": 50235}, {"filename": "/runtime/autoload/cargo/quickfix.vim", "start": 50235, "end": 51354}, {"filename": "/runtime/autoload/ccomplete.vim", "start": 51354, "end": 71101}, {"filename": "/runtime/autoload/clojurecomplete.vim", "start": 71101, "end": 79972}, {"filename": "/runtime/autoload/context.vim", "start": 79972, "end": 85437}, {"filename": "/runtime/autoload/contextcomplete.vim", "start": 85437, "end": 86093}, {"filename": "/runtime/autoload/csscomplete.vim", "start": 86093, "end": 129345}, {"filename": "/runtime/autoload/decada.vim", "start": 129345, "end": 132343}, {"filename": "/runtime/autoload/dist/vim.vim", "start": 132343, "end": 133309}, {"filename": "/runtime/autoload/freebasic.vim", "start": 133309, "end": 134349}, {"filename": "/runtime/autoload/gnat.vim", "start": 134349, "end": 139679}, {"filename": "/runtime/autoload/gzip.vim", "start": 139679, "end": 146456}, {"filename": "/runtime/autoload/hare.vim", "start": 146456, "end": 147238}, {"filename": "/runtime/autoload/haskellcomplete.vim", "start": 147238, "end": 253004}, {"filename": "/runtime/autoload/hcl.vim", "start": 253004, "end": 254902}, {"filename": "/runtime/autoload/htmlcomplete.vim", "start": 254902, "end": 280384}, {"filename": "/runtime/autoload/htmlfold.vim", "start": 280384, "end": 284969}, {"filename": "/runtime/autoload/javaformat.vim", "start": 284969, "end": 287278}, {"filename": "/runtime/autoload/javascriptcomplete.vim", "start": 287278, "end": 315502}, {"filename": "/runtime/autoload/modula2.vim", "start": 315502, "end": 316193}, {"filename": "/runtime/autoload/msgpack.vim", "start": 316193, "end": 342515}, {"filename": "/runtime/autoload/paste.vim", "start": 342515, "end": 343238}, {"filename": "/runtime/autoload/phpcomplete.vim", "start": 343238, "end": 697362}, {"filename": "/runtime/autoload/provider.vim", "start": 697362, "end": 698003}, {"filename": "/runtime/autoload/provider/clipboard.vim", "start": 698003, "end": 709655}, {"filename": "/runtime/autoload/provider/node.vim", "start": 709655, "end": 714756}, {"filename": "/runtime/autoload/provider/perl.vim", "start": 714756, "end": 715183}, {"filename": "/runtime/autoload/provider/python3.vim", "start": 715183, "end": 715640}, {"filename": "/runtime/autoload/provider/ruby.vim", "start": 715640, "end": 716134}, {"filename": "/runtime/autoload/provider/script_host.rb", "start": 716134, "end": 716316}, {"filename": "/runtime/autoload/python.vim", "start": 716316, "end": 724297}, {"filename": "/runtime/autoload/python3complete.vim", "start": 724297, "end": 748707}, {"filename": "/runtime/autoload/pythoncomplete.vim", "start": 748707, "end": 773576}, {"filename": "/runtime/autoload/remote/define.vim", "start": 773576, "end": 781949}, {"filename": "/runtime/autoload/remote/host.vim", "start": 781949, "end": 788962}, {"filename": "/runtime/autoload/rubycomplete.vim", "start": 788962, "end": 814997}, {"filename": "/runtime/autoload/rust.vim", "start": 814997, "end": 832047}, {"filename": "/runtime/autoload/rust/debugging.vim", "start": 832047, "end": 835250}, {"filename": "/runtime/autoload/rustfmt.vim", "start": 835250, "end": 843844}, {"filename": "/runtime/autoload/shada.vim", "start": 843844, "end": 867408}, {"filename": "/runtime/autoload/spotbugs.vim", "start": 867408, "end": 878189}, {"filename": "/runtime/autoload/sqlcomplete.vim", "start": 878189, "end": 917464}, {"filename": "/runtime/autoload/syntaxcomplete.vim", "start": 917464, "end": 951115}, {"filename": "/runtime/autoload/tar.vim", "start": 951115, "end": 979518}, {"filename": "/runtime/autoload/tutor.vim", "start": 979518, "end": 986629}, {"filename": "/runtime/autoload/typst.vim", "start": 986629, "end": 988637}, {"filename": "/runtime/autoload/vimexpect.vim", "start": 988637, "end": 993583}, {"filename": "/runtime/autoload/xml/html32.vim", "start": 993583, "end": 1011913}, {"filename": "/runtime/autoload/xml/html401f.vim", "start": 1011913, "end": 1066035}, {"filename": "/runtime/autoload/xml/html401s.vim", "start": 1066035, "end": 1107573}, {"filename": "/runtime/autoload/xml/html401t.vim", "start": 1107573, "end": 1161264}, {"filename": "/runtime/autoload/xml/html40f.vim", "start": 1161264, "end": 1215293}, {"filename": "/runtime/autoload/xml/html40s.vim", "start": 1215293, "end": 1256738}, {"filename": "/runtime/autoload/xml/html40t.vim", "start": 1256738, "end": 1310335}, {"filename": "/runtime/autoload/xml/xhtml10f.vim", "start": 1310335, "end": 1366387}, {"filename": "/runtime/autoload/xml/xhtml10s.vim", "start": 1366387, "end": 1410350}, {"filename": "/runtime/autoload/xml/xhtml10t.vim", "start": 1410350, "end": 1466678}, {"filename": "/runtime/autoload/xml/xhtml11.vim", "start": 1466678, "end": 1514827}, {"filename": "/runtime/autoload/xml/xsd.vim", "start": 1514827, "end": 1519938}, {"filename": "/runtime/autoload/xml/xsl.vim", "start": 1519938, "end": 1524617}, {"filename": "/runtime/autoload/xmlcomplete.vim", "start": 1524617, "end": 1539554}, {"filename": "/runtime/autoload/xmlformat.vim", "start": 1539554, "end": 1545987}, {"filename": "/runtime/colors/README.txt", "start": 1545987, "end": 1550601}, {"filename": "/runtime/colors/blue.vim", "start": 1550601, "end": 1567243}, {"filename": "/runtime/colors/catppuccin.vim", "start": 1567243, "end": 1621255}, {"filename": "/runtime/colors/darkblue.vim", "start": 1621255, "end": 1637725}, {"filename": "/runtime/colors/default.vim", "start": 1637725, "end": 1638112}, {"filename": "/runtime/colors/delek.vim", "start": 1638112, "end": 1654618}, {"filename": "/runtime/colors/desert.vim", "start": 1654618, "end": 1670920}, {"filename": "/runtime/colors/elflord.vim", "start": 1670920, "end": 1688010}, {"filename": "/runtime/colors/evening.vim", "start": 1688010, "end": 1704526}, {"filename": "/runtime/colors/habamax.vim", "start": 1704526, "end": 1724619}, {"filename": "/runtime/colors/industry.vim", "start": 1724619, "end": 1740915}, {"filename": "/runtime/colors/koehler.vim", "start": 1740915, "end": 1757126}, {"filename": "/runtime/colors/lunaperche.vim", "start": 1757126, "end": 1800152}, {"filename": "/runtime/colors/morning.vim", "start": 1800152, "end": 1816063}, {"filename": "/runtime/colors/murphy.vim", "start": 1816063, "end": 1831915}, {"filename": "/runtime/colors/pablo.vim", "start": 1831915, "end": 1847988}, {"filename": "/runtime/colors/peachpuff.vim", "start": 1847988, "end": 1864432}, {"filename": "/runtime/colors/quiet.vim", "start": 1864432, "end": 1898830}, {"filename": "/runtime/colors/retrobox.vim", "start": 1898830, "end": 1941496}, {"filename": "/runtime/colors/ron.vim", "start": 1941496, "end": 1958412}, {"filename": "/runtime/colors/shine.vim", "start": 1958412, "end": 1975216}, {"filename": "/runtime/colors/slate.vim", "start": 1975216, "end": 1992176}, {"filename": "/runtime/colors/sorbet.vim", "start": 1992176, "end": 2010522}, {"filename": "/runtime/colors/torte.vim", "start": 2010522, "end": 2026587}, {"filename": "/runtime/colors/unokai.vim", "start": 2026587, "end": 2047951}, {"filename": "/runtime/colors/vim.lua", "start": 2047951, "end": 2068029}, {"filename": "/runtime/colors/wildcharm.vim", "start": 2068029, "end": 2104329}, {"filename": "/runtime/colors/zaibatsu.vim", "start": 2104329, "end": 2121139}, {"filename": "/runtime/colors/zellner.vim", "start": 2121139, "end": 2137555}, {"filename": "/runtime/compiler/README.txt", "start": 2137555, "end": 2138094}, {"filename": "/runtime/compiler/ant.vim", "start": 2138094, "end": 2138983}, {"filename": "/runtime/compiler/bash.vim", "start": 2138983, "end": 2139242}, {"filename": "/runtime/compiler/bcc.vim", "start": 2139242, "end": 2139639}, {"filename": "/runtime/compiler/bdf.vim", "start": 2139639, "end": 2140242}, {"filename": "/runtime/compiler/bean_check.vim", "start": 2140242, "end": 2140716}, {"filename": "/runtime/compiler/biome.vim", "start": 2140716, "end": 2141729}, {"filename": "/runtime/compiler/cabal.vim", "start": 2141729, "end": 2142789}, {"filename": "/runtime/compiler/cargo.vim", "start": 2142789, "end": 2144305}, {"filename": "/runtime/compiler/checkstyle.vim", "start": 2144305, "end": 2144976}, {"filename": "/runtime/compiler/cm3.vim", "start": 2144976, "end": 2145489}, {"filename": "/runtime/compiler/context.vim", "start": 2145489, "end": 2147374}, {"filename": "/runtime/compiler/cppcheck.vim", "start": 2147374, "end": 2149002}, {"filename": "/runtime/compiler/cs.vim", "start": 2149002, "end": 2149642}, {"filename": "/runtime/compiler/csslint.vim", "start": 2149642, "end": 2150132}, {"filename": "/runtime/compiler/cucumber.vim", "start": 2150132, "end": 2150641}, {"filename": "/runtime/compiler/dart.vim", "start": 2150641, "end": 2151070}, {"filename": "/runtime/compiler/dart2js.vim", "start": 2151070, "end": 2151585}, {"filename": "/runtime/compiler/dart2native.vim", "start": 2151585, "end": 2152092}, {"filename": "/runtime/compiler/dartanalyser.vim", "start": 2152092, "end": 2152527}, {"filename": "/runtime/compiler/dartdevc.vim", "start": 2152527, "end": 2153100}, {"filename": "/runtime/compiler/dartdoc.vim", "start": 2153100, "end": 2153598}, {"filename": "/runtime/compiler/dartfmt.vim", "start": 2153598, "end": 2154045}, {"filename": "/runtime/compiler/decada.vim", "start": 2154045, "end": 2155913}, {"filename": "/runtime/compiler/dot.vim", "start": 2155913, "end": 2156425}, {"filename": "/runtime/compiler/dotnet.vim", "start": 2156425, "end": 2157478}, {"filename": "/runtime/compiler/erlang.vim", "start": 2157478, "end": 2157822}, {"filename": "/runtime/compiler/eruby.vim", "start": 2157822, "end": 2158557}, {"filename": "/runtime/compiler/eslint.vim", "start": 2158557, "end": 2158942}, {"filename": "/runtime/compiler/fbc.vim", "start": 2158942, "end": 2159458}, {"filename": "/runtime/compiler/fortran_F.vim", "start": 2159458, "end": 2160108}, {"filename": "/runtime/compiler/fortran_cv.vim", "start": 2160108, "end": 2160805}, {"filename": "/runtime/compiler/fortran_elf90.vim", "start": 2160805, "end": 2161555}, {"filename": "/runtime/compiler/fortran_g77.vim", "start": 2161555, "end": 2162652}, {"filename": "/runtime/compiler/fortran_lf95.vim", "start": 2162652, "end": 2163283}, {"filename": "/runtime/compiler/fpc.vim", "start": 2163283, "end": 2163694}, {"filename": "/runtime/compiler/g95.vim", "start": 2163694, "end": 2164331}, {"filename": "/runtime/compiler/gawk.vim", "start": 2164331, "end": 2165076}, {"filename": "/runtime/compiler/gcc.vim", "start": 2165076, "end": 2166636}, {"filename": "/runtime/compiler/gfortran.vim", "start": 2166636, "end": 2167255}, {"filename": "/runtime/compiler/ghc.vim", "start": 2167255, "end": 2167796}, {"filename": "/runtime/compiler/gjs.vim", "start": 2167796, "end": 2168239}, {"filename": "/runtime/compiler/gleam_build.vim", "start": 2168239, "end": 2168913}, {"filename": "/runtime/compiler/gm2.vim", "start": 2168913, "end": 2169406}, {"filename": "/runtime/compiler/gnat.vim", "start": 2169406, "end": 2171665}, {"filename": "/runtime/compiler/go.vim", "start": 2171665, "end": 2172186}, {"filename": "/runtime/compiler/groff.vim", "start": 2172186, "end": 2173797}, {"filename": "/runtime/compiler/haml.vim", "start": 2173797, "end": 2174296}, {"filename": "/runtime/compiler/hare.vim", "start": 2174296, "end": 2174870}, {"filename": "/runtime/compiler/hp_acc.vim", "start": 2174870, "end": 2175870}, {"filename": "/runtime/compiler/iar.vim", "start": 2175870, "end": 2176713}, {"filename": "/runtime/compiler/icc.vim", "start": 2176713, "end": 2177246}, {"filename": "/runtime/compiler/icon.vim", "start": 2177246, "end": 2177963}, {"filename": "/runtime/compiler/ifort.vim", "start": 2177963, "end": 2178601}, {"filename": "/runtime/compiler/intel.vim", "start": 2178601, "end": 2179144}, {"filename": "/runtime/compiler/irix5_c.vim", "start": 2179144, "end": 2179754}, {"filename": "/runtime/compiler/irix5_cpp.vim", "start": 2179754, "end": 2180373}, {"filename": "/runtime/compiler/javac.vim", "start": 2180373, "end": 2181032}, {"filename": "/runtime/compiler/jest.vim", "start": 2181032, "end": 2181706}, {"filename": "/runtime/compiler/jikes.vim", "start": 2181706, "end": 2182176}, {"filename": "/runtime/compiler/jjs.vim", "start": 2182176, "end": 2182550}, {"filename": "/runtime/compiler/jq.vim", "start": 2182550, "end": 2183094}, {"filename": "/runtime/compiler/jshint.vim", "start": 2183094, "end": 2183531}, {"filename": "/runtime/compiler/jsonlint.vim", "start": 2183531, "end": 2183995}, {"filename": "/runtime/compiler/just.vim", "start": 2183995, "end": 2184374}, {"filename": "/runtime/compiler/make.vim", "start": 2184374, "end": 2184768}, {"filename": "/runtime/compiler/maven.vim", "start": 2184768, "end": 2188192}, {"filename": "/runtime/compiler/mcs.vim", "start": 2188192, "end": 2189175}, {"filename": "/runtime/compiler/mips_c.vim", "start": 2189175, "end": 2189814}, {"filename": "/runtime/compiler/mipspro_c89.vim", "start": 2189814, "end": 2190483}, {"filename": "/runtime/compiler/mipspro_cpp.vim", "start": 2190483, "end": 2191132}, {"filename": "/runtime/compiler/modelsim_vcom.vim", "start": 2191132, "end": 2191925}, {"filename": "/runtime/compiler/msbuild.vim", "start": 2191925, "end": 2192402}, {"filename": "/runtime/compiler/msvc.vim", "start": 2192402, "end": 2192829}, {"filename": "/runtime/compiler/mypy.vim", "start": 2192829, "end": 2193366}, {"filename": "/runtime/compiler/neato.vim", "start": 2193366, "end": 2193884}, {"filename": "/runtime/compiler/ocaml.vim", "start": 2193884, "end": 2195925}, {"filename": "/runtime/compiler/onsgmls.vim", "start": 2195925, "end": 2196378}, {"filename": "/runtime/compiler/pandoc.vim", "start": 2196378, "end": 2198668}, {"filename": "/runtime/compiler/pbx.vim", "start": 2198668, "end": 2199085}, {"filename": "/runtime/compiler/perl.vim", "start": 2199085, "end": 2200459}, {"filename": "/runtime/compiler/perlcritic.vim", "start": 2200459, "end": 2201231}, {"filename": "/runtime/compiler/php.vim", "start": 2201231, "end": 2201818}, {"filename": "/runtime/compiler/phpstan.vim", "start": 2201818, "end": 2202162}, {"filename": "/runtime/compiler/pip_compile.vim", "start": 2202162, "end": 2203893}, {"filename": "/runtime/compiler/podchecker.vim", "start": 2203893, "end": 2204727}, {"filename": "/runtime/compiler/powershell.vim", "start": 2204727, "end": 2207681}, {"filename": "/runtime/compiler/pylint.vim", "start": 2207681, "end": 2208529}, {"filename": "/runtime/compiler/pyright.vim", "start": 2208529, "end": 2209169}, {"filename": "/runtime/compiler/pytest.vim", "start": 2209169, "end": 2212977}, {"filename": "/runtime/compiler/pyunit.vim", "start": 2212977, "end": 2213332}, {"filename": "/runtime/compiler/raco.vim", "start": 2213332, "end": 2213705}, {"filename": "/runtime/compiler/racomake.vim", "start": 2213705, "end": 2214100}, {"filename": "/runtime/compiler/racosetup.vim", "start": 2214100, "end": 2214491}, {"filename": "/runtime/compiler/racotest.vim", "start": 2214491, "end": 2214888}, {"filename": "/runtime/compiler/rake.vim", "start": 2214888, "end": 2215844}, {"filename": "/runtime/compiler/rhino.vim", "start": 2215844, "end": 2216519}, {"filename": "/runtime/compiler/rime_deployer.vim", "start": 2216519, "end": 2217516}, {"filename": "/runtime/compiler/rspec.vim", "start": 2217516, "end": 2218232}, {"filename": "/runtime/compiler/rst.vim", "start": 2218232, "end": 2219083}, {"filename": "/runtime/compiler/rubocop.vim", "start": 2219083, "end": 2219461}, {"filename": "/runtime/compiler/ruby.vim", "start": 2219461, "end": 2220369}, {"filename": "/runtime/compiler/rubyunit.vim", "start": 2220369, "end": 2221086}, {"filename": "/runtime/compiler/ruff.vim", "start": 2221086, "end": 2221864}, {"filename": "/runtime/compiler/rustc.vim", "start": 2221864, "end": 2223374}, {"filename": "/runtime/compiler/sass.vim", "start": 2223374, "end": 2223912}, {"filename": "/runtime/compiler/scdoc.vim", "start": 2223912, "end": 2224337}, {"filename": "/runtime/compiler/se.vim", "start": 2224337, "end": 2224959}, {"filename": "/runtime/compiler/shellcheck.vim", "start": 2224959, "end": 2225435}, {"filename": "/runtime/compiler/sml.vim", "start": 2225435, "end": 2225962}, {"filename": "/runtime/compiler/spectral.vim", "start": 2225962, "end": 2226354}, {"filename": "/runtime/compiler/splint.vim", "start": 2226354, "end": 2228573}, {"filename": "/runtime/compiler/spotbugs.vim", "start": 2228573, "end": 2238492}, {"filename": "/runtime/compiler/stack.vim", "start": 2238492, "end": 2239362}, {"filename": "/runtime/compiler/standard.vim", "start": 2239362, "end": 2239742}, {"filename": "/runtime/compiler/stylelint.vim", "start": 2239742, "end": 2240273}, {"filename": "/runtime/compiler/svelte-check.vim", "start": 2240273, "end": 2241131}, {"filename": "/runtime/compiler/tcl.vim", "start": 2241131, "end": 2241425}, {"filename": "/runtime/compiler/tex.vim", "start": 2241425, "end": 2243444}, {"filename": "/runtime/compiler/tidy.vim", "start": 2243444, "end": 2243934}, {"filename": "/runtime/compiler/tombi.vim", "start": 2243934, "end": 2246056}, {"filename": "/runtime/compiler/ts-node.vim", "start": 2246056, "end": 2246570}, {"filename": "/runtime/compiler/tsc.vim", "start": 2246570, "end": 2247204}, {"filename": "/runtime/compiler/ty.vim", "start": 2247204, "end": 2247786}, {"filename": "/runtime/compiler/typedoc.vim", "start": 2247786, "end": 2248276}, {"filename": "/runtime/compiler/typst.vim", "start": 2248276, "end": 2248861}, {"filename": "/runtime/compiler/vimdoc.vim", "start": 2248861, "end": 2249500}, {"filename": "/runtime/compiler/xbuild.vim", "start": 2249500, "end": 2249957}, {"filename": "/runtime/compiler/xmllint.vim", "start": 2249957, "end": 2250491}, {"filename": "/runtime/compiler/xmlwf.vim", "start": 2250491, "end": 2250895}, {"filename": "/runtime/compiler/xo.vim", "start": 2250895, "end": 2251390}, {"filename": "/runtime/compiler/yamllint.vim", "start": 2251390, "end": 2251893}, {"filename": "/runtime/compiler/zig.vim", "start": 2251893, "end": 2253197}, {"filename": "/runtime/compiler/zig_build.vim", "start": 2253197, "end": 2253853}, {"filename": "/runtime/compiler/zig_build_exe.vim", "start": 2253853, "end": 2254431}, {"filename": "/runtime/compiler/zig_cc.vim", "start": 2254431, "end": 2254848}, {"filename": "/runtime/compiler/zig_test.vim", "start": 2254848, "end": 2255412}, {"filename": "/runtime/compiler/zsh.vim", "start": 2255412, "end": 2255774}, {"filename": "/runtime/delmenu.vim", "start": 2255774, "end": 2257049}, {"filename": "/runtime/doc/api-ui-events.txt", "start": 2257049, "end": 2296949}, {"filename": "/runtime/doc/api.txt", "start": 2296949, "end": 2476585}, {"filename": "/runtime/doc/autocmd.txt", "start": 2476585, "end": 2555932}, {"filename": "/runtime/doc/change.txt", "start": 2555932, "end": 2636816}, {"filename": "/runtime/doc/channel.txt", "start": 2636816, "end": 2647707}, {"filename": "/runtime/doc/cmdline.txt", "start": 2647707, "end": 2703064}, {"filename": "/runtime/doc/credits.txt", "start": 2703064, "end": 2724606}, {"filename": "/runtime/doc/deprecated.txt", "start": 2724606, "end": 2743244}, {"filename": "/runtime/doc/dev.txt", "start": 2743244, "end": 2778126}, {"filename": "/runtime/doc/dev_arch.txt", "start": 2778126, "end": 2808262}, {"filename": "/runtime/doc/dev_style.txt", "start": 2808262, "end": 2839673}, {"filename": "/runtime/doc/dev_test.txt", "start": 2839673, "end": 2862134}, {"filename": "/runtime/doc/dev_theme.txt", "start": 2862134, "end": 2866710}, {"filename": "/runtime/doc/dev_tools.txt", "start": 2866710, "end": 2886268}, {"filename": "/runtime/doc/dev_vimpatch.txt", "start": 2886268, "end": 2902443}, {"filename": "/runtime/doc/diagnostic.txt", "start": 2902443, "end": 2952424}, {"filename": "/runtime/doc/diff.txt", "start": 2952424, "end": 2974645}, {"filename": "/runtime/doc/digraph.txt", "start": 2974645, "end": 3039413}, {"filename": "/runtime/doc/editing.txt", "start": 3039413, "end": 3111867}, {"filename": "/runtime/doc/faq.txt", "start": 3111867, "end": 3131145}, {"filename": "/runtime/doc/filetype.txt", "start": 3131145, "end": 3179991}, {"filename": "/runtime/doc/fold.txt", "start": 3179991, "end": 3206236}, {"filename": "/runtime/doc/ft_ada.txt", "start": 3206236, "end": 3223672}, {"filename": "/runtime/doc/ft_hare.txt", "start": 3223672, "end": 3226619}, {"filename": "/runtime/doc/ft_ps1.txt", "start": 3226619, "end": 3228727}, {"filename": "/runtime/doc/ft_raku.txt", "start": 3228727, "end": 3232939}, {"filename": "/runtime/doc/ft_rust.txt", "start": 3232939, "end": 3249786}, {"filename": "/runtime/doc/ft_sql.txt", "start": 3249786, "end": 3280121}, {"filename": "/runtime/doc/gui.txt", "start": 3280121, "end": 3326466}, {"filename": "/runtime/doc/health.txt", "start": 3326466, "end": 3332293}, {"filename": "/runtime/doc/help.txt", "start": 3332293, "end": 3337840}, {"filename": "/runtime/doc/helphelp.txt", "start": 3337840, "end": 3361316}, {"filename": "/runtime/doc/if_perl.txt", "start": 3361316, "end": 3370135}, {"filename": "/runtime/doc/if_pyth.txt", "start": 3370135, "end": 3394960}, {"filename": "/runtime/doc/if_ruby.txt", "start": 3394960, "end": 3401150}, {"filename": "/runtime/doc/indent.txt", "start": 3401150, "end": 3444789}, {"filename": "/runtime/doc/index.txt", "start": 3444789, "end": 3524712}, {"filename": "/runtime/doc/insert.txt", "start": 3524712, "end": 3614876}, {"filename": "/runtime/doc/intro.txt", "start": 3614876, "end": 3645238}, {"filename": "/runtime/doc/job_control.txt", "start": 3645238, "end": 3650496}, {"filename": "/runtime/doc/l10n-arabic.txt", "start": 3650496, "end": 3663062}, {"filename": "/runtime/doc/l10n-hebrew.txt", "start": 3663062, "end": 3666889}, {"filename": "/runtime/doc/l10n-russian.txt", "start": 3666889, "end": 3669449}, {"filename": "/runtime/doc/l10n-vietnamese.txt", "start": 3669449, "end": 3672596}, {"filename": "/runtime/doc/lsp.txt", "start": 3672596, "end": 3818250}, {"filename": "/runtime/doc/lua-async.txt", "start": 3818250, "end": 3840400}, {"filename": "/runtime/doc/lua-bit.txt", "start": 3840400, "end": 3857435}, {"filename": "/runtime/doc/lua-guide.txt", "start": 3857435, "end": 3887192}, {"filename": "/runtime/doc/lua-plugin.txt", "start": 3887192, "end": 3901427}, {"filename": "/runtime/doc/lua.txt", "start": 3901427, "end": 4131917}, {"filename": "/runtime/doc/luaref.txt", "start": 4131917, "end": 4359095}, {"filename": "/runtime/doc/luvref.txt", "start": 4359095, "end": 4537900}, {"filename": "/runtime/doc/map.txt", "start": 4537900, "end": 4612881}, {"filename": "/runtime/doc/mbyte.txt", "start": 4612881, "end": 4642651}, {"filename": "/runtime/doc/message.txt", "start": 4642651, "end": 4674871}, {"filename": "/runtime/doc/mlang.txt", "start": 4674871, "end": 4682201}, {"filename": "/runtime/doc/motion.txt", "start": 4682201, "end": 4739269}, {"filename": "/runtime/doc/news-0.10.txt", "start": 4739269, "end": 4763249}, {"filename": "/runtime/doc/news-0.11.txt", "start": 4763249, "end": 4787326}, {"filename": "/runtime/doc/news-0.12.txt", "start": 4787326, "end": 4810798}, {"filename": "/runtime/doc/news-0.9.txt", "start": 4810798, "end": 4822817}, {"filename": "/runtime/doc/news.txt", "start": 4822817, "end": 4851382}, {"filename": "/runtime/doc/nvim.txt", "start": 4851382, "end": 4855170}, {"filename": "/runtime/doc/options.txt", "start": 4855170, "end": 5200348}, {"filename": "/runtime/doc/pack.txt", "start": 5200348, "end": 5228725}, {"filename": "/runtime/doc/pattern.txt", "start": 5228725, "end": 5293111}, {"filename": "/runtime/doc/pi_gzip.txt", "start": 5293111, "end": 5294641}, {"filename": "/runtime/doc/pi_msgpack.txt", "start": 5294641, "end": 5300339}, {"filename": "/runtime/doc/pi_paren.txt", "start": 5300339, "end": 5303089}, {"filename": "/runtime/doc/pi_spec.txt", "start": 5303089, "end": 5307406}, {"filename": "/runtime/doc/pi_tar.txt", "start": 5307406, "end": 5314730}, {"filename": "/runtime/doc/pi_tutor.txt", "start": 5314730, "end": 5316513}, {"filename": "/runtime/doc/plugins.txt", "start": 5316513, "end": 5336035}, {"filename": "/runtime/doc/provider.txt", "start": 5336035, "end": 5350017}, {"filename": "/runtime/doc/quickfix.txt", "start": 5350017, "end": 5453975}, {"filename": "/runtime/doc/quickref.txt", "start": 5453975, "end": 5521420}, {"filename": "/runtime/doc/recover.txt", "start": 5521420, "end": 5529912}, {"filename": "/runtime/doc/remote.txt", "start": 5529912, "end": 5534104}, {"filename": "/runtime/doc/remote_plugin.txt", "start": 5534104, "end": 5540218}, {"filename": "/runtime/doc/repeat.txt", "start": 5540218, "end": 5560956}, {"filename": "/runtime/doc/rileft.txt", "start": 5560956, "end": 5566097}, {"filename": "/runtime/doc/scroll.txt", "start": 5566097, "end": 5576732}, {"filename": "/runtime/doc/sign.txt", "start": 5576732, "end": 5589862}, {"filename": "/runtime/doc/spell.txt", "start": 5589862, "end": 5658016}, {"filename": "/runtime/doc/starting.txt", "start": 5658016, "end": 5736542}, {"filename": "/runtime/doc/support.txt", "start": 5736542, "end": 5738763}, {"filename": "/runtime/doc/syntax.txt", "start": 5738763, "end": 5961091}, {"filename": "/runtime/doc/tabpage.txt", "start": 5961091, "end": 5978631}, {"filename": "/runtime/doc/tags", "start": 5978631, "end": 6383999}, {"filename": "/runtime/doc/tagsrch.txt", "start": 6383999, "end": 6424974}, {"filename": "/runtime/doc/terminal.txt", "start": 6424974, "end": 6455976}, {"filename": "/runtime/doc/tips.txt", "start": 6455976, "end": 6473301}, {"filename": "/runtime/doc/treesitter.txt", "start": 6473301, "end": 6552362}, {"filename": "/runtime/doc/tui.txt", "start": 6552362, "end": 6570686}, {"filename": "/runtime/doc/uganda.txt", "start": 6570686, "end": 6582105}, {"filename": "/runtime/doc/undo.txt", "start": 6582105, "end": 6599325}, {"filename": "/runtime/doc/userfunc.txt", "start": 6599325, "end": 6618215}, {"filename": "/runtime/doc/usr_01.txt", "start": 6618215, "end": 6622256}, {"filename": "/runtime/doc/usr_02.txt", "start": 6622256, "end": 6645937}, {"filename": "/runtime/doc/usr_03.txt", "start": 6645937, "end": 6669522}, {"filename": "/runtime/doc/usr_04.txt", "start": 6669522, "end": 6688678}, {"filename": "/runtime/doc/usr_05.txt", "start": 6688678, "end": 6710251}, {"filename": "/runtime/doc/usr_06.txt", "start": 6710251, "end": 6717131}, {"filename": "/runtime/doc/usr_07.txt", "start": 6717131, "end": 6733084}, {"filename": "/runtime/doc/usr_08.txt", "start": 6733084, "end": 6752586}, {"filename": "/runtime/doc/usr_09.txt", "start": 6752586, "end": 6762944}, {"filename": "/runtime/doc/usr_10.txt", "start": 6762944, "end": 6792500}, {"filename": "/runtime/doc/usr_11.txt", "start": 6792500, "end": 6805172}, {"filename": "/runtime/doc/usr_12.txt", "start": 6805172, "end": 6817998}, {"filename": "/runtime/doc/usr_20.txt", "start": 6817998, "end": 6831934}, {"filename": "/runtime/doc/usr_21.txt", "start": 6831934, "end": 6849913}, {"filename": "/runtime/doc/usr_22.txt", "start": 6849913, "end": 6860664}, {"filename": "/runtime/doc/usr_23.txt", "start": 6860664, "end": 6869786}, {"filename": "/runtime/doc/usr_24.txt", "start": 6869786, "end": 6890658}, {"filename": "/runtime/doc/usr_25.txt", "start": 6890658, "end": 6909975}, {"filename": "/runtime/doc/usr_26.txt", "start": 6909975, "end": 6917911}, {"filename": "/runtime/doc/usr_27.txt", "start": 6917911, "end": 6935640}, {"filename": "/runtime/doc/usr_28.txt", "start": 6935640, "end": 6951616}, {"filename": "/runtime/doc/usr_29.txt", "start": 6951616, "end": 6971662}, {"filename": "/runtime/doc/usr_30.txt", "start": 6971662, "end": 6997638}, {"filename": "/runtime/doc/usr_31.txt", "start": 6997638, "end": 7005177}, {"filename": "/runtime/doc/usr_32.txt", "start": 7005177, "end": 7010513}, {"filename": "/runtime/doc/usr_40.txt", "start": 7010513, "end": 7034017}, {"filename": "/runtime/doc/usr_41.txt", "start": 7034017, "end": 7129257}, {"filename": "/runtime/doc/usr_42.txt", "start": 7129257, "end": 7142801}, {"filename": "/runtime/doc/usr_43.txt", "start": 7142801, "end": 7150229}, {"filename": "/runtime/doc/usr_44.txt", "start": 7150229, "end": 7179162}, {"filename": "/runtime/doc/usr_45.txt", "start": 7179162, "end": 7194918}, {"filename": "/runtime/doc/usr_toc.txt", "start": 7194918, "end": 7203712}, {"filename": "/runtime/doc/various.txt", "start": 7203712, "end": 7227991}, {"filename": "/runtime/doc/vi_diff.txt", "start": 7227991, "end": 7244752}, {"filename": "/runtime/doc/vim_diff.txt", "start": 7244752, "end": 7284967}, {"filename": "/runtime/doc/vimeval.txt", "start": 7284967, "end": 7424397}, {"filename": "/runtime/doc/vimfn.txt", "start": 7424397, "end": 7905373}, {"filename": "/runtime/doc/vimscript.txt", "start": 7905373, "end": 7933366}, {"filename": "/runtime/doc/visual.txt", "start": 7933366, "end": 7956848}, {"filename": "/runtime/doc/vvars.txt", "start": 7956848, "end": 7986419}, {"filename": "/runtime/doc/windows.txt", "start": 7986419, "end": 8045521}, {"filename": "/runtime/doc/zip.txt", "start": 8045521, "end": 8047477}, {"filename": "/runtime/embedded_data.zig", "start": 8047477, "end": 8047667}, {"filename": "/runtime/example_init.lua", "start": 8047667, "end": 8051491}, {"filename": "/runtime/filetype.lua", "start": 8051491, "end": 8053161}, {"filename": "/runtime/ftoff.vim", "start": 8053161, "end": 8053507}, {"filename": "/runtime/ftplugin.vim", "start": 8053507, "end": 8054777}, {"filename": "/runtime/ftplugin/8th.vim", "start": 8054777, "end": 8055610}, {"filename": "/runtime/ftplugin/README.txt", "start": 8055610, "end": 8056479}, {"filename": "/runtime/ftplugin/a2ps.vim", "start": 8056479, "end": 8056949}, {"filename": "/runtime/ftplugin/aap.vim", "start": 8056949, "end": 8058257}, {"filename": "/runtime/ftplugin/abap.vim", "start": 8058257, "end": 8059698}, {"filename": "/runtime/ftplugin/abaqus.vim", "start": 8059698, "end": 8064049}, {"filename": "/runtime/ftplugin/abnf.vim", "start": 8064049, "end": 8064428}, {"filename": "/runtime/ftplugin/ada.vim", "start": 8064428, "end": 8071514}, {"filename": "/runtime/ftplugin/algol68.vim", "start": 8071514, "end": 8072836}, {"filename": "/runtime/ftplugin/alsaconf.vim", "start": 8072836, "end": 8073274}, {"filename": "/runtime/ftplugin/ant.vim", "start": 8073274, "end": 8074778}, {"filename": "/runtime/ftplugin/antlr4.vim", "start": 8074778, "end": 8075159}, {"filename": "/runtime/ftplugin/apache.vim", "start": 8075159, "end": 8075520}, {"filename": "/runtime/ftplugin/arch.vim", "start": 8075520, "end": 8075951}, {"filename": "/runtime/ftplugin/arduino.lua", "start": 8075951, "end": 8076061}, {"filename": "/runtime/ftplugin/arduino.vim", "start": 8076061, "end": 8078322}, {"filename": "/runtime/ftplugin/art.vim", "start": 8078322, "end": 8078831}, {"filename": "/runtime/ftplugin/asciidoc.vim", "start": 8078831, "end": 8080841}, {"filename": "/runtime/ftplugin/asm.vim", "start": 8080841, "end": 8081834}, {"filename": "/runtime/ftplugin/aspvbs.vim", "start": 8081834, "end": 8083913}, {"filename": "/runtime/ftplugin/astro.vim", "start": 8083913, "end": 8089839}, {"filename": "/runtime/ftplugin/asy.vim", "start": 8089839, "end": 8090118}, {"filename": "/runtime/ftplugin/autohotkey.vim", "start": 8090118, "end": 8090472}, {"filename": "/runtime/ftplugin/automake.vim", "start": 8090472, "end": 8090829}, {"filename": "/runtime/ftplugin/autopkgtest.vim", "start": 8090829, "end": 8091297}, {"filename": "/runtime/ftplugin/awk.vim", "start": 8091297, "end": 8093017}, {"filename": "/runtime/ftplugin/bash.vim", "start": 8093017, "end": 8093541}, {"filename": "/runtime/ftplugin/basic.vim", "start": 8093541, "end": 8095708}, {"filename": "/runtime/ftplugin/bazelrc.vim", "start": 8095708, "end": 8096062}, {"filename": "/runtime/ftplugin/bdf.vim", "start": 8096062, "end": 8096511}, {"filename": "/runtime/ftplugin/beancount.vim", "start": 8096511, "end": 8097514}, {"filename": "/runtime/ftplugin/bicep-params.vim", "start": 8097514, "end": 8097716}, {"filename": "/runtime/ftplugin/bicep.vim", "start": 8097716, "end": 8098032}, {"filename": "/runtime/ftplugin/bindzone.vim", "start": 8098032, "end": 8098397}, {"filename": "/runtime/ftplugin/bitbake.vim", "start": 8098397, "end": 8098858}, {"filename": "/runtime/ftplugin/bp.vim", "start": 8098858, "end": 8099204}, {"filename": "/runtime/ftplugin/bpftrace.vim", "start": 8099204, "end": 8099608}, {"filename": "/runtime/ftplugin/brighterscript.vim", "start": 8099608, "end": 8099842}, {"filename": "/runtime/ftplugin/brightscript.vim", "start": 8099842, "end": 8100108}, {"filename": "/runtime/ftplugin/bst.vim", "start": 8100108, "end": 8100441}, {"filename": "/runtime/ftplugin/btm.vim", "start": 8100441, "end": 8100843}, {"filename": "/runtime/ftplugin/bzl.vim", "start": 8100843, "end": 8104144}, {"filename": "/runtime/ftplugin/c.lua", "start": 8104144, "end": 8104586}, {"filename": "/runtime/ftplugin/c.vim", "start": 8104586, "end": 8107079}, {"filename": "/runtime/ftplugin/c3.vim", "start": 8107079, "end": 8107405}, {"filename": "/runtime/ftplugin/cabal.vim", "start": 8107405, "end": 8108008}, {"filename": "/runtime/ftplugin/calendar.vim", "start": 8108008, "end": 8108569}, {"filename": "/runtime/ftplugin/cdrdaoconf.vim", "start": 8108569, "end": 8108952}, {"filename": "/runtime/ftplugin/cedar.vim", "start": 8108952, "end": 8109212}, {"filename": "/runtime/ftplugin/cfg.vim", "start": 8109212, "end": 8109600}, {"filename": "/runtime/ftplugin/cgdbrc.vim", "start": 8109600, "end": 8110180}, {"filename": "/runtime/ftplugin/ch.lua", "start": 8110180, "end": 8110290}, {"filename": "/runtime/ftplugin/ch.vim", "start": 8110290, "end": 8110782}, {"filename": "/runtime/ftplugin/changelog.vim", "start": 8110782, "end": 8120289}, {"filename": "/runtime/ftplugin/chatito.vim", "start": 8120289, "end": 8120672}, {"filename": "/runtime/ftplugin/checkhealth.lua", "start": 8120672, "end": 8121901}, {"filename": "/runtime/ftplugin/chicken.vim", "start": 8121901, "end": 8123666}, {"filename": "/runtime/ftplugin/clojure.vim", "start": 8123666, "end": 8127149}, {"filename": "/runtime/ftplugin/cmake.vim", "start": 8127149, "end": 8128153}, {"filename": "/runtime/ftplugin/cmakecache.vim", "start": 8128153, "end": 8128464}, {"filename": "/runtime/ftplugin/cobol.vim", "start": 8128464, "end": 8138331}, {"filename": "/runtime/ftplugin/codeowners.vim", "start": 8138331, "end": 8138641}, {"filename": "/runtime/ftplugin/conf.vim", "start": 8138641, "end": 8139075}, {"filename": "/runtime/ftplugin/config.vim", "start": 8139075, "end": 8140517}, {"filename": "/runtime/ftplugin/confini.vim", "start": 8140517, "end": 8140731}, {"filename": "/runtime/ftplugin/context.vim", "start": 8140731, "end": 8146320}, {"filename": "/runtime/ftplugin/cook.vim", "start": 8146320, "end": 8146584}, {"filename": "/runtime/ftplugin/corn.vim", "start": 8146584, "end": 8147041}, {"filename": "/runtime/ftplugin/cpp.vim", "start": 8147041, "end": 8147801}, {"filename": "/runtime/ftplugin/crm.vim", "start": 8147801, "end": 8148215}, {"filename": "/runtime/ftplugin/crontab.vim", "start": 8148215, "end": 8148576}, {"filename": "/runtime/ftplugin/cs.lua", "start": 8148576, "end": 8148686}, {"filename": "/runtime/ftplugin/cs.vim", "start": 8148686, "end": 8150330}, {"filename": "/runtime/ftplugin/csc.vim", "start": 8150330, "end": 8151027}, {"filename": "/runtime/ftplugin/csh.vim", "start": 8151027, "end": 8153343}, {"filename": "/runtime/ftplugin/css.vim", "start": 8153343, "end": 8154038}, {"filename": "/runtime/ftplugin/csv.vim", "start": 8154038, "end": 8154549}, {"filename": "/runtime/ftplugin/cucumber.vim", "start": 8154549, "end": 8159858}, {"filename": "/runtime/ftplugin/cuda.vim", "start": 8159858, "end": 8160081}, {"filename": "/runtime/ftplugin/cvsrc.vim", "start": 8160081, "end": 8160480}, {"filename": "/runtime/ftplugin/d.lua", "start": 8160480, "end": 8160590}, {"filename": "/runtime/ftplugin/dart.vim", "start": 8160590, "end": 8160987}, {"filename": "/runtime/ftplugin/dax.vim", "start": 8160987, "end": 8161335}, {"filename": "/runtime/ftplugin/deb822sources.vim", "start": 8161335, "end": 8161810}, {"filename": "/runtime/ftplugin/debchangelog.vim", "start": 8161810, "end": 8173809}, {"filename": "/runtime/ftplugin/debcontrol.vim", "start": 8173809, "end": 8175729}, {"filename": "/runtime/ftplugin/debsources.vim", "start": 8175729, "end": 8176201}, {"filename": "/runtime/ftplugin/denyhosts.vim", "start": 8176201, "end": 8176584}, {"filename": "/runtime/ftplugin/desktop.vim", "start": 8176584, "end": 8176966}, {"filename": "/runtime/ftplugin/dictconf.vim", "start": 8176966, "end": 8177400}, {"filename": "/runtime/ftplugin/dictdconf.vim", "start": 8177400, "end": 8177835}, {"filename": "/runtime/ftplugin/diff.vim", "start": 8177835, "end": 8178687}, {"filename": "/runtime/ftplugin/dircolors.vim", "start": 8178687, "end": 8179118}, {"filename": "/runtime/ftplugin/dnsmasq.vim", "start": 8179118, "end": 8179337}, {"filename": "/runtime/ftplugin/docbk.vim", "start": 8179337, "end": 8179907}, {"filename": "/runtime/ftplugin/dockerfile.vim", "start": 8179907, "end": 8180294}, {"filename": "/runtime/ftplugin/dosbatch.vim", "start": 8180294, "end": 8181704}, {"filename": "/runtime/ftplugin/dosini.vim", "start": 8181704, "end": 8182242}, {"filename": "/runtime/ftplugin/dot.lua", "start": 8182242, "end": 8182352}, {"filename": "/runtime/ftplugin/dtd.vim", "start": 8182352, "end": 8183706}, {"filename": "/runtime/ftplugin/dtrace.vim", "start": 8183706, "end": 8184944}, {"filename": "/runtime/ftplugin/dts.vim", "start": 8184944, "end": 8185479}, {"filename": "/runtime/ftplugin/dune.vim", "start": 8185479, "end": 8186152}, {"filename": "/runtime/ftplugin/editorconfig.vim", "start": 8186152, "end": 8186465}, {"filename": "/runtime/ftplugin/eiffel.vim", "start": 8186465, "end": 8190646}, {"filename": "/runtime/ftplugin/elinks.vim", "start": 8190646, "end": 8191082}, {"filename": "/runtime/ftplugin/elixir.vim", "start": 8191082, "end": 8192240}, {"filename": "/runtime/ftplugin/elm.vim", "start": 8192240, "end": 8192609}, {"filename": "/runtime/ftplugin/env.vim", "start": 8192609, "end": 8193015}, {"filename": "/runtime/ftplugin/erlang.vim", "start": 8193015, "end": 8196359}, {"filename": "/runtime/ftplugin/eruby.vim", "start": 8196359, "end": 8201377}, {"filename": "/runtime/ftplugin/eterm.vim", "start": 8201377, "end": 8201847}, {"filename": "/runtime/ftplugin/expect.vim", "start": 8201847, "end": 8202451}, {"filename": "/runtime/ftplugin/exports.vim", "start": 8202451, "end": 8202792}, {"filename": "/runtime/ftplugin/falcon.vim", "start": 8202792, "end": 8204476}, {"filename": "/runtime/ftplugin/faust.lua", "start": 8204476, "end": 8204586}, {"filename": "/runtime/ftplugin/fennel.vim", "start": 8204586, "end": 8205363}, {"filename": "/runtime/ftplugin/fetchmail.vim", "start": 8205363, "end": 8205760}, {"filename": "/runtime/ftplugin/fga.vim", "start": 8205760, "end": 8206017}, {"filename": "/runtime/ftplugin/fish.vim", "start": 8206017, "end": 8207191}, {"filename": "/runtime/ftplugin/flexwiki.vim", "start": 8207191, "end": 8209067}, {"filename": "/runtime/ftplugin/forth.vim", "start": 8209067, "end": 8211098}, {"filename": "/runtime/ftplugin/fortran.vim", "start": 8211098, "end": 8217042}, {"filename": "/runtime/ftplugin/fpcmake.vim", "start": 8217042, "end": 8217721}, {"filename": "/runtime/ftplugin/framescript.vim", "start": 8217721, "end": 8218501}, {"filename": "/runtime/ftplugin/freebasic.vim", "start": 8218501, "end": 8221188}, {"filename": "/runtime/ftplugin/fstab.vim", "start": 8221188, "end": 8221907}, {"filename": "/runtime/ftplugin/fvwm.vim", "start": 8221907, "end": 8222297}, {"filename": "/runtime/ftplugin/gdb.vim", "start": 8222297, "end": 8224029}, {"filename": "/runtime/ftplugin/gdscript.vim", "start": 8224029, "end": 8226275}, {"filename": "/runtime/ftplugin/gdshader.vim", "start": 8226275, "end": 8226785}, {"filename": "/runtime/ftplugin/gel.vim", "start": 8226785, "end": 8227260}, {"filename": "/runtime/ftplugin/ghostty.vim", "start": 8227260, "end": 8227474}, {"filename": "/runtime/ftplugin/git.vim", "start": 8227474, "end": 8227779}, {"filename": "/runtime/ftplugin/gitattributes.vim", "start": 8227779, "end": 8228057}, {"filename": "/runtime/ftplugin/gitcommit.vim", "start": 8228057, "end": 8230135}, {"filename": "/runtime/ftplugin/gitconfig.vim", "start": 8230135, "end": 8230511}, {"filename": "/runtime/ftplugin/gitignore.vim", "start": 8230511, "end": 8230980}, {"filename": "/runtime/ftplugin/gitrebase.vim", "start": 8230980, "end": 8232768}, {"filename": "/runtime/ftplugin/gitrevlist.vim", "start": 8232768, "end": 8233185}, {"filename": "/runtime/ftplugin/gitsendemail.vim", "start": 8233185, "end": 8233350}, {"filename": "/runtime/ftplugin/gleam.vim", "start": 8233350, "end": 8234391}, {"filename": "/runtime/ftplugin/glsl.lua", "start": 8234391, "end": 8234501}, {"filename": "/runtime/ftplugin/go.vim", "start": 8234501, "end": 8237488}, {"filename": "/runtime/ftplugin/goaccess.vim", "start": 8237488, "end": 8237763}, {"filename": "/runtime/ftplugin/gomod.vim", "start": 8237763, "end": 8238147}, {"filename": "/runtime/ftplugin/gpg.vim", "start": 8238147, "end": 8239103}, {"filename": "/runtime/ftplugin/gprof.vim", "start": 8239103, "end": 8240223}, {"filename": "/runtime/ftplugin/graphql.vim", "start": 8240223, "end": 8240938}, {"filename": "/runtime/ftplugin/groff.vim", "start": 8240938, "end": 8241399}, {"filename": "/runtime/ftplugin/groovy.vim", "start": 8241399, "end": 8241814}, {"filename": "/runtime/ftplugin/group.vim", "start": 8241814, "end": 8242239}, {"filename": "/runtime/ftplugin/grub.vim", "start": 8242239, "end": 8242673}, {"filename": "/runtime/ftplugin/gyp.vim", "start": 8242673, "end": 8242984}, {"filename": "/runtime/ftplugin/haml.vim", "start": 8242984, "end": 8245143}, {"filename": "/runtime/ftplugin/hamster.vim", "start": 8245143, "end": 8247267}, {"filename": "/runtime/ftplugin/hare.vim", "start": 8247267, "end": 8248827}, {"filename": "/runtime/ftplugin/haredoc.vim", "start": 8248827, "end": 8250019}, {"filename": "/runtime/ftplugin/haskell.vim", "start": 8250019, "end": 8251039}, {"filename": "/runtime/ftplugin/hcl.vim", "start": 8251039, "end": 8251224}, {"filename": "/runtime/ftplugin/heex.vim", "start": 8251224, "end": 8252360}, {"filename": "/runtime/ftplugin/help.lua", "start": 8252360, "end": 8258333}, {"filename": "/runtime/ftplugin/help.vim", "start": 8258333, "end": 8259737}, {"filename": "/runtime/ftplugin/hgcommit.vim", "start": 8259737, "end": 8260233}, {"filename": "/runtime/ftplugin/hip.vim", "start": 8260233, "end": 8260509}, {"filename": "/runtime/ftplugin/hlsl.vim", "start": 8260509, "end": 8260960}, {"filename": "/runtime/ftplugin/hlsplaylist.vim", "start": 8260960, "end": 8261809}, {"filename": "/runtime/ftplugin/hog.vim", "start": 8261809, "end": 8263263}, {"filename": "/runtime/ftplugin/hostconf.vim", "start": 8263263, "end": 8263646}, {"filename": "/runtime/ftplugin/hostsaccess.vim", "start": 8263646, "end": 8264082}, {"filename": "/runtime/ftplugin/html.vim", "start": 8264082, "end": 8267328}, {"filename": "/runtime/ftplugin/htmlangular.vim", "start": 8267328, "end": 8267634}, {"filename": "/runtime/ftplugin/htmldjango.vim", "start": 8267634, "end": 8267981}, {"filename": "/runtime/ftplugin/http.vim", "start": 8267981, "end": 8268239}, {"filename": "/runtime/ftplugin/hurl.vim", "start": 8268239, "end": 8268516}, {"filename": "/runtime/ftplugin/hyprlang.vim", "start": 8268516, "end": 8268773}, {"filename": "/runtime/ftplugin/i3config.vim", "start": 8268773, "end": 8269106}, {"filename": "/runtime/ftplugin/icalendar.vim", "start": 8269106, "end": 8270603}, {"filename": "/runtime/ftplugin/icon.vim", "start": 8270603, "end": 8271673}, {"filename": "/runtime/ftplugin/idris2.vim", "start": 8271673, "end": 8272839}, {"filename": "/runtime/ftplugin/indent.lua", "start": 8272839, "end": 8272949}, {"filename": "/runtime/ftplugin/indent.vim", "start": 8272949, "end": 8273515}, {"filename": "/runtime/ftplugin/initex.vim", "start": 8273515, "end": 8274607}, {"filename": "/runtime/ftplugin/ipkg.vim", "start": 8274607, "end": 8275152}, {"filename": "/runtime/ftplugin/ishd.vim", "start": 8275152, "end": 8276912}, {"filename": "/runtime/ftplugin/j.vim", "start": 8276912, "end": 8280576}, {"filename": "/runtime/ftplugin/java.vim", "start": 8280576, "end": 8294722}, {"filename": "/runtime/ftplugin/javacc.vim", "start": 8294722, "end": 8295285}, {"filename": "/runtime/ftplugin/javascript.vim", "start": 8295285, "end": 8298060}, {"filename": "/runtime/ftplugin/javascriptreact.vim", "start": 8298060, "end": 8298312}, {"filename": "/runtime/ftplugin/jinja.vim", "start": 8298312, "end": 8298550}, {"filename": "/runtime/ftplugin/jjdescription.vim", "start": 8298550, "end": 8299188}, {"filename": "/runtime/ftplugin/jproperties.vim", "start": 8299188, "end": 8299480}, {"filename": "/runtime/ftplugin/jq.vim", "start": 8299480, "end": 8300037}, {"filename": "/runtime/ftplugin/json.vim", "start": 8300037, "end": 8300397}, {"filename": "/runtime/ftplugin/json5.vim", "start": 8300397, "end": 8301212}, {"filename": "/runtime/ftplugin/jsonc.vim", "start": 8301212, "end": 8301985}, {"filename": "/runtime/ftplugin/jsonld.vim", "start": 8301985, "end": 8302185}, {"filename": "/runtime/ftplugin/jsonnet.vim", "start": 8302185, "end": 8302616}, {"filename": "/runtime/ftplugin/jsp.vim", "start": 8302616, "end": 8304814}, {"filename": "/runtime/ftplugin/julia.vim", "start": 8304814, "end": 8308564}, {"filename": "/runtime/ftplugin/just.vim", "start": 8308564, "end": 8309111}, {"filename": "/runtime/ftplugin/karel.vim", "start": 8309111, "end": 8309507}, {"filename": "/runtime/ftplugin/kconfig.vim", "start": 8309507, "end": 8310194}, {"filename": "/runtime/ftplugin/kdl.vim", "start": 8310194, "end": 8310603}, {"filename": "/runtime/ftplugin/kerml.vim", "start": 8310603, "end": 8311460}, {"filename": "/runtime/ftplugin/kitty.vim", "start": 8311460, "end": 8311763}, {"filename": "/runtime/ftplugin/kivy.vim", "start": 8311763, "end": 8312004}, {"filename": "/runtime/ftplugin/kotlin.vim", "start": 8312004, "end": 8313075}, {"filename": "/runtime/ftplugin/kwt.vim", "start": 8313075, "end": 8314113}, {"filename": "/runtime/ftplugin/lc.vim", "start": 8314113, "end": 8314373}, {"filename": "/runtime/ftplugin/ld.vim", "start": 8314373, "end": 8314938}, {"filename": "/runtime/ftplugin/ldapconf.vim", "start": 8314938, "end": 8315224}, {"filename": "/runtime/ftplugin/leex.vim", "start": 8315224, "end": 8315553}, {"filename": "/runtime/ftplugin/leo.vim", "start": 8315553, "end": 8315812}, {"filename": "/runtime/ftplugin/less.vim", "start": 8315812, "end": 8316314}, {"filename": "/runtime/ftplugin/lex.vim", "start": 8316314, "end": 8316636}, {"filename": "/runtime/ftplugin/lf.vim", "start": 8316636, "end": 8317160}, {"filename": "/runtime/ftplugin/lftp.vim", "start": 8317160, "end": 8317594}, {"filename": "/runtime/ftplugin/libao.vim", "start": 8317594, "end": 8318034}, {"filename": "/runtime/ftplugin/limits.vim", "start": 8318034, "end": 8318470}, {"filename": "/runtime/ftplugin/liquid.vim", "start": 8318470, "end": 8320493}, {"filename": "/runtime/ftplugin/lisp.vim", "start": 8320493, "end": 8321338}, {"filename": "/runtime/ftplugin/livebook.vim", "start": 8321338, "end": 8321628}, {"filename": "/runtime/ftplugin/llvm.vim", "start": 8321628, "end": 8321914}, {"filename": "/runtime/ftplugin/lnk.vim", "start": 8321914, "end": 8322406}, {"filename": "/runtime/ftplugin/lnkmap.vim", "start": 8322406, "end": 8322804}, {"filename": "/runtime/ftplugin/logcheck.vim", "start": 8322804, "end": 8323255}, {"filename": "/runtime/ftplugin/loginaccess.vim", "start": 8323255, "end": 8323697}, {"filename": "/runtime/ftplugin/logindefs.vim", "start": 8323697, "end": 8324137}, {"filename": "/runtime/ftplugin/logtalk.dict", "start": 8324137, "end": 8327363}, {"filename": "/runtime/ftplugin/logtalk.vim", "start": 8327363, "end": 8327779}, {"filename": "/runtime/ftplugin/lprolog.vim", "start": 8327779, "end": 8329713}, {"filename": "/runtime/ftplugin/lua.lua", "start": 8329713, "end": 8330081}, {"filename": "/runtime/ftplugin/lua.vim", "start": 8330081, "end": 8332554}, {"filename": "/runtime/ftplugin/luau.vim", "start": 8332554, "end": 8338901}, {"filename": "/runtime/ftplugin/lynx.vim", "start": 8338901, "end": 8339718}, {"filename": "/runtime/ftplugin/m17ndb.vim", "start": 8339718, "end": 8340134}, {"filename": "/runtime/ftplugin/m3build.vim", "start": 8340134, "end": 8340775}, {"filename": "/runtime/ftplugin/m3quake.vim", "start": 8340775, "end": 8341824}, {"filename": "/runtime/ftplugin/m4.vim", "start": 8341824, "end": 8342250}, {"filename": "/runtime/ftplugin/mail.vim", "start": 8342250, "end": 8343856}, {"filename": "/runtime/ftplugin/mailaliases.vim", "start": 8343856, "end": 8344239}, {"filename": "/runtime/ftplugin/mailcap.vim", "start": 8344239, "end": 8344673}, {"filename": "/runtime/ftplugin/make.vim", "start": 8344673, "end": 8345798}, {"filename": "/runtime/ftplugin/man.vim", "start": 8345798, "end": 8347155}, {"filename": "/runtime/ftplugin/manconf.vim", "start": 8347155, "end": 8347599}, {"filename": "/runtime/ftplugin/markdown.lua", "start": 8347599, "end": 8348322}, {"filename": "/runtime/ftplugin/markdown.vim", "start": 8348322, "end": 8351428}, {"filename": "/runtime/ftplugin/masm.vim", "start": 8351428, "end": 8352532}, {"filename": "/runtime/ftplugin/matlab.vim", "start": 8352532, "end": 8353367}, {"filename": "/runtime/ftplugin/mbsync.vim", "start": 8353367, "end": 8353667}, {"filename": "/runtime/ftplugin/mediawiki.vim", "start": 8353667, "end": 8355385}, {"filename": "/runtime/ftplugin/mermaid.vim", "start": 8355385, "end": 8357322}, {"filename": "/runtime/ftplugin/meson.vim", "start": 8357322, "end": 8358720}, {"filename": "/runtime/ftplugin/mf.vim", "start": 8358720, "end": 8361969}, {"filename": "/runtime/ftplugin/mlir.vim", "start": 8361969, "end": 8362187}, {"filename": "/runtime/ftplugin/mma.vim", "start": 8362187, "end": 8362623}, {"filename": "/runtime/ftplugin/modconf.vim", "start": 8362623, "end": 8363642}, {"filename": "/runtime/ftplugin/modula2.vim", "start": 8363642, "end": 8365219}, {"filename": "/runtime/ftplugin/modula3.vim", "start": 8365219, "end": 8366604}, {"filename": "/runtime/ftplugin/mojo.vim", "start": 8366604, "end": 8367975}, {"filename": "/runtime/ftplugin/mp.vim", "start": 8367975, "end": 8371687}, {"filename": "/runtime/ftplugin/mplayerconf.vim", "start": 8371687, "end": 8372159}, {"filename": "/runtime/ftplugin/mrxvtrc.vim", "start": 8372159, "end": 8372957}, {"filename": "/runtime/ftplugin/msmessages.vim", "start": 8372957, "end": 8374270}, {"filename": "/runtime/ftplugin/mss.vim", "start": 8374270, "end": 8374737}, {"filename": "/runtime/ftplugin/muttrc.vim", "start": 8374737, "end": 8375735}, {"filename": "/runtime/ftplugin/mysql.vim", "start": 8375735, "end": 8375918}, {"filename": "/runtime/ftplugin/nanorc.vim", "start": 8375918, "end": 8376365}, {"filename": "/runtime/ftplugin/nasm.vim", "start": 8376365, "end": 8376945}, {"filename": "/runtime/ftplugin/neomuttrc.vim", "start": 8376945, "end": 8377477}, {"filename": "/runtime/ftplugin/netrc.vim", "start": 8377477, "end": 8377957}, {"filename": "/runtime/ftplugin/nginx.vim", "start": 8377957, "end": 8378208}, {"filename": "/runtime/ftplugin/nickel.vim", "start": 8378208, "end": 8378468}, {"filename": "/runtime/ftplugin/nim.vim", "start": 8378468, "end": 8378750}, {"filename": "/runtime/ftplugin/nix.vim", "start": 8378750, "end": 8379329}, {"filename": "/runtime/ftplugin/nroff.vim", "start": 8379329, "end": 8380902}, {"filename": "/runtime/ftplugin/nsis.vim", "start": 8380902, "end": 8382242}, {"filename": "/runtime/ftplugin/nu.vim", "start": 8382242, "end": 8382640}, {"filename": "/runtime/ftplugin/nvim-pack.lua", "start": 8382640, "end": 8385351}, {"filename": "/runtime/ftplugin/objc.lua", "start": 8385351, "end": 8385461}, {"filename": "/runtime/ftplugin/objc.vim", "start": 8385461, "end": 8385843}, {"filename": "/runtime/ftplugin/objcpp.vim", "start": 8385843, "end": 8385878}, {"filename": "/runtime/ftplugin/objdump.vim", "start": 8385878, "end": 8386149}, {"filename": "/runtime/ftplugin/obse.vim", "start": 8386149, "end": 8387872}, {"filename": "/runtime/ftplugin/ocaml.vim", "start": 8387872, "end": 8411762}, {"filename": "/runtime/ftplugin/occam.vim", "start": 8411762, "end": 8413608}, {"filename": "/runtime/ftplugin/octave.vim", "start": 8413608, "end": 8415908}, {"filename": "/runtime/ftplugin/odin.vim", "start": 8415908, "end": 8416561}, {"filename": "/runtime/ftplugin/ondir.vim", "start": 8416561, "end": 8416939}, {"filename": "/runtime/ftplugin/opencl.vim", "start": 8416939, "end": 8417301}, {"filename": "/runtime/ftplugin/openscad.vim", "start": 8417301, "end": 8417644}, {"filename": "/runtime/ftplugin/openvpn.vim", "start": 8417644, "end": 8418023}, {"filename": "/runtime/ftplugin/org.vim", "start": 8418023, "end": 8419091}, {"filename": "/runtime/ftplugin/pamconf.vim", "start": 8419091, "end": 8419524}, {"filename": "/runtime/ftplugin/pascal.vim", "start": 8419524, "end": 8421242}, {"filename": "/runtime/ftplugin/passwd.vim", "start": 8421242, "end": 8421666}, {"filename": "/runtime/ftplugin/pbtxt.vim", "start": 8421666, "end": 8422144}, {"filename": "/runtime/ftplugin/pdf.vim", "start": 8422144, "end": 8424829}, {"filename": "/runtime/ftplugin/perl.vim", "start": 8424829, "end": 8428959}, {"filename": "/runtime/ftplugin/php.vim", "start": 8428959, "end": 8434538}, {"filename": "/runtime/ftplugin/pinfo.vim", "start": 8434538, "end": 8434973}, {"filename": "/runtime/ftplugin/pkl.vim", "start": 8434973, "end": 8435374}, {"filename": "/runtime/ftplugin/plaintex.vim", "start": 8435374, "end": 8436517}, {"filename": "/runtime/ftplugin/plsql.vim", "start": 8436517, "end": 8437150}, {"filename": "/runtime/ftplugin/pod.vim", "start": 8437150, "end": 8439634}, {"filename": "/runtime/ftplugin/poefilter.vim", "start": 8439634, "end": 8439917}, {"filename": "/runtime/ftplugin/poke.vim", "start": 8439917, "end": 8440770}, {"filename": "/runtime/ftplugin/postscr.vim", "start": 8440770, "end": 8442050}, {"filename": "/runtime/ftplugin/pq.vim", "start": 8442050, "end": 8442380}, {"filename": "/runtime/ftplugin/prisma.vim", "start": 8442380, "end": 8442647}, {"filename": "/runtime/ftplugin/procmail.vim", "start": 8442647, "end": 8443126}, {"filename": "/runtime/ftplugin/prolog.vim", "start": 8443126, "end": 8443566}, {"filename": "/runtime/ftplugin/proto.vim", "start": 8443566, "end": 8443962}, {"filename": "/runtime/ftplugin/protocols.vim", "start": 8443962, "end": 8444419}, {"filename": "/runtime/ftplugin/ps1.vim", "start": 8444419, "end": 8447102}, {"filename": "/runtime/ftplugin/ps1xml.vim", "start": 8447102, "end": 8448400}, {"filename": "/runtime/ftplugin/ptx.vim", "start": 8448400, "end": 8448848}, {"filename": "/runtime/ftplugin/purescript.vim", "start": 8448848, "end": 8449146}, {"filename": "/runtime/ftplugin/pymanifest.vim", "start": 8449146, "end": 8449423}, {"filename": "/runtime/ftplugin/pyrex.vim", "start": 8449423, "end": 8450374}, {"filename": "/runtime/ftplugin/python.vim", "start": 8450374, "end": 8458785}, {"filename": "/runtime/ftplugin/qb64.vim", "start": 8458785, "end": 8459380}, {"filename": "/runtime/ftplugin/qf.vim", "start": 8459380, "end": 8459888}, {"filename": "/runtime/ftplugin/qml.vim", "start": 8459888, "end": 8460959}, {"filename": "/runtime/ftplugin/quake.vim", "start": 8460959, "end": 8461398}, {"filename": "/runtime/ftplugin/quarto.vim", "start": 8461398, "end": 8461467}, {"filename": "/runtime/ftplugin/query.lua", "start": 8461467, "end": 8462505}, {"filename": "/runtime/ftplugin/r.vim", "start": 8462505, "end": 8463576}, {"filename": "/runtime/ftplugin/racc.vim", "start": 8463576, "end": 8464025}, {"filename": "/runtime/ftplugin/racket.vim", "start": 8464025, "end": 8466938}, {"filename": "/runtime/ftplugin/radvd.vim", "start": 8466938, "end": 8467241}, {"filename": "/runtime/ftplugin/raku.vim", "start": 8467241, "end": 8471442}, {"filename": "/runtime/ftplugin/rasi.vim", "start": 8471442, "end": 8471911}, {"filename": "/runtime/ftplugin/readline.vim", "start": 8471911, "end": 8473558}, {"filename": "/runtime/ftplugin/registry.vim", "start": 8473558, "end": 8474502}, {"filename": "/runtime/ftplugin/remind.vim", "start": 8474502, "end": 8474893}, {"filename": "/runtime/ftplugin/requirements.vim", "start": 8474893, "end": 8476643}, {"filename": "/runtime/ftplugin/rescript.vim", "start": 8476643, "end": 8476948}, {"filename": "/runtime/ftplugin/reva.vim", "start": 8476948, "end": 8477753}, {"filename": "/runtime/ftplugin/rhelp.vim", "start": 8477753, "end": 8478705}, {"filename": "/runtime/ftplugin/rmd.vim", "start": 8478705, "end": 8481322}, {"filename": "/runtime/ftplugin/rnc.vim", "start": 8481322, "end": 8481753}, {"filename": "/runtime/ftplugin/rnoweb.vim", "start": 8481753, "end": 8483553}, {"filename": "/runtime/ftplugin/robot.vim", "start": 8483553, "end": 8483959}, {"filename": "/runtime/ftplugin/roc.vim", "start": 8483959, "end": 8484259}, {"filename": "/runtime/ftplugin/routeros.vim", "start": 8484259, "end": 8485079}, {"filename": "/runtime/ftplugin/rpl.vim", "start": 8485079, "end": 8485745}, {"filename": "/runtime/ftplugin/rrst.vim", "start": 8485745, "end": 8487603}, {"filename": "/runtime/ftplugin/rst.vim", "start": 8487603, "end": 8489278}, {"filename": "/runtime/ftplugin/ruby.vim", "start": 8489278, "end": 8507376}, {"filename": "/runtime/ftplugin/rust.vim", "start": 8507376, "end": 8516884}, {"filename": "/runtime/ftplugin/samba.vim", "start": 8516884, "end": 8517229}, {"filename": "/runtime/ftplugin/sass.vim", "start": 8517229, "end": 8518453}, {"filename": "/runtime/ftplugin/sbt.vim", "start": 8518453, "end": 8518807}, {"filename": "/runtime/ftplugin/scala.vim", "start": 8518807, "end": 8520280}, {"filename": "/runtime/ftplugin/scdoc.vim", "start": 8520280, "end": 8520893}, {"filename": "/runtime/ftplugin/scheme.vim", "start": 8520893, "end": 8522575}, {"filename": "/runtime/ftplugin/screen.vim", "start": 8522575, "end": 8523011}, {"filename": "/runtime/ftplugin/scss.vim", "start": 8523011, "end": 8523258}, {"filename": "/runtime/ftplugin/sed.vim", "start": 8523258, "end": 8524013}, {"filename": "/runtime/ftplugin/sensors.vim", "start": 8524013, "end": 8524468}, {"filename": "/runtime/ftplugin/services.vim", "start": 8524468, "end": 8524920}, {"filename": "/runtime/ftplugin/setserial.vim", "start": 8524920, "end": 8525359}, {"filename": "/runtime/ftplugin/sexplib.vim", "start": 8525359, "end": 8525810}, {"filename": "/runtime/ftplugin/sgml.vim", "start": 8525810, "end": 8527183}, {"filename": "/runtime/ftplugin/sh.vim", "start": 8527183, "end": 8529976}, {"filename": "/runtime/ftplugin/shada.vim", "start": 8529976, "end": 8530390}, {"filename": "/runtime/ftplugin/shaderslang.vim", "start": 8530390, "end": 8531977}, {"filename": "/runtime/ftplugin/sieve.vim", "start": 8531977, "end": 8532786}, {"filename": "/runtime/ftplugin/skhd.vim", "start": 8532786, "end": 8533236}, {"filename": "/runtime/ftplugin/slint.vim", "start": 8533236, "end": 8533634}, {"filename": "/runtime/ftplugin/slpconf.vim", "start": 8533634, "end": 8534111}, {"filename": "/runtime/ftplugin/slpreg.vim", "start": 8534111, "end": 8534587}, {"filename": "/runtime/ftplugin/slpspi.vim", "start": 8534587, "end": 8535054}, {"filename": "/runtime/ftplugin/sml.vim", "start": 8535054, "end": 8536205}, {"filename": "/runtime/ftplugin/snakemake.vim", "start": 8536205, "end": 8536468}, {"filename": "/runtime/ftplugin/solidity.vim", "start": 8536468, "end": 8536862}, {"filename": "/runtime/ftplugin/solution.vim", "start": 8536862, "end": 8537921}, {"filename": "/runtime/ftplugin/soy.vim", "start": 8537921, "end": 8538273}, {"filename": "/runtime/ftplugin/spajson.vim", "start": 8538273, "end": 8538583}, {"filename": "/runtime/ftplugin/spec.vim", "start": 8538583, "end": 8544406}, {"filename": "/runtime/ftplugin/sql.vim", "start": 8544406, "end": 8566030}, {"filename": "/runtime/ftplugin/squirrel.vim", "start": 8566030, "end": 8566445}, {"filename": "/runtime/ftplugin/ssa.vim", "start": 8566445, "end": 8566733}, {"filename": "/runtime/ftplugin/sshconfig.vim", "start": 8566733, "end": 8567674}, {"filename": "/runtime/ftplugin/sshdconfig.vim", "start": 8567674, "end": 8567922}, {"filename": "/runtime/ftplugin/stata.lua", "start": 8567922, "end": 8568032}, {"filename": "/runtime/ftplugin/stylus.vim", "start": 8568032, "end": 8569444}, {"filename": "/runtime/ftplugin/sudoers.vim", "start": 8569444, "end": 8570353}, {"filename": "/runtime/ftplugin/supercollider.lua", "start": 8570353, "end": 8570463}, {"filename": "/runtime/ftplugin/svelte.vim", "start": 8570463, "end": 8571682}, {"filename": "/runtime/ftplugin/svg.vim", "start": 8571682, "end": 8573045}, {"filename": "/runtime/ftplugin/sway.vim", "start": 8573045, "end": 8573371}, {"filename": "/runtime/ftplugin/swayconfig.vim", "start": 8573371, "end": 8573708}, {"filename": "/runtime/ftplugin/swift.lua", "start": 8573708, "end": 8573818}, {"filename": "/runtime/ftplugin/swift.vim", "start": 8573818, "end": 8574710}, {"filename": "/runtime/ftplugin/swiftgyb.vim", "start": 8574710, "end": 8575139}, {"filename": "/runtime/ftplugin/swig.vim", "start": 8575139, "end": 8575644}, {"filename": "/runtime/ftplugin/sysctl.vim", "start": 8575644, "end": 8576097}, {"filename": "/runtime/ftplugin/sysml.vim", "start": 8576097, "end": 8576437}, {"filename": "/runtime/ftplugin/systemd.vim", "start": 8576437, "end": 8577739}, {"filename": "/runtime/ftplugin/systemverilog.vim", "start": 8577739, "end": 8579101}, {"filename": "/runtime/ftplugin/tap.vim", "start": 8579101, "end": 8579948}, {"filename": "/runtime/ftplugin/tcl.vim", "start": 8579948, "end": 8581249}, {"filename": "/runtime/ftplugin/tcsh.vim", "start": 8581249, "end": 8582423}, {"filename": "/runtime/ftplugin/tera.vim", "start": 8582423, "end": 8583743}, {"filename": "/runtime/ftplugin/terminfo.vim", "start": 8583743, "end": 8584173}, {"filename": "/runtime/ftplugin/terraform.vim", "start": 8584173, "end": 8584478}, {"filename": "/runtime/ftplugin/tex.vim", "start": 8584478, "end": 8586113}, {"filename": "/runtime/ftplugin/text.vim", "start": 8586113, "end": 8586611}, {"filename": "/runtime/ftplugin/tf.vim", "start": 8586611, "end": 8586882}, {"filename": "/runtime/ftplugin/thrift.vim", "start": 8586882, "end": 8587424}, {"filename": "/runtime/ftplugin/tiasm.vim", "start": 8587424, "end": 8588217}, {"filename": "/runtime/ftplugin/tidy.vim", "start": 8588217, "end": 8589187}, {"filename": "/runtime/ftplugin/tmux.vim", "start": 8589187, "end": 8589559}, {"filename": "/runtime/ftplugin/tolk.vim", "start": 8589559, "end": 8590218}, {"filename": "/runtime/ftplugin/toml.vim", "start": 8590218, "end": 8590718}, {"filename": "/runtime/ftplugin/treetop.vim", "start": 8590718, "end": 8591134}, {"filename": "/runtime/ftplugin/tt2html.vim", "start": 8591134, "end": 8591699}, {"filename": "/runtime/ftplugin/tutor.vim", "start": 8591699, "end": 8592713}, {"filename": "/runtime/ftplugin/twig.vim", "start": 8592713, "end": 8593011}, {"filename": "/runtime/ftplugin/typescript.vim", "start": 8593011, "end": 8594415}, {"filename": "/runtime/ftplugin/typescriptreact.vim", "start": 8594415, "end": 8595210}, {"filename": "/runtime/ftplugin/typst.vim", "start": 8595210, "end": 8596855}, {"filename": "/runtime/ftplugin/uc.vim", "start": 8596855, "end": 8597169}, {"filename": "/runtime/ftplugin/uci.vim", "start": 8597169, "end": 8597693}, {"filename": "/runtime/ftplugin/udevconf.vim", "start": 8597693, "end": 8598127}, {"filename": "/runtime/ftplugin/udevperm.vim", "start": 8598127, "end": 8598559}, {"filename": "/runtime/ftplugin/udevrules.vim", "start": 8598559, "end": 8599499}, {"filename": "/runtime/ftplugin/unison.vim", "start": 8599499, "end": 8599813}, {"filename": "/runtime/ftplugin/updatedb.vim", "start": 8599813, "end": 8600256}, {"filename": "/runtime/ftplugin/urlshortcut.vim", "start": 8600256, "end": 8600731}, {"filename": "/runtime/ftplugin/usd.vim", "start": 8600731, "end": 8601110}, {"filename": "/runtime/ftplugin/v.vim", "start": 8601110, "end": 8601504}, {"filename": "/runtime/ftplugin/vb.vim", "start": 8601504, "end": 8605067}, {"filename": "/runtime/ftplugin/vdf.vim", "start": 8605067, "end": 8605378}, {"filename": "/runtime/ftplugin/verilog.vim", "start": 8605378, "end": 8607629}, {"filename": "/runtime/ftplugin/vhdl.vim", "start": 8607629, "end": 8612020}, {"filename": "/runtime/ftplugin/vim.vim", "start": 8612020, "end": 8619056}, {"filename": "/runtime/ftplugin/vroom.vim", "start": 8619056, "end": 8619980}, {"filename": "/runtime/ftplugin/vue.vim", "start": 8619980, "end": 8620926}, {"filename": "/runtime/ftplugin/wat.vim", "start": 8620926, "end": 8621483}, {"filename": "/runtime/ftplugin/wget.vim", "start": 8621483, "end": 8622310}, {"filename": "/runtime/ftplugin/wget2.vim", "start": 8622310, "end": 8623145}, {"filename": "/runtime/ftplugin/wks.vim", "start": 8623145, "end": 8623485}, {"filename": "/runtime/ftplugin/xcompose.vim", "start": 8623485, "end": 8623761}, {"filename": "/runtime/ftplugin/xdefaults.vim", "start": 8623761, "end": 8624301}, {"filename": "/runtime/ftplugin/xf86conf.vim", "start": 8624301, "end": 8624735}, {"filename": "/runtime/ftplugin/xhtml.vim", "start": 8624735, "end": 8626955}, {"filename": "/runtime/ftplugin/xinetd.vim", "start": 8626955, "end": 8627431}, {"filename": "/runtime/ftplugin/xkb.vim", "start": 8627431, "end": 8627734}, {"filename": "/runtime/ftplugin/xml.vim", "start": 8627734, "end": 8629979}, {"filename": "/runtime/ftplugin/xmodmap.vim", "start": 8629979, "end": 8630413}, {"filename": "/runtime/ftplugin/xs.lua", "start": 8630413, "end": 8630523}, {"filename": "/runtime/ftplugin/xs.vim", "start": 8630523, "end": 8631092}, {"filename": "/runtime/ftplugin/xsd.vim", "start": 8631092, "end": 8632427}, {"filename": "/runtime/ftplugin/xslt.vim", "start": 8632427, "end": 8632963}, {"filename": "/runtime/ftplugin/yacc.vim", "start": 8632963, "end": 8633373}, {"filename": "/runtime/ftplugin/yaml.vim", "start": 8633373, "end": 8634576}, {"filename": "/runtime/ftplugin/yara.vim", "start": 8634576, "end": 8635225}, {"filename": "/runtime/ftplugin/zathurarc.vim", "start": 8635225, "end": 8635839}, {"filename": "/runtime/ftplugin/zig.vim", "start": 8635839, "end": 8637363}, {"filename": "/runtime/ftplugin/zimbu.vim", "start": 8637363, "end": 8644976}, {"filename": "/runtime/ftplugin/zsh.vim", "start": 8644976, "end": 8646536}, {"filename": "/runtime/ftplugof.vim", "start": 8646536, "end": 8646939}, {"filename": "/runtime/gen_runtime.zig", "start": 8646939, "end": 8648460}, {"filename": "/runtime/indent.vim", "start": 8648460, "end": 8649372}, {"filename": "/runtime/indent/README.txt", "start": 8649372, "end": 8651106}, {"filename": "/runtime/indent/aap.vim", "start": 8651106, "end": 8651437}, {"filename": "/runtime/indent/ada.vim", "start": 8651437, "end": 8662761}, {"filename": "/runtime/indent/ant.vim", "start": 8662761, "end": 8663051}, {"filename": "/runtime/indent/arduino.vim", "start": 8663051, "end": 8663407}, {"filename": "/runtime/indent/astro.vim", "start": 8663407, "end": 8665841}, {"filename": "/runtime/indent/automake.vim", "start": 8665841, "end": 8666084}, {"filename": "/runtime/indent/awk.vim", "start": 8666084, "end": 8673947}, {"filename": "/runtime/indent/bash.vim", "start": 8673947, "end": 8674337}, {"filename": "/runtime/indent/basic.vim", "start": 8674337, "end": 8674587}, {"filename": "/runtime/indent/beancount.vim", "start": 8674587, "end": 8676071}, {"filename": "/runtime/indent/bib.vim", "start": 8676071, "end": 8676417}, {"filename": "/runtime/indent/bitbake.vim", "start": 8676417, "end": 8677000}, {"filename": "/runtime/indent/bpftrace.vim", "start": 8677000, "end": 8677489}, {"filename": "/runtime/indent/bst.vim", "start": 8677489, "end": 8679396}, {"filename": "/runtime/indent/bzl.vim", "start": 8679396, "end": 8682321}, {"filename": "/runtime/indent/c.vim", "start": 8682321, "end": 8682712}, {"filename": "/runtime/indent/cabal.vim", "start": 8682712, "end": 8685242}, {"filename": "/runtime/indent/cdl.vim", "start": 8685242, "end": 8689648}, {"filename": "/runtime/indent/ch.vim", "start": 8689648, "end": 8690204}, {"filename": "/runtime/indent/chaiscript.vim", "start": 8690204, "end": 8691408}, {"filename": "/runtime/indent/changelog.vim", "start": 8691408, "end": 8691672}, {"filename": "/runtime/indent/chatito.vim", "start": 8691672, "end": 8692403}, {"filename": "/runtime/indent/clojure.vim", "start": 8692403, "end": 8703960}, {"filename": "/runtime/indent/cmake.vim", "start": 8703960, "end": 8707009}, {"filename": "/runtime/indent/cobol.vim", "start": 8707009, "end": 8715841}, {"filename": "/runtime/indent/config.vim", "start": 8715841, "end": 8718063}, {"filename": "/runtime/indent/context.vim", "start": 8718063, "end": 8718815}, {"filename": "/runtime/indent/cpp.vim", "start": 8718815, "end": 8719210}, {"filename": "/runtime/indent/cs.vim", "start": 8719210, "end": 8721138}, {"filename": "/runtime/indent/css.vim", "start": 8721138, "end": 8722951}, {"filename": "/runtime/indent/cucumber.vim", "start": 8722951, "end": 8725911}, {"filename": "/runtime/indent/cuda.vim", "start": 8725911, "end": 8726282}, {"filename": "/runtime/indent/d.vim", "start": 8726282, "end": 8726887}, {"filename": "/runtime/indent/dictconf.vim", "start": 8726887, "end": 8727298}, {"filename": "/runtime/indent/dictdconf.vim", "start": 8727298, "end": 8727712}, {"filename": "/runtime/indent/docbk.vim", "start": 8727712, "end": 8728048}, {"filename": "/runtime/indent/dosbatch.vim", "start": 8728048, "end": 8729430}, {"filename": "/runtime/indent/dtd.vim", "start": 8729430, "end": 8741502}, {"filename": "/runtime/indent/dtrace.vim", "start": 8741502, "end": 8741953}, {"filename": "/runtime/indent/dts.vim", "start": 8741953, "end": 8743688}, {"filename": "/runtime/indent/dune.vim", "start": 8743688, "end": 8744118}, {"filename": "/runtime/indent/dylan.vim", "start": 8744118, "end": 8746929}, {"filename": "/runtime/indent/eiffel.vim", "start": 8746929, "end": 8750246}, {"filename": "/runtime/indent/elm.vim", "start": 8750246, "end": 8753523}, {"filename": "/runtime/indent/erlang.vim", "start": 8753523, "end": 8805478}, {"filename": "/runtime/indent/eruby.vim", "start": 8805478, "end": 8808436}, {"filename": "/runtime/indent/eterm.vim", "start": 8808436, "end": 8809179}, {"filename": "/runtime/indent/expect.vim", "start": 8809179, "end": 8809386}, {"filename": "/runtime/indent/falcon.vim", "start": 8809386, "end": 8823557}, {"filename": "/runtime/indent/fennel.vim", "start": 8823557, "end": 8823830}, {"filename": "/runtime/indent/fish.vim", "start": 8823830, "end": 8826549}, {"filename": "/runtime/indent/fortran.vim", "start": 8826549, "end": 8834455}, {"filename": "/runtime/indent/framescript.vim", "start": 8834455, "end": 8835346}, {"filename": "/runtime/indent/freebasic.vim", "start": 8835346, "end": 8835583}, {"filename": "/runtime/indent/gdscript.vim", "start": 8835583, "end": 8840133}, {"filename": "/runtime/indent/gitconfig.vim", "start": 8840133, "end": 8840974}, {"filename": "/runtime/indent/gitolite.vim", "start": 8840974, "end": 8842295}, {"filename": "/runtime/indent/glsl.vim", "start": 8842295, "end": 8842615}, {"filename": "/runtime/indent/go.vim", "start": 8842615, "end": 8844382}, {"filename": "/runtime/indent/graphql.vim", "start": 8844382, "end": 8847283}, {"filename": "/runtime/indent/gyp.vim", "start": 8847283, "end": 8847452}, {"filename": "/runtime/indent/haml.vim", "start": 8847452, "end": 8849699}, {"filename": "/runtime/indent/hamster.vim", "start": 8849699, "end": 8851391}, {"filename": "/runtime/indent/handlebars.vim", "start": 8851391, "end": 8855355}, {"filename": "/runtime/indent/hare.vim", "start": 8855355, "end": 8859893}, {"filename": "/runtime/indent/hcl.vim", "start": 8859893, "end": 8861118}, {"filename": "/runtime/indent/hip.vim", "start": 8861118, "end": 8861487}, {"filename": "/runtime/indent/hlsl.vim", "start": 8861487, "end": 8861791}, {"filename": "/runtime/indent/hog.vim", "start": 8861791, "end": 8863683}, {"filename": "/runtime/indent/html.vim", "start": 8863683, "end": 8897775}, {"filename": "/runtime/indent/htmldjango.vim", "start": 8897775, "end": 8898048}, {"filename": "/runtime/indent/idlang.vim", "start": 8898048, "end": 8899771}, {"filename": "/runtime/indent/idris2.vim", "start": 8899771, "end": 8904301}, {"filename": "/runtime/indent/ishd.vim", "start": 8904301, "end": 8906178}, {"filename": "/runtime/indent/j.vim", "start": 8906178, "end": 8907990}, {"filename": "/runtime/indent/java.vim", "start": 8907990, "end": 8912277}, {"filename": "/runtime/indent/javascript.vim", "start": 8912277, "end": 8927841}, {"filename": "/runtime/indent/javascriptreact.vim", "start": 8927841, "end": 8927950}, {"filename": "/runtime/indent/json.vim", "start": 8927950, "end": 8932594}, {"filename": "/runtime/indent/json5.vim", "start": 8932594, "end": 8932825}, {"filename": "/runtime/indent/jsonc.vim", "start": 8932825, "end": 8937682}, {"filename": "/runtime/indent/jsonld.vim", "start": 8937682, "end": 8937832}, {"filename": "/runtime/indent/jsp.vim", "start": 8937832, "end": 8938294}, {"filename": "/runtime/indent/julia.vim", "start": 8938294, "end": 8954125}, {"filename": "/runtime/indent/just.vim", "start": 8954125, "end": 8955506}, {"filename": "/runtime/indent/karel.vim", "start": 8955506, "end": 8959519}, {"filename": "/runtime/indent/kdl.vim", "start": 8959519, "end": 8960338}, {"filename": "/runtime/indent/kotlin.vim", "start": 8960338, "end": 8961904}, {"filename": "/runtime/indent/krl.vim", "start": 8961904, "end": 8966260}, {"filename": "/runtime/indent/ld.vim", "start": 8966260, "end": 8968127}, {"filename": "/runtime/indent/less.vim", "start": 8968127, "end": 8968370}, {"filename": "/runtime/indent/lf.vim", "start": 8968370, "end": 8968697}, {"filename": "/runtime/indent/lifelines.vim", "start": 8968697, "end": 8969335}, {"filename": "/runtime/indent/liquid.vim", "start": 8969335, "end": 8971412}, {"filename": "/runtime/indent/lisp.vim", "start": 8971412, "end": 8971761}, {"filename": "/runtime/indent/livebook.vim", "start": 8971761, "end": 8971967}, {"filename": "/runtime/indent/logtalk.vim", "start": 8971967, "end": 8973926}, {"filename": "/runtime/indent/lua.vim", "start": 8973926, "end": 8976296}, {"filename": "/runtime/indent/luau.vim", "start": 8976296, "end": 8980939}, {"filename": "/runtime/indent/m17ndb.vim", "start": 8980939, "end": 8981228}, {"filename": "/runtime/indent/mail.vim", "start": 8981228, "end": 8981613}, {"filename": "/runtime/indent/make.vim", "start": 8981613, "end": 8985508}, {"filename": "/runtime/indent/matlab.vim", "start": 8985508, "end": 8990431}, {"filename": "/runtime/indent/meson.vim", "start": 8990431, "end": 8995750}, {"filename": "/runtime/indent/mf.vim", "start": 8995750, "end": 8995906}, {"filename": "/runtime/indent/mma.vim", "start": 8995906, "end": 8998273}, {"filename": "/runtime/indent/mojo.vim", "start": 8998273, "end": 8998410}, {"filename": "/runtime/indent/mp.vim", "start": 8998410, "end": 9009731}, {"filename": "/runtime/indent/nginx.vim", "start": 9009731, "end": 9011468}, {"filename": "/runtime/indent/nsis.vim", "start": 9011468, "end": 9014774}, {"filename": "/runtime/indent/nu.vim", "start": 9014774, "end": 9016085}, {"filename": "/runtime/indent/objc.vim", "start": 9016085, "end": 9017773}, {"filename": "/runtime/indent/obse.vim", "start": 9017773, "end": 9019210}, {"filename": "/runtime/indent/ocaml.vim", "start": 9019210, "end": 9028497}, {"filename": "/runtime/indent/occam.vim", "start": 9028497, "end": 9033235}, {"filename": "/runtime/indent/odin.vim", "start": 9033235, "end": 9037042}, {"filename": "/runtime/indent/pascal.vim", "start": 9037042, "end": 9042835}, {"filename": "/runtime/indent/perl.vim", "start": 9042835, "end": 9048918}, {"filename": "/runtime/indent/php.vim", "start": 9048918, "end": 9075340}, {"filename": "/runtime/indent/postscr.vim", "start": 9075340, "end": 9077019}, {"filename": "/runtime/indent/pov.vim", "start": 9077019, "end": 9079789}, {"filename": "/runtime/indent/prolog.vim", "start": 9079789, "end": 9081743}, {"filename": "/runtime/indent/proto.vim", "start": 9081743, "end": 9082148}, {"filename": "/runtime/indent/ps1.vim", "start": 9082148, "end": 9082558}, {"filename": "/runtime/indent/pyrex.vim", "start": 9082558, "end": 9082884}, {"filename": "/runtime/indent/python.vim", "start": 9082884, "end": 9083770}, {"filename": "/runtime/indent/qb64.vim", "start": 9083770, "end": 9084002}, {"filename": "/runtime/indent/qml.vim", "start": 9084002, "end": 9085338}, {"filename": "/runtime/indent/quarto.vim", "start": 9085338, "end": 9085361}, {"filename": "/runtime/indent/query.lua", "start": 9085361, "end": 9085468}, {"filename": "/runtime/indent/r.vim", "start": 9085468, "end": 9099893}, {"filename": "/runtime/indent/racket.vim", "start": 9099893, "end": 9103323}, {"filename": "/runtime/indent/raku.vim", "start": 9103323, "end": 9106885}, {"filename": "/runtime/indent/raml.vim", "start": 9106885, "end": 9107170}, {"filename": "/runtime/indent/rapid.vim", "start": 9107170, "end": 9115336}, {"filename": "/runtime/indent/readline.vim", "start": 9115336, "end": 9116122}, {"filename": "/runtime/indent/rhelp.vim", "start": 9116122, "end": 9119147}, {"filename": "/runtime/indent/rmd.vim", "start": 9119147, "end": 9121579}, {"filename": "/runtime/indent/rnoweb.vim", "start": 9121579, "end": 9122831}, {"filename": "/runtime/indent/rpl.vim", "start": 9122831, "end": 9124694}, {"filename": "/runtime/indent/rrst.vim", "start": 9124694, "end": 9126046}, {"filename": "/runtime/indent/rst.vim", "start": 9126046, "end": 9128183}, {"filename": "/runtime/indent/ruby.vim", "start": 9128183, "end": 9159357}, {"filename": "/runtime/indent/rust.vim", "start": 9159357, "end": 9169763}, {"filename": "/runtime/indent/sas.vim", "start": 9169763, "end": 9175072}, {"filename": "/runtime/indent/sass.vim", "start": 9175072, "end": 9175998}, {"filename": "/runtime/indent/scala.vim", "start": 9175998, "end": 9195763}, {"filename": "/runtime/indent/scheme.vim", "start": 9195763, "end": 9196242}, {"filename": "/runtime/indent/scss.vim", "start": 9196242, "end": 9196433}, {"filename": "/runtime/indent/sdl.vim", "start": 9196433, "end": 9199262}, {"filename": "/runtime/indent/sh.vim", "start": 9199262, "end": 9210166}, {"filename": "/runtime/indent/sml.vim", "start": 9210166, "end": 9216764}, {"filename": "/runtime/indent/solidity.vim", "start": 9216764, "end": 9229677}, {"filename": "/runtime/indent/spajson.vim", "start": 9229677, "end": 9229823}, {"filename": "/runtime/indent/sql.vim", "start": 9229823, "end": 9231027}, {"filename": "/runtime/indent/sqlanywhere.vim", "start": 9231027, "end": 9244296}, {"filename": "/runtime/indent/sshconfig.vim", "start": 9244296, "end": 9245092}, {"filename": "/runtime/indent/stylus.vim", "start": 9245092, "end": 9248253}, {"filename": "/runtime/indent/systemverilog.vim", "start": 9248253, "end": 9259057}, {"filename": "/runtime/indent/tcl.vim", "start": 9259057, "end": 9261599}, {"filename": "/runtime/indent/tcsh.vim", "start": 9261599, "end": 9262980}, {"filename": "/runtime/indent/teraterm.vim", "start": 9262980, "end": 9264393}, {"filename": "/runtime/indent/terraform.vim", "start": 9264393, "end": 9264616}, {"filename": "/runtime/indent/tex.vim", "start": 9264616, "end": 9278464}, {"filename": "/runtime/indent/tf.vim", "start": 9278464, "end": 9280068}, {"filename": "/runtime/indent/thrift.vim", "start": 9280068, "end": 9281784}, {"filename": "/runtime/indent/tilde.vim", "start": 9281784, "end": 9282920}, {"filename": "/runtime/indent/treetop.vim", "start": 9282920, "end": 9283705}, {"filename": "/runtime/indent/typescript.vim", "start": 9283705, "end": 9296791}, {"filename": "/runtime/indent/typescriptreact.vim", "start": 9296791, "end": 9296900}, {"filename": "/runtime/indent/typst.vim", "start": 9296900, "end": 9297373}, {"filename": "/runtime/indent/vb.vim", "start": 9297373, "end": 9302174}, {"filename": "/runtime/indent/verilog.vim", "start": 9302174, "end": 9310443}, {"filename": "/runtime/indent/vhdl.vim", "start": 9310443, "end": 9325221}, {"filename": "/runtime/indent/vim.vim", "start": 9325221, "end": 9331972}, {"filename": "/runtime/indent/vroom.vim", "start": 9331972, "end": 9332351}, {"filename": "/runtime/indent/vue.vim", "start": 9332351, "end": 9332736}, {"filename": "/runtime/indent/wat.vim", "start": 9332736, "end": 9333194}, {"filename": "/runtime/indent/xf86conf.vim", "start": 9333194, "end": 9333980}, {"filename": "/runtime/indent/xhtml.vim", "start": 9333980, "end": 9334305}, {"filename": "/runtime/indent/xinetd.vim", "start": 9334305, "end": 9335613}, {"filename": "/runtime/indent/xml.vim", "start": 9335613, "end": 9344383}, {"filename": "/runtime/indent/xsd.vim", "start": 9344383, "end": 9344636}, {"filename": "/runtime/indent/xslt.vim", "start": 9344636, "end": 9344933}, {"filename": "/runtime/indent/yacc.vim", "start": 9344933, "end": 9345791}, {"filename": "/runtime/indent/yaml.vim", "start": 9345791, "end": 9352193}, {"filename": "/runtime/indent/zig.vim", "start": 9352193, "end": 9354332}, {"filename": "/runtime/indent/zimbu.vim", "start": 9354332, "end": 9358345}, {"filename": "/runtime/indent/zsh.vim", "start": 9358345, "end": 9358756}, {"filename": "/runtime/indoff.vim", "start": 9358756, "end": 9359104}, {"filename": "/runtime/keymap/README.txt", "start": 9359104, "end": 9360059}, {"filename": "/runtime/keymap/accents.vim", "start": 9360059, "end": 9360738}, {"filename": "/runtime/keymap/arabic.vim", "start": 9360738, "end": 9360847}, {"filename": "/runtime/keymap/arabic_buckwalter.vim", "start": 9360847, "end": 9363063}, {"filename": "/runtime/keymap/arabic_utf-8.vim", "start": 9363063, "end": 9366069}, {"filename": "/runtime/keymap/armenian-eastern_utf-8.vim", "start": 9366069, "end": 9366962}, {"filename": "/runtime/keymap/armenian-western_utf-8.vim", "start": 9366962, "end": 9367855}, {"filename": "/runtime/keymap/azerbaijani.vim", "start": 9367855, "end": 9368167}, {"filename": "/runtime/keymap/belarusian-jcuken.vim", "start": 9368167, "end": 9370685}, {"filename": "/runtime/keymap/brazilian_portuguese-abnt.vim", "start": 9370685, "end": 9373069}, {"filename": "/runtime/keymap/brazilian_portuguese-compact.vim", "start": 9373069, "end": 9375501}, {"filename": "/runtime/keymap/bulgarian-bds.vim", "start": 9375501, "end": 9382002}, {"filename": "/runtime/keymap/bulgarian-phonetic.vim", "start": 9382002, "end": 9387808}, {"filename": "/runtime/keymap/canfr-win.vim", "start": 9387808, "end": 9388974}, {"filename": "/runtime/keymap/croatian.vim", "start": 9388974, "end": 9389314}, {"filename": "/runtime/keymap/croatian_cp1250.vim", "start": 9389314, "end": 9389866}, {"filename": "/runtime/keymap/croatian_iso-8859-2.vim", "start": 9389866, "end": 9390571}, {"filename": "/runtime/keymap/croatian_utf-8.vim", "start": 9390571, "end": 9391181}, {"filename": "/runtime/keymap/czech.vim", "start": 9391181, "end": 9391216}, {"filename": "/runtime/keymap/czech_utf-8.vim", "start": 9391216, "end": 9391780}, {"filename": "/runtime/keymap/dvorak.vim", "start": 9391780, "end": 9392287}, {"filename": "/runtime/keymap/esperanto.vim", "start": 9392287, "end": 9392326}, {"filename": "/runtime/keymap/esperanto_utf-8.vim", "start": 9392326, "end": 9394728}, {"filename": "/runtime/keymap/french-azerty.vim", "start": 9394728, "end": 9397004}, {"filename": "/runtime/keymap/georgian-qwerty.vim", "start": 9397004, "end": 9397442}, {"filename": "/runtime/keymap/german-qwertz.vim", "start": 9397442, "end": 9399839}, {"filename": "/runtime/keymap/greek.vim", "start": 9399839, "end": 9400073}, {"filename": "/runtime/keymap/greek_cp1253.vim", "start": 9400073, "end": 9402277}, {"filename": "/runtime/keymap/greek_cp737.vim", "start": 9402277, "end": 9404232}, {"filename": "/runtime/keymap/greek_iso-8859-7.vim", "start": 9404232, "end": 9406364}, {"filename": "/runtime/keymap/greek_utf-8.vim", "start": 9406364, "end": 9432664}, {"filename": "/runtime/keymap/hebrew.vim", "start": 9432664, "end": 9432986}, {"filename": "/runtime/keymap/hebrew_cp1255.vim", "start": 9432986, "end": 9434727}, {"filename": "/runtime/keymap/hebrew_iso-8859-8.vim", "start": 9434727, "end": 9435733}, {"filename": "/runtime/keymap/hebrew_utf-8.vim", "start": 9435733, "end": 9439549}, {"filename": "/runtime/keymap/hebrewp.vim", "start": 9439549, "end": 9439874}, {"filename": "/runtime/keymap/hebrewp_cp1255.vim", "start": 9439874, "end": 9441445}, {"filename": "/runtime/keymap/hebrewp_iso-8859-8.vim", "start": 9441445, "end": 9442242}, {"filename": "/runtime/keymap/hebrewp_utf-8.vim", "start": 9442242, "end": 9445905}, {"filename": "/runtime/keymap/kana.vim", "start": 9445905, "end": 9454920}, {"filename": "/runtime/keymap/kazakh-jcuken.vim", "start": 9454920, "end": 9458185}, {"filename": "/runtime/keymap/korean-dubeolsik_utf-8.vim", "start": 9458185, "end": 9556650}, {"filename": "/runtime/keymap/korean.vim", "start": 9556650, "end": 9557749}, {"filename": "/runtime/keymap/lithuanian-baltic.vim", "start": 9557749, "end": 9558712}, {"filename": "/runtime/keymap/magyar_utf-8.vim", "start": 9558712, "end": 9559038}, {"filename": "/runtime/keymap/mongolian_utf-8.vim", "start": 9559038, "end": 9561600}, {"filename": "/runtime/keymap/oldturkic-orkhon_utf-8.vim", "start": 9561600, "end": 9567402}, {"filename": "/runtime/keymap/oldturkic-yenisei_utf-8.vim", "start": 9567402, "end": 9574016}, {"filename": "/runtime/keymap/persian-iranian_utf-8.vim", "start": 9574016, "end": 9574768}, {"filename": "/runtime/keymap/persian.vim", "start": 9574768, "end": 9574912}, {"filename": "/runtime/keymap/pinyin.vim", "start": 9574912, "end": 9575451}, {"filename": "/runtime/keymap/polish-slash.vim", "start": 9575451, "end": 9576160}, {"filename": "/runtime/keymap/polish-slash_cp1250.vim", "start": 9576160, "end": 9577532}, {"filename": "/runtime/keymap/polish-slash_cp852.vim", "start": 9577532, "end": 9578903}, {"filename": "/runtime/keymap/polish-slash_iso-8859-2.vim", "start": 9578903, "end": 9580275}, {"filename": "/runtime/keymap/polish-slash_utf-8.vim", "start": 9580275, "end": 9581700}, {"filename": "/runtime/keymap/russian-dvorak.vim", "start": 9581700, "end": 9584294}, {"filename": "/runtime/keymap/russian-jcuken.vim", "start": 9584294, "end": 9586829}, {"filename": "/runtime/keymap/russian-jcukenmac.vim", "start": 9586829, "end": 9589642}, {"filename": "/runtime/keymap/russian-jcukenwin.vim", "start": 9589642, "end": 9592286}, {"filename": "/runtime/keymap/russian-jcukenwintype.vim", "start": 9592286, "end": 9595086}, {"filename": "/runtime/keymap/russian-typograph.vim", "start": 9595086, "end": 9612202}, {"filename": "/runtime/keymap/russian-yawerty.vim", "start": 9612202, "end": 9614665}, {"filename": "/runtime/keymap/serbian-latin.vim", "start": 9614665, "end": 9614708}, {"filename": "/runtime/keymap/serbian-latin_utf-8.vim", "start": 9614708, "end": 9615264}, {"filename": "/runtime/keymap/serbian.vim", "start": 9615264, "end": 9615731}, {"filename": "/runtime/keymap/serbian_cp1250.vim", "start": 9615731, "end": 9616182}, {"filename": "/runtime/keymap/serbian_cp1251.vim", "start": 9616182, "end": 9616836}, {"filename": "/runtime/keymap/serbian_iso-8859-2.vim", "start": 9616836, "end": 9617309}, {"filename": "/runtime/keymap/serbian_iso-8859-5.vim", "start": 9617309, "end": 9617969}, {"filename": "/runtime/keymap/serbian_utf-8.vim", "start": 9617969, "end": 9618762}, {"filename": "/runtime/keymap/sinhala-phonetic_utf-8.vim", "start": 9618762, "end": 9623435}, {"filename": "/runtime/keymap/sinhala.vim", "start": 9623435, "end": 9623525}, {"filename": "/runtime/keymap/slovak.vim", "start": 9623525, "end": 9623847}, {"filename": "/runtime/keymap/slovak_cp1250.vim", "start": 9623847, "end": 9624607}, {"filename": "/runtime/keymap/slovak_iso-8859-2.vim", "start": 9624607, "end": 9625367}, {"filename": "/runtime/keymap/slovak_utf-8.vim", "start": 9625367, "end": 9626179}, {"filename": "/runtime/keymap/tamil_tscii.vim", "start": 9626179, "end": 9640990}, {"filename": "/runtime/keymap/thaana-phonetic_utf-8.vim", "start": 9640990, "end": 9643812}, {"filename": "/runtime/keymap/thaana.vim", "start": 9643812, "end": 9643883}, {"filename": "/runtime/keymap/turkish-f.vim", "start": 9643883, "end": 9646644}, {"filename": "/runtime/keymap/turkish-q.vim", "start": 9646644, "end": 9648038}, {"filename": "/runtime/keymap/ukrainian-dvorak.vim", "start": 9648038, "end": 9650778}, {"filename": "/runtime/keymap/ukrainian-enhanced.vim", "start": 9650778, "end": 9653474}, {"filename": "/runtime/keymap/ukrainian-jcuken.vim", "start": 9653474, "end": 9656212}, {"filename": "/runtime/keymap/vietnamese-telex_utf-8.vim", "start": 9656212, "end": 9657569}, {"filename": "/runtime/keymap/vietnamese-viqr_utf-8.vim", "start": 9657569, "end": 9658816}, {"filename": "/runtime/keymap/vietnamese-vni_utf-8.vim", "start": 9658816, "end": 9660171}, {"filename": "/runtime/lua/coxpcall.lua", "start": 9660171, "end": 9664680}, {"filename": "/runtime/lua/editorconfig.lua", "start": 9664680, "end": 9676693}, {"filename": "/runtime/lua/man.lua", "start": 9676693, "end": 9703196}, {"filename": "/runtime/lua/nvim/autoread.lua", "start": 9703196, "end": 9709336}, {"filename": "/runtime/lua/nvim/dir.lua", "start": 9709336, "end": 9720524}, {"filename": "/runtime/lua/nvim/dir/fs.lua", "start": 9720524, "end": 9723526}, {"filename": "/runtime/lua/nvim/matchparen.lua", "start": 9723526, "end": 9733003}, {"filename": "/runtime/lua/nvim/spellfile.lua", "start": 9733003, "end": 9741208}, {"filename": "/runtime/lua/nvim/tutor.lua", "start": 9741208, "end": 9744544}, {"filename": "/runtime/lua/nvim/zip.lua", "start": 9744544, "end": 9762686}, {"filename": "/runtime/lua/nvim/zip/health.lua", "start": 9762686, "end": 9764291}, {"filename": "/runtime/lua/uv/_meta.lua", "start": 9764291, "end": 9937262}, {"filename": "/runtime/lua/vim/F.lua", "start": 9937262, "end": 9939152}, {"filename": "/runtime/lua/vim/_comment.lua", "start": 9939152, "end": 9948383}, {"filename": "/runtime/lua/vim/_core/cmdwin.lua", "start": 9948383, "end": 9954937}, {"filename": "/runtime/lua/vim/_core/defaults.lua", "start": 9954937, "end": 9995537}, {"filename": "/runtime/lua/vim/_core/editor.lua", "start": 9995537, "end": 10043461}, {"filename": "/runtime/lua/vim/_core/ex_cmd.lua", "start": 10043461, "end": 10054936}, {"filename": "/runtime/lua/vim/_core/exmode.lua", "start": 10054936, "end": 10063292}, {"filename": "/runtime/lua/vim/_core/exrc.lua", "start": 10063292, "end": 10063828}, {"filename": "/runtime/lua/vim/_core/help.lua", "start": 10063828, "end": 10084153}, {"filename": "/runtime/lua/vim/_core/log.lua", "start": 10084153, "end": 10084855}, {"filename": "/runtime/lua/vim/_core/marks.lua", "start": 10084855, "end": 10090369}, {"filename": "/runtime/lua/vim/_core/mcursor.lua", "start": 10090369, "end": 10100277}, {"filename": "/runtime/lua/vim/_core/options.lua", "start": 10100277, "end": 10118466}, {"filename": "/runtime/lua/vim/_core/proc.lua", "start": 10118466, "end": 10119042}, {"filename": "/runtime/lua/vim/_core/server.lua", "start": 10119042, "end": 10126444}, {"filename": "/runtime/lua/vim/_core/shared.lua", "start": 10126444, "end": 10178576}, {"filename": "/runtime/lua/vim/_core/spell.lua", "start": 10178576, "end": 10180188}, {"filename": "/runtime/lua/vim/_core/stringbuffer.lua", "start": 10180188, "end": 10183186}, {"filename": "/runtime/lua/vim/_core/swapfile.lua", "start": 10183186, "end": 10185969}, {"filename": "/runtime/lua/vim/_core/system.lua", "start": 10185969, "end": 10201449}, {"filename": "/runtime/lua/vim/_core/table.lua", "start": 10201449, "end": 10201955}, {"filename": "/runtime/lua/vim/_core/tag.lua", "start": 10201955, "end": 10203883}, {"filename": "/runtime/lua/vim/_core/time.lua", "start": 10203883, "end": 10205252}, {"filename": "/runtime/lua/vim/_core/ui.lua", "start": 10205252, "end": 10206003}, {"filename": "/runtime/lua/vim/_core/ui2.lua", "start": 10206003, "end": 10217921}, {"filename": "/runtime/lua/vim/_core/ui2/cmdline.lua", "start": 10217921, "end": 10225641}, {"filename": "/runtime/lua/vim/_core/ui2/messages.lua", "start": 10225641, "end": 10260867}, {"filename": "/runtime/lua/vim/_core/util.lua", "start": 10260867, "end": 10269249}, {"filename": "/runtime/lua/vim/_core/vimfn.lua", "start": 10269249, "end": 10270050}, {"filename": "/runtime/lua/vim/_ftplugin/lua.lua", "start": 10270050, "end": 10270603}, {"filename": "/runtime/lua/vim/_init_packages.lua", "start": 10270603, "end": 10273752}, {"filename": "/runtime/lua/vim/_inspector.lua", "start": 10273752, "end": 10282511}, {"filename": "/runtime/lua/vim/_meta.lua", "start": 10282511, "end": 10283917}, {"filename": "/runtime/lua/vim/_meta/api.gen.lua", "start": 10283917, "end": 10400530}, {"filename": "/runtime/lua/vim/_meta/api_keysets.gen.lua", "start": 10400530, "end": 10415148}, {"filename": "/runtime/lua/vim/_meta/api_keysets_extra.lua", "start": 10415148, "end": 10423412}, {"filename": "/runtime/lua/vim/_meta/base64.lua", "start": 10423412, "end": 10423828}, {"filename": "/runtime/lua/vim/_meta/builtin.lua", "start": 10423828, "end": 10434369}, {"filename": "/runtime/lua/vim/_meta/events.lua", "start": 10434369, "end": 10437195}, {"filename": "/runtime/lua/vim/_meta/json.lua", "start": 10437195, "end": 10439943}, {"filename": "/runtime/lua/vim/_meta/lpeg.lua", "start": 10439943, "end": 10455708}, {"filename": "/runtime/lua/vim/_meta/misc.lua", "start": 10455708, "end": 10456405}, {"filename": "/runtime/lua/vim/_meta/mpack.lua", "start": 10456405, "end": 10457005}, {"filename": "/runtime/lua/vim/_meta/options.gen.lua", "start": 10457005, "end": 10806474}, {"filename": "/runtime/lua/vim/_meta/re.lua", "start": 10806474, "end": 10808793}, {"filename": "/runtime/lua/vim/_meta/regex.lua", "start": 10808793, "end": 10810573}, {"filename": "/runtime/lua/vim/_meta/spell.lua", "start": 10810573, "end": 10811609}, {"filename": "/runtime/lua/vim/_meta/tui.lua", "start": 10811609, "end": 10811953}, {"filename": "/runtime/lua/vim/_meta/vimfn.gen.lua", "start": 10811953, "end": 11255185}, {"filename": "/runtime/lua/vim/_meta/vimfn_types.lua", "start": 11255185, "end": 11265745}, {"filename": "/runtime/lua/vim/_meta/vvars.gen.lua", "start": 11265745, "end": 11295401}, {"filename": "/runtime/lua/vim/_meta/vvars_extra.lua", "start": 11295401, "end": 11299704}, {"filename": "/runtime/lua/vim/_watch.lua", "start": 11299704, "end": 11323621}, {"filename": "/runtime/lua/vim/async.lua", "start": 11323621, "end": 11333912}, {"filename": "/runtime/lua/vim/async/_core.lua", "start": 11333912, "end": 11368883}, {"filename": "/runtime/lua/vim/async/_event.lua", "start": 11368883, "end": 11372176}, {"filename": "/runtime/lua/vim/async/_future.lua", "start": 11372176, "end": 11374116}, {"filename": "/runtime/lua/vim/async/_queue.lua", "start": 11374116, "end": 11376894}, {"filename": "/runtime/lua/vim/async/_runtime.lua", "start": 11376894, "end": 11378395}, {"filename": "/runtime/lua/vim/async/_semaphore.lua", "start": 11378395, "end": 11382331}, {"filename": "/runtime/lua/vim/deprecated/health.lua", "start": 11382331, "end": 11383790}, {"filename": "/runtime/lua/vim/diagnostic.lua", "start": 11383790, "end": 11423679}, {"filename": "/runtime/lua/vim/diagnostic/_config.lua", "start": 11423679, "end": 11428462}, {"filename": "/runtime/lua/vim/diagnostic/_display.lua", "start": 11428462, "end": 11434973}, {"filename": "/runtime/lua/vim/diagnostic/_float.lua", "start": 11434973, "end": 11445816}, {"filename": "/runtime/lua/vim/diagnostic/_handlers.lua", "start": 11445816, "end": 11469426}, {"filename": "/runtime/lua/vim/diagnostic/_jump.lua", "start": 11469426, "end": 11477813}, {"filename": "/runtime/lua/vim/diagnostic/_severity.lua", "start": 11477813, "end": 11481465}, {"filename": "/runtime/lua/vim/diagnostic/_shared.lua", "start": 11481465, "end": 11486606}, {"filename": "/runtime/lua/vim/diagnostic/_store.lua", "start": 11486606, "end": 11496278}, {"filename": "/runtime/lua/vim/filetype.lua", "start": 11496278, "end": 11594602}, {"filename": "/runtime/lua/vim/filetype/detect.lua", "start": 11594602, "end": 11667322}, {"filename": "/runtime/lua/vim/filetype/options.lua", "start": 11667322, "end": 11669986}, {"filename": "/runtime/lua/vim/fs.lua", "start": 11669986, "end": 11705293}, {"filename": "/runtime/lua/vim/func.lua", "start": 11705293, "end": 11707200}, {"filename": "/runtime/lua/vim/func/_memoize.lua", "start": 11707200, "end": 11709308}, {"filename": "/runtime/lua/vim/glob.lua", "start": 11709308, "end": 11722386}, {"filename": "/runtime/lua/vim/health.lua", "start": 11722386, "end": 11741152}, {"filename": "/runtime/lua/vim/health/health.lua", "start": 11741152, "end": 11768110}, {"filename": "/runtime/lua/vim/hl.lua", "start": 11768110, "end": 11775553}, {"filename": "/runtime/lua/vim/inspect.lua", "start": 11775553, "end": 11787629}, {"filename": "/runtime/lua/vim/iter.lua", "start": 11787629, "end": 11822039}, {"filename": "/runtime/lua/vim/keymap.lua", "start": 11822039, "end": 11827386}, {"filename": "/runtime/lua/vim/loader.lua", "start": 11827386, "end": 11842939}, {"filename": "/runtime/lua/vim/log.lua", "start": 11842939, "end": 11850886}, {"filename": "/runtime/lua/vim/lsp.lua", "start": 11850886, "end": 11904756}, {"filename": "/runtime/lua/vim/lsp/_capability.lua", "start": 11904756, "end": 11913824}, {"filename": "/runtime/lua/vim/lsp/_changetracking.lua", "start": 11913824, "end": 11927394}, {"filename": "/runtime/lua/vim/lsp/_folding_range.lua", "start": 11927394, "end": 11941531}, {"filename": "/runtime/lua/vim/lsp/_meta.lua", "start": 11941531, "end": 11942189}, {"filename": "/runtime/lua/vim/lsp/_meta/protocol.lua", "start": 11942189, "end": 12135791}, {"filename": "/runtime/lua/vim/lsp/_snippet_grammar.lua", "start": 12135791, "end": 12142044}, {"filename": "/runtime/lua/vim/lsp/_watchfiles.lua", "start": 12142044, "end": 12148904}, {"filename": "/runtime/lua/vim/lsp/buf.lua", "start": 12148904, "end": 12202221}, {"filename": "/runtime/lua/vim/lsp/client.lua", "start": 12202221, "end": 12257264}, {"filename": "/runtime/lua/vim/lsp/codelens.lua", "start": 12257264, "end": 12272093}, {"filename": "/runtime/lua/vim/lsp/completion.lua", "start": 12272093, "end": 12319843}, {"filename": "/runtime/lua/vim/lsp/diagnostic.lua", "start": 12319843, "end": 12337094}, {"filename": "/runtime/lua/vim/lsp/document_color.lua", "start": 12337094, "end": 12349716}, {"filename": "/runtime/lua/vim/lsp/handlers.lua", "start": 12349716, "end": 12377440}, {"filename": "/runtime/lua/vim/lsp/health.lua", "start": 12377440, "end": 12387569}, {"filename": "/runtime/lua/vim/lsp/inlay_hint.lua", "start": 12387569, "end": 12400134}, {"filename": "/runtime/lua/vim/lsp/inline_completion.lua", "start": 12400134, "end": 12416253}, {"filename": "/runtime/lua/vim/lsp/linked_editing_range.lua", "start": 12416253, "end": 12423535}, {"filename": "/runtime/lua/vim/lsp/log.lua", "start": 12423535, "end": 12427234}, {"filename": "/runtime/lua/vim/lsp/on_type_formatting.lua", "start": 12427234, "end": 12434843}, {"filename": "/runtime/lua/vim/lsp/protocol.lua", "start": 12434843, "end": 12499550}, {"filename": "/runtime/lua/vim/lsp/rpc.lua", "start": 12499550, "end": 12522802}, {"filename": "/runtime/lua/vim/lsp/semantic_tokens.lua", "start": 12522802, "end": 12556882}, {"filename": "/runtime/lua/vim/lsp/sync.lua", "start": 12556882, "end": 12573246}, {"filename": "/runtime/lua/vim/lsp/tagfunc.lua", "start": 12573246, "end": 12577057}, {"filename": "/runtime/lua/vim/lsp/util.lua", "start": 12577057, "end": 12648558}, {"filename": "/runtime/lua/vim/net.lua", "start": 12648558, "end": 12654534}, {"filename": "/runtime/lua/vim/net/_ssh.lua", "start": 12654534, "end": 12660308}, {"filename": "/runtime/lua/vim/net/_transport.lua", "start": 12660308, "end": 12668641}, {"filename": "/runtime/lua/vim/pack.lua", "start": 12668641, "end": 12730070}, {"filename": "/runtime/lua/vim/pack/_lsp.lua", "start": 12730070, "end": 12742670}, {"filename": "/runtime/lua/vim/pack/health.lua", "start": 12742670, "end": 12753947}, {"filename": "/runtime/lua/vim/pos.lua", "start": 12753947, "end": 12762915}, {"filename": "/runtime/lua/vim/pos/_util.lua", "start": 12762915, "end": 12768086}, {"filename": "/runtime/lua/vim/provider.lua", "start": 12768086, "end": 12768288}, {"filename": "/runtime/lua/vim/provider/health.lua", "start": 12768288, "end": 12799831}, {"filename": "/runtime/lua/vim/provider/perl.lua", "start": 12799831, "end": 12802023}, {"filename": "/runtime/lua/vim/provider/python.lua", "start": 12802023, "end": 12806579}, {"filename": "/runtime/lua/vim/provider/ruby.lua", "start": 12806579, "end": 12808370}, {"filename": "/runtime/lua/vim/range.lua", "start": 12808370, "end": 12822806}, {"filename": "/runtime/lua/vim/re.lua", "start": 12822806, "end": 12829517}, {"filename": "/runtime/lua/vim/secure.lua", "start": 12829517, "end": 12836646}, {"filename": "/runtime/lua/vim/snippet.lua", "start": 12836646, "end": 12859571}, {"filename": "/runtime/lua/vim/text.lua", "start": 12859571, "end": 12867089}, {"filename": "/runtime/lua/vim/treesitter.lua", "start": 12867089, "end": 12886592}, {"filename": "/runtime/lua/vim/treesitter/_fold.lua", "start": 12886592, "end": 12902360}, {"filename": "/runtime/lua/vim/treesitter/_headings.lua", "start": 12902360, "end": 12906462}, {"filename": "/runtime/lua/vim/treesitter/_meta/misc.lua", "start": 12906462, "end": 12909267}, {"filename": "/runtime/lua/vim/treesitter/_meta/tsnode.lua", "start": 12909267, "end": 12915257}, {"filename": "/runtime/lua/vim/treesitter/_meta/tsquery.lua", "start": 12915257, "end": 12917162}, {"filename": "/runtime/lua/vim/treesitter/_meta/tstree.lua", "start": 12917162, "end": 12918444}, {"filename": "/runtime/lua/vim/treesitter/_query_linter.lua", "start": 12918444, "end": 12926391}, {"filename": "/runtime/lua/vim/treesitter/_range.lua", "start": 12926391, "end": 12932248}, {"filename": "/runtime/lua/vim/treesitter/_select.lua", "start": 12932248, "end": 12949831}, {"filename": "/runtime/lua/vim/treesitter/dev.lua", "start": 12949831, "end": 12972064}, {"filename": "/runtime/lua/vim/treesitter/health.lua", "start": 12972064, "end": 12975366}, {"filename": "/runtime/lua/vim/treesitter/highlighter.lua", "start": 12975366, "end": 12994786}, {"filename": "/runtime/lua/vim/treesitter/language.lua", "start": 12994786, "end": 13000795}, {"filename": "/runtime/lua/vim/treesitter/languagetree.lua", "start": 13000795, "end": 13045629}, {"filename": "/runtime/lua/vim/treesitter/query.lua", "start": 13045629, "end": 13083393}, {"filename": "/runtime/lua/vim/tty.lua", "start": 13083393, "end": 13090671}, {"filename": "/runtime/lua/vim/ui.lua", "start": 13090671, "end": 13104545}, {"filename": "/runtime/lua/vim/ui/clipboard/osc52.lua", "start": 13104545, "end": 13106720}, {"filename": "/runtime/lua/vim/ui/img.lua", "start": 13106720, "end": 13111169}, {"filename": "/runtime/lua/vim/ui/img/_kitty.lua", "start": 13111169, "end": 13116858}, {"filename": "/runtime/lua/vim/uri.lua", "start": 13116858, "end": 13120567}, {"filename": "/runtime/lua/vim/version.lua", "start": 13120567, "end": 13136129}, {"filename": "/runtime/makemenu.vim", "start": 13136129, "end": 13161507}, {"filename": "/runtime/menu.vim", "start": 13161507, "end": 13204442}, {"filename": "/runtime/neovim.ico", "start": 13204442, "end": 13326797}, {"filename": "/runtime/nvim.png", "start": 13326797, "end": 13332684}, {"filename": "/runtime/org.neovim.nvim.appdata.xml", "start": 13332684, "end": 13336277}, {"filename": "/runtime/org.neovim.nvim.desktop", "start": 13336277, "end": 13340419}, {"filename": "/runtime/pack/dist/opt/cfilter/plugin/cfilter.vim", "start": 13340419, "end": 13342348}, {"filename": "/runtime/pack/dist/opt/justify/plugin/justify.vim", "start": 13342348, "end": 13351507}, {"filename": "/runtime/pack/dist/opt/matchit/autoload/matchit.vim", "start": 13351507, "end": 13382799}, {"filename": "/runtime/pack/dist/opt/matchit/doc/matchit.txt", "start": 13382799, "end": 13403223}, {"filename": "/runtime/pack/dist/opt/matchit/plugin/matchit.vim", "start": 13403223, "end": 13408328}, {"filename": "/runtime/pack/dist/opt/netrw/LICENSE.txt", "start": 13408328, "end": 13409077}, {"filename": "/runtime/pack/dist/opt/netrw/README.md", "start": 13409077, "end": 13434132}, {"filename": "/runtime/pack/dist/opt/netrw/autoload/netrw.vim", "start": 13434132, "end": 13831353}, {"filename": "/runtime/pack/dist/opt/netrw/autoload/netrw/fs.vim", "start": 13831353, "end": 13837161}, {"filename": "/runtime/pack/dist/opt/netrw/autoload/netrw/msg.vim", "start": 13837161, "end": 13839281}, {"filename": "/runtime/pack/dist/opt/netrw/autoload/netrw/os.vim", "start": 13839281, "end": 13840857}, {"filename": "/runtime/pack/dist/opt/netrw/autoload/netrw_gitignore.vim", "start": 13840857, "end": 13842264}, {"filename": "/runtime/pack/dist/opt/netrw/doc/netrw.txt", "start": 13842264, "end": 13989815}, {"filename": "/runtime/pack/dist/opt/netrw/plugin/netrwPlugin.vim", "start": 13989815, "end": 13997541}, {"filename": "/runtime/pack/dist/opt/netrw/syntax/netrw.vim", "start": 13997541, "end": 14004501}, {"filename": "/runtime/pack/dist/opt/nohlsearch/plugin/nohlsearch.vim", "start": 14004501, "end": 14005015}, {"filename": "/runtime/pack/dist/opt/nvim.difftool/lua/difftool.lua", "start": 14005015, "end": 14019326}, {"filename": "/runtime/pack/dist/opt/nvim.difftool/plugin/difftool.lua", "start": 14019326, "end": 14020247}, {"filename": "/runtime/pack/dist/opt/nvim.tohtml/lua/tohtml.lua", "start": 14020247, "end": 14065236}, {"filename": "/runtime/pack/dist/opt/nvim.tohtml/plugin/tohtml.lua", "start": 14065236, "end": 14065697}, {"filename": "/runtime/pack/dist/opt/nvim.undotree/lua/undotree.lua", "start": 14065697, "end": 14077423}, {"filename": "/runtime/pack/dist/opt/nvim.undotree/plugin/undotree.lua", "start": 14077423, "end": 14077610}, {"filename": "/runtime/pack/dist/opt/old-zip/autoload/zip.vim", "start": 14077610, "end": 14100390}, {"filename": "/runtime/pack/dist/opt/old-zip/doc/pi_zip.txt", "start": 14100390, "end": 14109666}, {"filename": "/runtime/pack/dist/opt/old-zip/plugin/zipPlugin.vim", "start": 14109666, "end": 14112561}, {"filename": "/runtime/pack/dist/opt/swapmouse/plugin/swapmouse.vim", "start": 14112561, "end": 14113417}, {"filename": "/runtime/pack/dist/opt/termdebug/plugin/termdebug.vim", "start": 14113417, "end": 14173299}, {"filename": "/runtime/plugin/autoread.lua", "start": 14173299, "end": 14173739}, {"filename": "/runtime/plugin/dir.lua", "start": 14173739, "end": 14176220}, {"filename": "/runtime/plugin/editorconfig.lua", "start": 14176220, "end": 14176625}, {"filename": "/runtime/plugin/gzip.vim", "start": 14176625, "end": 14180302}, {"filename": "/runtime/plugin/man.lua", "start": 14180302, "end": 14181467}, {"filename": "/runtime/plugin/matchit.vim", "start": 14181467, "end": 14181605}, {"filename": "/runtime/plugin/matchparen.lua", "start": 14181605, "end": 14181694}, {"filename": "/runtime/plugin/net.lua", "start": 14181694, "end": 14185522}, {"filename": "/runtime/plugin/netrwPlugin.vim", "start": 14185522, "end": 14185674}, {"filename": "/runtime/plugin/osc52.lua", "start": 14185674, "end": 14189240}, {"filename": "/runtime/plugin/rplugin.vim", "start": 14189240, "end": 14191257}, {"filename": "/runtime/plugin/shada.lua", "start": 14191257, "end": 14193801}, {"filename": "/runtime/plugin/spellfile.lua", "start": 14193801, "end": 14194156}, {"filename": "/runtime/plugin/tarPlugin.vim", "start": 14194156, "end": 14196733}, {"filename": "/runtime/plugin/tutor.vim", "start": 14196733, "end": 14197189}, {"filename": "/runtime/plugin/zip.lua", "start": 14197189, "end": 14199153}, {"filename": "/runtime/queries/c/folds.scm", "start": 14199153, "end": 14199559}, {"filename": "/runtime/queries/c/highlights.scm", "start": 14199559, "end": 14205891}, {"filename": "/runtime/queries/c/injections.scm", "start": 14205891, "end": 14205951}, {"filename": "/runtime/queries/diff/folds.scm", "start": 14205951, "end": 14205990}, {"filename": "/runtime/queries/diff/highlights.scm", "start": 14205990, "end": 14206905}, {"filename": "/runtime/queries/lua/folds.scm", "start": 14206905, "end": 14207108}, {"filename": "/runtime/queries/lua/highlights.scm", "start": 14207108, "end": 14211444}, {"filename": "/runtime/queries/lua/injections.scm", "start": 14211444, "end": 14214681}, {"filename": "/runtime/queries/markdown/folds.scm", "start": 14214681, "end": 14214836}, {"filename": "/runtime/queries/markdown/highlights.scm", "start": 14214836, "end": 14217490}, {"filename": "/runtime/queries/markdown/injections.scm", "start": 14217490, "end": 14218149}, {"filename": "/runtime/queries/markdown_inline/highlights.scm", "start": 14218149, "end": 14220408}, {"filename": "/runtime/queries/markdown_inline/injections.scm", "start": 14220408, "end": 14220615}, {"filename": "/runtime/queries/query/folds.scm", "start": 14220615, "end": 14220676}, {"filename": "/runtime/queries/query/highlights.scm", "start": 14220676, "end": 14222343}, {"filename": "/runtime/queries/vim/folds.scm", "start": 14222343, "end": 14222394}, {"filename": "/runtime/queries/vim/highlights.scm", "start": 14222394, "end": 14226925}, {"filename": "/runtime/queries/vim/injections.scm", "start": 14226925, "end": 14227789}, {"filename": "/runtime/queries/vimdoc/highlights.scm", "start": 14227789, "end": 14228997}, {"filename": "/runtime/queries/vimdoc/injections.scm", "start": 14228997, "end": 14229109}, {"filename": "/runtime/scripts/emoji_list.lua", "start": 14229109, "end": 14229515}, {"filename": "/runtime/scripts/less.bat", "start": 14229515, "end": 14229807}, {"filename": "/runtime/scripts/less.sh", "start": 14229807, "end": 14230399}, {"filename": "/runtime/scripts/less.vim", "start": 14230399, "end": 14238377}, {"filename": "/runtime/scripts/mswin.vim", "start": 14238377, "end": 14242591}, {"filename": "/runtime/scripts/optwin.lua", "start": 14242591, "end": 14276956}, {"filename": "/runtime/spell/cleanadd.vim", "start": 14276956, "end": 14277890}, {"filename": "/runtime/spell/en.utf-8.spl", "start": 14277890, "end": 14899507}, {"filename": "/runtime/synmenu.vim", "start": 14899507, "end": 14950177}, {"filename": "/runtime/syntax/8th.vim", "start": 14950177, "end": 14996933}, {"filename": "/runtime/syntax/README.txt", "start": 14996933, "end": 14998518}, {"filename": "/runtime/syntax/a2ps.vim", "start": 14998518, "end": 15000944}, {"filename": "/runtime/syntax/a65.vim", "start": 15000944, "end": 15007337}, {"filename": "/runtime/syntax/aap.vim", "start": 15007337, "end": 15013100}, {"filename": "/runtime/syntax/abap.vim", "start": 15013100, "end": 15022256}, {"filename": "/runtime/syntax/abaqus.vim", "start": 15022256, "end": 15023349}, {"filename": "/runtime/syntax/abc.vim", "start": 15023349, "end": 15025191}, {"filename": "/runtime/syntax/abel.vim", "start": 15025191, "end": 15030304}, {"filename": "/runtime/syntax/abnf.vim", "start": 15030304, "end": 15031256}, {"filename": "/runtime/syntax/acedb.vim", "start": 15031256, "end": 15036127}, {"filename": "/runtime/syntax/ada.vim", "start": 15036127, "end": 15049178}, {"filename": "/runtime/syntax/aflex.vim", "start": 15049178, "end": 15053546}, {"filename": "/runtime/syntax/ahdl.vim", "start": 15053546, "end": 15056316}, {"filename": "/runtime/syntax/aidl.vim", "start": 15056316, "end": 15056945}, {"filename": "/runtime/syntax/algol68.vim", "start": 15056945, "end": 15091061}, {"filename": "/runtime/syntax/alsaconf.vim", "start": 15091061, "end": 15092607}, {"filename": "/runtime/syntax/amiga.vim", "start": 15092607, "end": 15101548}, {"filename": "/runtime/syntax/aml.vim", "start": 15101548, "end": 15125685}, {"filename": "/runtime/syntax/ampl.vim", "start": 15125685, "end": 15129793}, {"filename": "/runtime/syntax/ant.vim", "start": 15129793, "end": 15135262}, {"filename": "/runtime/syntax/antlr.vim", "start": 15135262, "end": 15137055}, {"filename": "/runtime/syntax/antlr4.vim", "start": 15137055, "end": 15138165}, {"filename": "/runtime/syntax/apache.vim", "start": 15138165, "end": 15160649}, {"filename": "/runtime/syntax/apachestyle.vim", "start": 15160649, "end": 15162066}, {"filename": "/runtime/syntax/apkbuild.vim", "start": 15162066, "end": 15162465}, {"filename": "/runtime/syntax/aptconf.vim", "start": 15162465, "end": 15184906}, {"filename": "/runtime/syntax/arch.vim", "start": 15184906, "end": 15186055}, {"filename": "/runtime/syntax/arduino.vim", "start": 15186055, "end": 15190029}, {"filename": "/runtime/syntax/art.vim", "start": 15190029, "end": 15191326}, {"filename": "/runtime/syntax/asciidoc.vim", "start": 15191326, "end": 15202472}, {"filename": "/runtime/syntax/asm.vim", "start": 15202472, "end": 15208607}, {"filename": "/runtime/syntax/asm68k.vim", "start": 15208607, "end": 15222887}, {"filename": "/runtime/syntax/asmh8300.vim", "start": 15222887, "end": 15224605}, {"filename": "/runtime/syntax/asn.vim", "start": 15224605, "end": 15227183}, {"filename": "/runtime/syntax/aspperl.vim", "start": 15227183, "end": 15227994}, {"filename": "/runtime/syntax/aspvbs.vim", "start": 15227994, "end": 15237093}, {"filename": "/runtime/syntax/asterisk.vim", "start": 15237093, "end": 15242722}, {"filename": "/runtime/syntax/asteriskvm.vim", "start": 15242722, "end": 15245054}, {"filename": "/runtime/syntax/astro.vim", "start": 15245054, "end": 15251224}, {"filename": "/runtime/syntax/asy.vim", "start": 15251224, "end": 15264499}, {"filename": "/runtime/syntax/atlas.vim", "start": 15264499, "end": 15267101}, {"filename": "/runtime/syntax/autodoc.vim", "start": 15267101, "end": 15270831}, {"filename": "/runtime/syntax/autohotkey.vim", "start": 15270831, "end": 15282971}, {"filename": "/runtime/syntax/autoit.vim", "start": 15282971, "end": 15331017}, {"filename": "/runtime/syntax/automake.vim", "start": 15331017, "end": 15334953}, {"filename": "/runtime/syntax/autopkgtest.vim", "start": 15334953, "end": 15339258}, {"filename": "/runtime/syntax/ave.vim", "start": 15339258, "end": 15341158}, {"filename": "/runtime/syntax/avra.vim", "start": 15341158, "end": 15343841}, {"filename": "/runtime/syntax/awk.vim", "start": 15343841, "end": 15352021}, {"filename": "/runtime/syntax/ayacc.vim", "start": 15352021, "end": 15354606}, {"filename": "/runtime/syntax/b.vim", "start": 15354606, "end": 15359508}, {"filename": "/runtime/syntax/baan.vim", "start": 15359508, "end": 15433103}, {"filename": "/runtime/syntax/bash.vim", "start": 15433103, "end": 15433514}, {"filename": "/runtime/syntax/basic.vim", "start": 15433514, "end": 15442924}, {"filename": "/runtime/syntax/bazelrc.vim", "start": 15442924, "end": 15446737}, {"filename": "/runtime/syntax/bc.vim", "start": 15446737, "end": 15448528}, {"filename": "/runtime/syntax/bdf.vim", "start": 15448528, "end": 15452369}, {"filename": "/runtime/syntax/beancount.vim", "start": 15452369, "end": 15456828}, {"filename": "/runtime/syntax/bib.vim", "start": 15456828, "end": 15462832}, {"filename": "/runtime/syntax/bindzone.vim", "start": 15462832, "end": 15467764}, {"filename": "/runtime/syntax/bitbake.vim", "start": 15467764, "end": 15474008}, {"filename": "/runtime/syntax/blank.vim", "start": 15474008, "end": 15474802}, {"filename": "/runtime/syntax/bpftrace.vim", "start": 15474802, "end": 15477041}, {"filename": "/runtime/syntax/bsdl.vim", "start": 15477041, "end": 15477418}, {"filename": "/runtime/syntax/bst.vim", "start": 15477418, "end": 15480243}, {"filename": "/runtime/syntax/btm.vim", "start": 15480243, "end": 15489528}, {"filename": "/runtime/syntax/bzl.vim", "start": 15489528, "end": 15489902}, {"filename": "/runtime/syntax/bzr.vim", "start": 15489902, "end": 15491462}, {"filename": "/runtime/syntax/c.vim", "start": 15491462, "end": 15526932}, {"filename": "/runtime/syntax/cabal.vim", "start": 15526932, "end": 15535034}, {"filename": "/runtime/syntax/cabalconfig.vim", "start": 15535034, "end": 15536213}, {"filename": "/runtime/syntax/cabalproject.vim", "start": 15536213, "end": 15537157}, {"filename": "/runtime/syntax/calendar.vim", "start": 15537157, "end": 15542199}, {"filename": "/runtime/syntax/cangjie.vim", "start": 15542199, "end": 15551463}, {"filename": "/runtime/syntax/catalog.vim", "start": 15551463, "end": 15552384}, {"filename": "/runtime/syntax/cdl.vim", "start": 15552384, "end": 15555729}, {"filename": "/runtime/syntax/cdrdaoconf.vim", "start": 15555729, "end": 15559376}, {"filename": "/runtime/syntax/cdrtoc.vim", "start": 15559376, "end": 15582132}, {"filename": "/runtime/syntax/cf.vim", "start": 15582132, "end": 15600776}, {"filename": "/runtime/syntax/cfg.vim", "start": 15600776, "end": 15602228}, {"filename": "/runtime/syntax/cgdbrc.vim", "start": 15602228, "end": 15602663}, {"filename": "/runtime/syntax/ch.vim", "start": 15602663, "end": 15603585}, {"filename": "/runtime/syntax/chaiscript.vim", "start": 15603585, "end": 15606472}, {"filename": "/runtime/syntax/change.vim", "start": 15606472, "end": 15607373}, {"filename": "/runtime/syntax/changelog.vim", "start": 15607373, "end": 15609470}, {"filename": "/runtime/syntax/chaskell.vim", "start": 15609470, "end": 15609803}, {"filename": "/runtime/syntax/chatito.vim", "start": 15609803, "end": 15611657}, {"filename": "/runtime/syntax/checkhealth.vim", "start": 15611657, "end": 15612252}, {"filename": "/runtime/syntax/cheetah.vim", "start": 15612252, "end": 15614004}, {"filename": "/runtime/syntax/chicken.vim", "start": 15614004, "end": 15617311}, {"filename": "/runtime/syntax/chill.vim", "start": 15617311, "end": 15625336}, {"filename": "/runtime/syntax/chordpro.vim", "start": 15625336, "end": 15631969}, {"filename": "/runtime/syntax/chuck.vim", "start": 15631969, "end": 15638830}, {"filename": "/runtime/syntax/cl.vim", "start": 15638830, "end": 15642735}, {"filename": "/runtime/syntax/clean.vim", "start": 15642735, "end": 15645886}, {"filename": "/runtime/syntax/clipper.vim", "start": 15645886, "end": 15651462}, {"filename": "/runtime/syntax/clojure.vim", "start": 15651462, "end": 15699159}, {"filename": "/runtime/syntax/cmacro.vim", "start": 15699159, "end": 15704290}, {"filename": "/runtime/syntax/cmake.vim", "start": 15704290, "end": 15869493}, {"filename": "/runtime/syntax/cmakecache.vim", "start": 15869493, "end": 15872032}, {"filename": "/runtime/syntax/cmod.vim", "start": 15872032, "end": 15878303}, {"filename": "/runtime/syntax/cmusrc.vim", "start": 15878303, "end": 15891224}, {"filename": "/runtime/syntax/cobol.vim", "start": 15891224, "end": 15904816}, {"filename": "/runtime/syntax/coco.vim", "start": 15904816, "end": 15906146}, {"filename": "/runtime/syntax/codeowners.vim", "start": 15906146, "end": 15906817}, {"filename": "/runtime/syntax/colortest.vim", "start": 15906817, "end": 15910084}, {"filename": "/runtime/syntax/conaryrecipe.vim", "start": 15910084, "end": 15918120}, {"filename": "/runtime/syntax/conf.vim", "start": 15918120, "end": 15918999}, {"filename": "/runtime/syntax/config.vim", "start": 15918999, "end": 15921318}, {"filename": "/runtime/syntax/confini.vim", "start": 15921318, "end": 15921736}, {"filename": "/runtime/syntax/context.vim", "start": 15921736, "end": 15927935}, {"filename": "/runtime/syntax/cpp.vim", "start": 15927935, "end": 15935822}, {"filename": "/runtime/syntax/crm.vim", "start": 15935822, "end": 15937019}, {"filename": "/runtime/syntax/crontab.vim", "start": 15937019, "end": 15939267}, {"filename": "/runtime/syntax/cs.vim", "start": 15939267, "end": 15955728}, {"filename": "/runtime/syntax/csc.vim", "start": 15955728, "end": 15964179}, {"filename": "/runtime/syntax/csdl.vim", "start": 15964179, "end": 15987934}, {"filename": "/runtime/syntax/csh.vim", "start": 15987934, "end": 15994337}, {"filename": "/runtime/syntax/csp.vim", "start": 15994337, "end": 16001489}, {"filename": "/runtime/syntax/css.vim", "start": 16001489, "end": 16036930}, {"filename": "/runtime/syntax/csv.vim", "start": 16036930, "end": 16038226}, {"filename": "/runtime/syntax/cterm.vim", "start": 16038226, "end": 16044632}, {"filename": "/runtime/syntax/ctrlh.vim", "start": 16044632, "end": 16045386}, {"filename": "/runtime/syntax/cucumber.vim", "start": 16045386, "end": 16097985}, {"filename": "/runtime/syntax/cuda.vim", "start": 16097985, "end": 16100296}, {"filename": "/runtime/syntax/cupl.vim", "start": 16100296, "end": 16104168}, {"filename": "/runtime/syntax/cuplsim.vim", "start": 16104168, "end": 16106084}, {"filename": "/runtime/syntax/cvs.vim", "start": 16106084, "end": 16106966}, {"filename": "/runtime/syntax/cvsrc.vim", "start": 16106966, "end": 16108220}, {"filename": "/runtime/syntax/cweb.vim", "start": 16108220, "end": 16111112}, {"filename": "/runtime/syntax/cynlib.vim", "start": 16111112, "end": 16113660}, {"filename": "/runtime/syntax/cynpp.vim", "start": 16113660, "end": 16114923}, {"filename": "/runtime/syntax/d.vim", "start": 16114923, "end": 16143555}, {"filename": "/runtime/syntax/dart.vim", "start": 16143555, "end": 16146847}, {"filename": "/runtime/syntax/datascript.vim", "start": 16146847, "end": 16150654}, {"filename": "/runtime/syntax/dax.vim", "start": 16150654, "end": 16158133}, {"filename": "/runtime/syntax/dcd.vim", "start": 16158133, "end": 16159936}, {"filename": "/runtime/syntax/dcl.vim", "start": 16159936, "end": 16166003}, {"filename": "/runtime/syntax/deb822sources.vim", "start": 16166003, "end": 16169658}, {"filename": "/runtime/syntax/debchangelog.vim", "start": 16169658, "end": 16172501}, {"filename": "/runtime/syntax/debcontrol.vim", "start": 16172501, "end": 16182014}, {"filename": "/runtime/syntax/debcopyright.vim", "start": 16182014, "end": 16183164}, {"filename": "/runtime/syntax/debsources.vim", "start": 16183164, "end": 16185185}, {"filename": "/runtime/syntax/def.vim", "start": 16185185, "end": 16186282}, {"filename": "/runtime/syntax/denyhosts.vim", "start": 16186282, "end": 16193647}, {"filename": "/runtime/syntax/dep3patch.vim", "start": 16193647, "end": 16196650}, {"filename": "/runtime/syntax/desc.vim", "start": 16196650, "end": 16199727}, {"filename": "/runtime/syntax/desktop.vim", "start": 16199727, "end": 16209111}, {"filename": "/runtime/syntax/dictconf.vim", "start": 16209111, "end": 16212229}, {"filename": "/runtime/syntax/dictdconf.vim", "start": 16212229, "end": 16218694}, {"filename": "/runtime/syntax/diff.vim", "start": 16218694, "end": 16236170}, {"filename": "/runtime/syntax/dircolors.vim", "start": 16236170, "end": 16243306}, {"filename": "/runtime/syntax/directory.vim", "start": 16243306, "end": 16243583}, {"filename": "/runtime/syntax/dirpager.vim", "start": 16243583, "end": 16245373}, {"filename": "/runtime/syntax/diva.vim", "start": 16245373, "end": 16250109}, {"filename": "/runtime/syntax/django.vim", "start": 16250109, "end": 16254674}, {"filename": "/runtime/syntax/dns.vim", "start": 16254674, "end": 16254806}, {"filename": "/runtime/syntax/dnsmasq.vim", "start": 16254806, "end": 16268362}, {"filename": "/runtime/syntax/docbk.vim", "start": 16268362, "end": 16278683}, {"filename": "/runtime/syntax/docbksgml.vim", "start": 16278683, "end": 16278876}, {"filename": "/runtime/syntax/docbkxml.vim", "start": 16278876, "end": 16279067}, {"filename": "/runtime/syntax/dockerfile.vim", "start": 16279067, "end": 16281619}, {"filename": "/runtime/syntax/dosbatch.vim", "start": 16281619, "end": 16288769}, {"filename": "/runtime/syntax/dosini.vim", "start": 16288769, "end": 16290322}, {"filename": "/runtime/syntax/dot.vim", "start": 16290322, "end": 16294815}, {"filename": "/runtime/syntax/doxygen.vim", "start": 16294815, "end": 16333901}, {"filename": "/runtime/syntax/dracula.vim", "start": 16333901, "end": 16336569}, {"filename": "/runtime/syntax/dsl.vim", "start": 16336569, "end": 16337881}, {"filename": "/runtime/syntax/dtd.vim", "start": 16337881, "end": 16342871}, {"filename": "/runtime/syntax/dtml.vim", "start": 16342871, "end": 16359484}, {"filename": "/runtime/syntax/dtrace.vim", "start": 16359484, "end": 16366045}, {"filename": "/runtime/syntax/dts.vim", "start": 16366045, "end": 16371318}, {"filename": "/runtime/syntax/dune.vim", "start": 16371318, "end": 16373396}, {"filename": "/runtime/syntax/dylan.vim", "start": 16373396, "end": 16376810}, {"filename": "/runtime/syntax/dylanintr.vim", "start": 16376810, "end": 16377888}, {"filename": "/runtime/syntax/dylanlid.vim", "start": 16377888, "end": 16378552}, {"filename": "/runtime/syntax/ecd.vim", "start": 16378552, "end": 16379781}, {"filename": "/runtime/syntax/ed.vim", "start": 16379781, "end": 16398874}, {"filename": "/runtime/syntax/edif.vim", "start": 16398874, "end": 16400152}, {"filename": "/runtime/syntax/editorconfig.vim", "start": 16400152, "end": 16400673}, {"filename": "/runtime/syntax/eiffel.vim", "start": 16400673, "end": 16407599}, {"filename": "/runtime/syntax/elf.vim", "start": 16407599, "end": 16409948}, {"filename": "/runtime/syntax/elinks.vim", "start": 16409948, "end": 16420442}, {"filename": "/runtime/syntax/elm.vim", "start": 16420442, "end": 16424093}, {"filename": "/runtime/syntax/elmfilt.vim", "start": 16424093, "end": 16427030}, {"filename": "/runtime/syntax/env.vim", "start": 16427030, "end": 16427878}, {"filename": "/runtime/syntax/erlang.vim", "start": 16427878, "end": 16446533}, {"filename": "/runtime/syntax/eruby.vim", "start": 16446533, "end": 16449613}, {"filename": "/runtime/syntax/esmtprc.vim", "start": 16449613, "end": 16450551}, {"filename": "/runtime/syntax/esqlc.vim", "start": 16450551, "end": 16452645}, {"filename": "/runtime/syntax/esterel.vim", "start": 16452645, "end": 16455698}, {"filename": "/runtime/syntax/eterm.vim", "start": 16455698, "end": 16474870}, {"filename": "/runtime/syntax/euphoria3.vim", "start": 16474870, "end": 16480973}, {"filename": "/runtime/syntax/euphoria4.vim", "start": 16480973, "end": 16495511}, {"filename": "/runtime/syntax/eviews.vim", "start": 16495511, "end": 16500515}, {"filename": "/runtime/syntax/exim.vim", "start": 16500515, "end": 16518600}, {"filename": "/runtime/syntax/expect.vim", "start": 16518600, "end": 16522334}, {"filename": "/runtime/syntax/exports.vim", "start": 16522334, "end": 16524126}, {"filename": "/runtime/syntax/falcon.vim", "start": 16524126, "end": 16536139}, {"filename": "/runtime/syntax/fan.vim", "start": 16536139, "end": 16541584}, {"filename": "/runtime/syntax/fasm.vim", "start": 16541584, "end": 16550341}, {"filename": "/runtime/syntax/fdcc.vim", "start": 16550341, "end": 16555989}, {"filename": "/runtime/syntax/fetchmail.vim", "start": 16555989, "end": 16558993}, {"filename": "/runtime/syntax/fgl.vim", "start": 16558993, "end": 16566267}, {"filename": "/runtime/syntax/fish.vim", "start": 16566267, "end": 16575698}, {"filename": "/runtime/syntax/flexwiki.vim", "start": 16575698, "end": 16581135}, {"filename": "/runtime/syntax/focexec.vim", "start": 16581135, "end": 16584662}, {"filename": "/runtime/syntax/form.vim", "start": 16584662, "end": 16591047}, {"filename": "/runtime/syntax/forth.vim", "start": 16591047, "end": 16607679}, {"filename": "/runtime/syntax/fortran.vim", "start": 16607679, "end": 16643800}, {"filename": "/runtime/syntax/foxpro.vim", "start": 16643800, "end": 16676656}, {"filename": "/runtime/syntax/fpcmake.vim", "start": 16676656, "end": 16678700}, {"filename": "/runtime/syntax/framescript.vim", "start": 16678700, "end": 16690379}, {"filename": "/runtime/syntax/freebasic.vim", "start": 16690379, "end": 16717000}, {"filename": "/runtime/syntax/fstab.vim", "start": 16717000, "end": 16748379}, {"filename": "/runtime/syntax/fvwm.vim", "start": 16748379, "end": 16773654}, {"filename": "/runtime/syntax/fvwm2m4.vim", "start": 16773654, "end": 16774365}, {"filename": "/runtime/syntax/gdb.vim", "start": 16774365, "end": 16890201}, {"filename": "/runtime/syntax/gdmo.vim", "start": 16890201, "end": 16893500}, {"filename": "/runtime/syntax/gdresource.vim", "start": 16893500, "end": 16895789}, {"filename": "/runtime/syntax/gdscript.vim", "start": 16895789, "end": 16899667}, {"filename": "/runtime/syntax/gdshader.vim", "start": 16899667, "end": 16901684}, {"filename": "/runtime/syntax/gedcom.vim", "start": 16901684, "end": 16903959}, {"filename": "/runtime/syntax/gel.vim", "start": 16903959, "end": 16906631}, {"filename": "/runtime/syntax/gemtext.vim", "start": 16906631, "end": 16907313}, {"filename": "/runtime/syntax/gift.vim", "start": 16907313, "end": 16917552}, {"filename": "/runtime/syntax/git.vim", "start": 16917552, "end": 16925019}, {"filename": "/runtime/syntax/gitattributes.vim", "start": 16925019, "end": 16927461}, {"filename": "/runtime/syntax/gitcommit.vim", "start": 16927461, "end": 16934079}, {"filename": "/runtime/syntax/gitconfig.vim", "start": 16934079, "end": 16935675}, {"filename": "/runtime/syntax/gitignore.vim", "start": 16935675, "end": 16936580}, {"filename": "/runtime/syntax/gitolite.vim", "start": 16936580, "end": 16941034}, {"filename": "/runtime/syntax/gitrebase.vim", "start": 16941034, "end": 16944234}, {"filename": "/runtime/syntax/gitrevlist.vim", "start": 16944234, "end": 16944731}, {"filename": "/runtime/syntax/gitsendemail.vim", "start": 16944731, "end": 16945311}, {"filename": "/runtime/syntax/gkrellmrc.vim", "start": 16945311, "end": 16949427}, {"filename": "/runtime/syntax/gleam.vim", "start": 16949427, "end": 16952235}, {"filename": "/runtime/syntax/glimmer.vim", "start": 16952235, "end": 16953690}, {"filename": "/runtime/syntax/glsl.vim", "start": 16953690, "end": 16986493}, {"filename": "/runtime/syntax/gnash.vim", "start": 16986493, "end": 16989955}, {"filename": "/runtime/syntax/gnuplot.vim", "start": 16989955, "end": 17013780}, {"filename": "/runtime/syntax/go.vim", "start": 17013780, "end": 17039329}, {"filename": "/runtime/syntax/goaccess.vim", "start": 17039329, "end": 17040593}, {"filename": "/runtime/syntax/godoc.vim", "start": 17040593, "end": 17040945}, {"filename": "/runtime/syntax/gp.vim", "start": 17040945, "end": 17044325}, {"filename": "/runtime/syntax/gpg.vim", "start": 17044325, "end": 17052814}, {"filename": "/runtime/syntax/gprof.vim", "start": 17052814, "end": 17054950}, {"filename": "/runtime/syntax/grads.vim", "start": 17054950, "end": 17056692}, {"filename": "/runtime/syntax/graphql.vim", "start": 17056692, "end": 17060643}, {"filename": "/runtime/syntax/gretl.vim", "start": 17060643, "end": 17064373}, {"filename": "/runtime/syntax/groff.vim", "start": 17064373, "end": 17064694}, {"filename": "/runtime/syntax/groovy.vim", "start": 17064694, "end": 17087177}, {"filename": "/runtime/syntax/group.vim", "start": 17087177, "end": 17088896}, {"filename": "/runtime/syntax/grub.vim", "start": 17088896, "end": 17092778}, {"filename": "/runtime/syntax/gsp.vim", "start": 17092778, "end": 17094809}, {"filename": "/runtime/syntax/gtkrc.vim", "start": 17094809, "end": 17104127}, {"filename": "/runtime/syntax/gvpr.vim", "start": 17104127, "end": 17107505}, {"filename": "/runtime/syntax/gyp.vim", "start": 17107505, "end": 17109159}, {"filename": "/runtime/syntax/haml.vim", "start": 17109159, "end": 17116230}, {"filename": "/runtime/syntax/hamster.vim", "start": 17116230, "end": 17130070}, {"filename": "/runtime/syntax/handlebars.vim", "start": 17130070, "end": 17137458}, {"filename": "/runtime/syntax/hare.vim", "start": 17137458, "end": 17142754}, {"filename": "/runtime/syntax/haredoc.vim", "start": 17142754, "end": 17143679}, {"filename": "/runtime/syntax/haskell.vim", "start": 17143679, "end": 17153152}, {"filename": "/runtime/syntax/haste.vim", "start": 17153152, "end": 17158146}, {"filename": "/runtime/syntax/hastepreproc.vim", "start": 17158146, "end": 17159568}, {"filename": "/runtime/syntax/hb.vim", "start": 17159568, "end": 17162726}, {"filename": "/runtime/syntax/hcl.vim", "start": 17162726, "end": 17165898}, {"filename": "/runtime/syntax/help.vim", "start": 17165898, "end": 17176135}, {"filename": "/runtime/syntax/help_it.vim", "start": 17176135, "end": 17176796}, {"filename": "/runtime/syntax/help_ru.vim", "start": 17176796, "end": 17180317}, {"filename": "/runtime/syntax/hercules.vim", "start": 17180317, "end": 17186037}, {"filename": "/runtime/syntax/hex.vim", "start": 17186037, "end": 17190048}, {"filename": "/runtime/syntax/hgcommit.vim", "start": 17190048, "end": 17191461}, {"filename": "/runtime/syntax/hip.vim", "start": 17191461, "end": 17193661}, {"filename": "/runtime/syntax/hitest.vim", "start": 17193661, "end": 17197304}, {"filename": "/runtime/syntax/hlsl.vim", "start": 17197304, "end": 17202505}, {"filename": "/runtime/syntax/hlsplaylist.vim", "start": 17202505, "end": 17210125}, {"filename": "/runtime/syntax/hog.vim", "start": 17210125, "end": 17222237}, {"filename": "/runtime/syntax/hollywood.vim", "start": 17222237, "end": 17272770}, {"filename": "/runtime/syntax/hostconf.vim", "start": 17272770, "end": 17276640}, {"filename": "/runtime/syntax/hostsaccess.vim", "start": 17276640, "end": 17277189}, {"filename": "/runtime/syntax/html.vim", "start": 17277189, "end": 17297131}, {"filename": "/runtime/syntax/htmlangular.vim", "start": 17297131, "end": 17297502}, {"filename": "/runtime/syntax/htmlcheetah.vim", "start": 17297502, "end": 17297990}, {"filename": "/runtime/syntax/htmldjango.vim", "start": 17297990, "end": 17299001}, {"filename": "/runtime/syntax/htmlm4.vim", "start": 17299001, "end": 17299671}, {"filename": "/runtime/syntax/htmlos.vim", "start": 17299671, "end": 17307984}, {"filename": "/runtime/syntax/hyprlang.vim", "start": 17307984, "end": 17310540}, {"filename": "/runtime/syntax/i3config.vim", "start": 17310540, "end": 17333764}, {"filename": "/runtime/syntax/ia64.vim", "start": 17333764, "end": 17344237}, {"filename": "/runtime/syntax/ibasic.vim", "start": 17344237, "end": 17351273}, {"filename": "/runtime/syntax/icalendar.vim", "start": 17351273, "end": 17356239}, {"filename": "/runtime/syntax/icemenu.vim", "start": 17356239, "end": 17357063}, {"filename": "/runtime/syntax/icon.vim", "start": 17357063, "end": 17365989}, {"filename": "/runtime/syntax/idl.vim", "start": 17365989, "end": 17385890}, {"filename": "/runtime/syntax/idlang.vim", "start": 17385890, "end": 17399432}, {"filename": "/runtime/syntax/idris2.vim", "start": 17399432, "end": 17403859}, {"filename": "/runtime/syntax/indent.vim", "start": 17403859, "end": 17411059}, {"filename": "/runtime/syntax/inform.vim", "start": 17411059, "end": 17430542}, {"filename": "/runtime/syntax/initex.vim", "start": 17430542, "end": 17449766}, {"filename": "/runtime/syntax/initng.vim", "start": 17449766, "end": 17453141}, {"filename": "/runtime/syntax/inittab.vim", "start": 17453141, "end": 17455682}, {"filename": "/runtime/syntax/ipfilter.vim", "start": 17455682, "end": 17457338}, {"filename": "/runtime/syntax/ipkg.vim", "start": 17457338, "end": 17458935}, {"filename": "/runtime/syntax/ishd.vim", "start": 17458935, "end": 17485992}, {"filename": "/runtime/syntax/iss.vim", "start": 17485992, "end": 17490996}, {"filename": "/runtime/syntax/ist.vim", "start": 17490996, "end": 17492996}, {"filename": "/runtime/syntax/j.vim", "start": 17492996, "end": 17502437}, {"filename": "/runtime/syntax/jal.vim", "start": 17502437, "end": 17511400}, {"filename": "/runtime/syntax/jam.vim", "start": 17511400, "end": 17538111}, {"filename": "/runtime/syntax/jargon.vim", "start": 17538111, "end": 17538851}, {"filename": "/runtime/syntax/java.vim", "start": 17538851, "end": 17595890}, {"filename": "/runtime/syntax/javacc.vim", "start": 17595890, "end": 17598265}, {"filename": "/runtime/syntax/javascript.vim", "start": 17598265, "end": 17604619}, {"filename": "/runtime/syntax/javascriptreact.vim", "start": 17604619, "end": 17604752}, {"filename": "/runtime/syntax/jess.vim", "start": 17604752, "end": 17610982}, {"filename": "/runtime/syntax/jgraph.vim", "start": 17610982, "end": 17612194}, {"filename": "/runtime/syntax/jinja.vim", "start": 17612194, "end": 17616776}, {"filename": "/runtime/syntax/jjdescription.vim", "start": 17616776, "end": 17617833}, {"filename": "/runtime/syntax/jovial.vim", "start": 17617833, "end": 17621940}, {"filename": "/runtime/syntax/jproperties.vim", "start": 17621940, "end": 17627436}, {"filename": "/runtime/syntax/jq.vim", "start": 17627436, "end": 17633116}, {"filename": "/runtime/syntax/json.vim", "start": 17633116, "end": 17639390}, {"filename": "/runtime/syntax/json5.vim", "start": 17639390, "end": 17641945}, {"filename": "/runtime/syntax/jsonc.vim", "start": 17641945, "end": 17643678}, {"filename": "/runtime/syntax/jsonld.vim", "start": 17643678, "end": 17644876}, {"filename": "/runtime/syntax/jsp.vim", "start": 17644876, "end": 17647554}, {"filename": "/runtime/syntax/julia.vim", "start": 17647554, "end": 17685745}, {"filename": "/runtime/syntax/just.vim", "start": 17685745, "end": 17706788}, {"filename": "/runtime/syntax/karel.vim", "start": 17706788, "end": 17713235}, {"filename": "/runtime/syntax/kconfig.vim", "start": 17713235, "end": 17750674}, {"filename": "/runtime/syntax/kdl.vim", "start": 17750674, "end": 17752323}, {"filename": "/runtime/syntax/kitty.vim", "start": 17752323, "end": 17757269}, {"filename": "/runtime/syntax/kivy.vim", "start": 17757269, "end": 17758260}, {"filename": "/runtime/syntax/kix.vim", "start": 17758260, "end": 17764589}, {"filename": "/runtime/syntax/kotlin.vim", "start": 17764589, "end": 17774023}, {"filename": "/runtime/syntax/krl.vim", "start": 17774023, "end": 17794687}, {"filename": "/runtime/syntax/kscript.vim", "start": 17794687, "end": 17796672}, {"filename": "/runtime/syntax/kwt.vim", "start": 17796672, "end": 17799126}, {"filename": "/runtime/syntax/lace.vim", "start": 17799126, "end": 17803201}, {"filename": "/runtime/syntax/latte.vim", "start": 17803201, "end": 17806712}, {"filename": "/runtime/syntax/lc.vim", "start": 17806712, "end": 17807507}, {"filename": "/runtime/syntax/ld.vim", "start": 17807507, "end": 17810454}, {"filename": "/runtime/syntax/ldapconf.vim", "start": 17810454, "end": 17823756}, {"filename": "/runtime/syntax/ldif.vim", "start": 17823756, "end": 17824770}, {"filename": "/runtime/syntax/leex.vim", "start": 17824770, "end": 17827471}, {"filename": "/runtime/syntax/less.vim", "start": 17827471, "end": 17831666}, {"filename": "/runtime/syntax/lex.vim", "start": 17831666, "end": 17840405}, {"filename": "/runtime/syntax/lf.vim", "start": 17840405, "end": 17844937}, {"filename": "/runtime/syntax/lftp.vim", "start": 17844937, "end": 17852408}, {"filename": "/runtime/syntax/lhaskell.vim", "start": 17852408, "end": 17857381}, {"filename": "/runtime/syntax/libao.vim", "start": 17857381, "end": 17858017}, {"filename": "/runtime/syntax/lidris2.vim", "start": 17858017, "end": 17858821}, {"filename": "/runtime/syntax/lifelines.vim", "start": 17858821, "end": 17865461}, {"filename": "/runtime/syntax/lilo.vim", "start": 17865461, "end": 17875888}, {"filename": "/runtime/syntax/limits.vim", "start": 17875888, "end": 17877205}, {"filename": "/runtime/syntax/liquid.vim", "start": 17877205, "end": 17883662}, {"filename": "/runtime/syntax/lisp.vim", "start": 17883662, "end": 17919784}, {"filename": "/runtime/syntax/lite.vim", "start": 17919784, "end": 17924629}, {"filename": "/runtime/syntax/litestep.vim", "start": 17924629, "end": 17931305}, {"filename": "/runtime/syntax/livebook.vim", "start": 17931305, "end": 17931459}, {"filename": "/runtime/syntax/lnk.vim", "start": 17931459, "end": 17933422}, {"filename": "/runtime/syntax/lnkmap.vim", "start": 17933422, "end": 17934617}, {"filename": "/runtime/syntax/log.vim", "start": 17934617, "end": 17944215}, {"filename": "/runtime/syntax/loginaccess.vim", "start": 17944215, "end": 17948412}, {"filename": "/runtime/syntax/logindefs.vim", "start": 17948412, "end": 17957039}, {"filename": "/runtime/syntax/logtalk.vim", "start": 17957039, "end": 17974272}, {"filename": "/runtime/syntax/lotos.vim", "start": 17974272, "end": 17976633}, {"filename": "/runtime/syntax/lout.vim", "start": 17976633, "end": 17980959}, {"filename": "/runtime/syntax/lpc.vim", "start": 17980959, "end": 18004097}, {"filename": "/runtime/syntax/lprolog.vim", "start": 18004097, "end": 18008076}, {"filename": "/runtime/syntax/lscript.vim", "start": 18008076, "end": 18020175}, {"filename": "/runtime/syntax/lsl.vim", "start": 18020175, "end": 18035975}, {"filename": "/runtime/syntax/lsp_markdown.vim", "start": 18035975, "end": 18037426}, {"filename": "/runtime/syntax/lss.vim", "start": 18037426, "end": 18042295}, {"filename": "/runtime/syntax/lua.vim", "start": 18042295, "end": 18058345}, {"filename": "/runtime/syntax/luau.vim", "start": 18058345, "end": 18070620}, {"filename": "/runtime/syntax/lynx.vim", "start": 18070620, "end": 18077936}, {"filename": "/runtime/syntax/lyrics.vim", "start": 18077936, "end": 18079114}, {"filename": "/runtime/syntax/m17ndb.vim", "start": 18079114, "end": 18080135}, {"filename": "/runtime/syntax/m3build.vim", "start": 18080135, "end": 18085912}, {"filename": "/runtime/syntax/m3quake.vim", "start": 18085912, "end": 18088546}, {"filename": "/runtime/syntax/m4.vim", "start": 18088546, "end": 18093083}, {"filename": "/runtime/syntax/mail.vim", "start": 18093083, "end": 18099278}, {"filename": "/runtime/syntax/mailaliases.vim", "start": 18099278, "end": 18102045}, {"filename": "/runtime/syntax/mailcap.vim", "start": 18102045, "end": 18103180}, {"filename": "/runtime/syntax/make.vim", "start": 18103180, "end": 18112700}, {"filename": "/runtime/syntax/mallard.vim", "start": 18112700, "end": 18114426}, {"filename": "/runtime/syntax/man.vim", "start": 18114426, "end": 18116768}, {"filename": "/runtime/syntax/manconf.vim", "start": 18116768, "end": 18121112}, {"filename": "/runtime/syntax/manual.vim", "start": 18121112, "end": 18122087}, {"filename": "/runtime/syntax/maple.vim", "start": 18122087, "end": 18150751}, {"filename": "/runtime/syntax/markdown.vim", "start": 18150751, "end": 18161335}, {"filename": "/runtime/syntax/marko.vim", "start": 18161335, "end": 18193066}, {"filename": "/runtime/syntax/masm.vim", "start": 18193066, "end": 18221692}, {"filename": "/runtime/syntax/mason.vim", "start": 18221692, "end": 18225620}, {"filename": "/runtime/syntax/master.vim", "start": 18225620, "end": 18226642}, {"filename": "/runtime/syntax/matlab.vim", "start": 18226642, "end": 18230958}, {"filename": "/runtime/syntax/maxima.vim", "start": 18230958, "end": 18249760}, {"filename": "/runtime/syntax/mbsync.vim", "start": 18249760, "end": 18264044}, {"filename": "/runtime/syntax/mediawiki.vim", "start": 18264044, "end": 18280330}, {"filename": "/runtime/syntax/mel.vim", "start": 18280330, "end": 18283735}, {"filename": "/runtime/syntax/mermaid.vim", "start": 18283735, "end": 18286600}, {"filename": "/runtime/syntax/meson.vim", "start": 18286600, "end": 18290659}, {"filename": "/runtime/syntax/messages.vim", "start": 18290659, "end": 18293374}, {"filename": "/runtime/syntax/mf.vim", "start": 18293374, "end": 18308797}, {"filename": "/runtime/syntax/mgl.vim", "start": 18308797, "end": 18312521}, {"filename": "/runtime/syntax/mgp.vim", "start": 18312521, "end": 18314521}, {"filename": "/runtime/syntax/mib.vim", "start": 18314521, "end": 18317217}, {"filename": "/runtime/syntax/mix.vim", "start": 18317217, "end": 18320159}, {"filename": "/runtime/syntax/mma.vim", "start": 18320159, "end": 18332022}, {"filename": "/runtime/syntax/mmix.vim", "start": 18332022, "end": 18336991}, {"filename": "/runtime/syntax/mmp.vim", "start": 18336991, "end": 18338477}, {"filename": "/runtime/syntax/modconf.vim", "start": 18338477, "end": 18339896}, {"filename": "/runtime/syntax/model.vim", "start": 18339896, "end": 18341424}, {"filename": "/runtime/syntax/modsim3.vim", "start": 18341424, "end": 18345047}, {"filename": "/runtime/syntax/modula2.vim", "start": 18345047, "end": 18345582}, {"filename": "/runtime/syntax/modula2/opt/iso.vim", "start": 18345582, "end": 18360888}, {"filename": "/runtime/syntax/modula2/opt/pim.vim", "start": 18360888, "end": 18376007}, {"filename": "/runtime/syntax/modula2/opt/r10.vim", "start": 18376007, "end": 18394781}, {"filename": "/runtime/syntax/modula3.vim", "start": 18394781, "end": 18399317}, {"filename": "/runtime/syntax/mojo.vim", "start": 18399317, "end": 18411964}, {"filename": "/runtime/syntax/monk.vim", "start": 18411964, "end": 18421930}, {"filename": "/runtime/syntax/moo.vim", "start": 18421930, "end": 18431164}, {"filename": "/runtime/syntax/mp.vim", "start": 18431164, "end": 18475694}, {"filename": "/runtime/syntax/mplayerconf.vim", "start": 18475694, "end": 18483181}, {"filename": "/runtime/syntax/mrxvtrc.vim", "start": 18483181, "end": 18492784}, {"filename": "/runtime/syntax/msidl.vim", "start": 18492784, "end": 18496248}, {"filename": "/runtime/syntax/msmessages.vim", "start": 18496248, "end": 18502566}, {"filename": "/runtime/syntax/msql.vim", "start": 18502566, "end": 18504956}, {"filename": "/runtime/syntax/mss.vim", "start": 18504956, "end": 18505808}, {"filename": "/runtime/syntax/mupad.vim", "start": 18505808, "end": 18521315}, {"filename": "/runtime/syntax/murphi.vim", "start": 18521315, "end": 18525894}, {"filename": "/runtime/syntax/mush.vim", "start": 18525894, "end": 18539081}, {"filename": "/runtime/syntax/muttrc.vim", "start": 18539081, "end": 18608628}, {"filename": "/runtime/syntax/mysql.vim", "start": 18608628, "end": 18627981}, {"filename": "/runtime/syntax/n1ql.vim", "start": 18627981, "end": 18640880}, {"filename": "/runtime/syntax/named.vim", "start": 18640880, "end": 18650885}, {"filename": "/runtime/syntax/nanorc.vim", "start": 18650885, "end": 18661505}, {"filename": "/runtime/syntax/nasm.vim", "start": 18661505, "end": 18734995}, {"filename": "/runtime/syntax/nastran.vim", "start": 18734995, "end": 18743499}, {"filename": "/runtime/syntax/natural.vim", "start": 18743499, "end": 18750476}, {"filename": "/runtime/syntax/ncf.vim", "start": 18750476, "end": 18763892}, {"filename": "/runtime/syntax/neomuttlog.vim", "start": 18763892, "end": 18767374}, {"filename": "/runtime/syntax/neomuttrc.vim", "start": 18767374, "end": 18839703}, {"filename": "/runtime/syntax/netrc.vim", "start": 18839703, "end": 18841753}, {"filename": "/runtime/syntax/nginx.vim", "start": 18841753, "end": 18958488}, {"filename": "/runtime/syntax/ninja.vim", "start": 18958488, "end": 18961377}, {"filename": "/runtime/syntax/nix.vim", "start": 18961377, "end": 18973202}, {"filename": "/runtime/syntax/nosyntax.vim", "start": 18973202, "end": 18974023}, {"filename": "/runtime/syntax/nqc.vim", "start": 18974023, "end": 18988091}, {"filename": "/runtime/syntax/nroff.vim", "start": 18988091, "end": 18995696}, {"filename": "/runtime/syntax/nsis.vim", "start": 18995696, "end": 19035011}, {"filename": "/runtime/syntax/nu.vim", "start": 19035011, "end": 19063448}, {"filename": "/runtime/syntax/obj.vim", "start": 19063448, "end": 19066234}, {"filename": "/runtime/syntax/objc.vim", "start": 19066234, "end": 19107999}, {"filename": "/runtime/syntax/objcpp.vim", "start": 19107999, "end": 19108599}, {"filename": "/runtime/syntax/obse.vim", "start": 19108599, "end": 19195078}, {"filename": "/runtime/syntax/ocaml.vim", "start": 19195078, "end": 19225621}, {"filename": "/runtime/syntax/occam.vim", "start": 19225621, "end": 19229937}, {"filename": "/runtime/syntax/odin.vim", "start": 19229937, "end": 19233827}, {"filename": "/runtime/syntax/omnimark.vim", "start": 19233827, "end": 19240204}, {"filename": "/runtime/syntax/ondir.vim", "start": 19240204, "end": 19241258}, {"filename": "/runtime/syntax/opam.vim", "start": 19241258, "end": 19243640}, {"filename": "/runtime/syntax/opencl.vim", "start": 19243640, "end": 19243890}, {"filename": "/runtime/syntax/openroad.vim", "start": 19243890, "end": 19257869}, {"filename": "/runtime/syntax/openscad.vim", "start": 19257869, "end": 19262804}, {"filename": "/runtime/syntax/openvpn.vim", "start": 19262804, "end": 19265110}, {"filename": "/runtime/syntax/opl.vim", "start": 19265110, "end": 19269238}, {"filename": "/runtime/syntax/ora.vim", "start": 19269238, "end": 19296379}, {"filename": "/runtime/syntax/org.vim", "start": 19296379, "end": 19299286}, {"filename": "/runtime/syntax/pacmanlog.vim", "start": 19299286, "end": 19301314}, {"filename": "/runtime/syntax/pamconf.vim", "start": 19301314, "end": 19307642}, {"filename": "/runtime/syntax/pamenv.vim", "start": 19307642, "end": 19308777}, {"filename": "/runtime/syntax/pandoc.vim", "start": 19308777, "end": 19345242}, {"filename": "/runtime/syntax/papp.vim", "start": 19345242, "end": 19349031}, {"filename": "/runtime/syntax/pascal.vim", "start": 19349031, "end": 19365015}, {"filename": "/runtime/syntax/passwd.vim", "start": 19365015, "end": 19367458}, {"filename": "/runtime/syntax/pbtxt.vim", "start": 19367458, "end": 19368831}, {"filename": "/runtime/syntax/pcap.vim", "start": 19368831, "end": 19370327}, {"filename": "/runtime/syntax/pccts.vim", "start": 19370327, "end": 19374420}, {"filename": "/runtime/syntax/pdf.vim", "start": 19374420, "end": 19377959}, {"filename": "/runtime/syntax/perl.vim", "start": 19377959, "end": 19423144}, {"filename": "/runtime/syntax/pf.vim", "start": 19423144, "end": 19433515}, {"filename": "/runtime/syntax/pfmain.vim", "start": 19433515, "end": 19526433}, {"filename": "/runtime/syntax/php.vim", "start": 19526433, "end": 19640999}, {"filename": "/runtime/syntax/phtml.vim", "start": 19640999, "end": 19641150}, {"filename": "/runtime/syntax/pic.vim", "start": 19641150, "end": 19644483}, {"filename": "/runtime/syntax/pike.vim", "start": 19644483, "end": 19666290}, {"filename": "/runtime/syntax/pilrc.vim", "start": 19666290, "end": 19671646}, {"filename": "/runtime/syntax/pine.vim", "start": 19671646, "end": 19687057}, {"filename": "/runtime/syntax/pinfo.vim", "start": 19687057, "end": 19692353}, {"filename": "/runtime/syntax/pkl.vim", "start": 19692353, "end": 19699392}, {"filename": "/runtime/syntax/plaintex.vim", "start": 19699392, "end": 19709693}, {"filename": "/runtime/syntax/pli.vim", "start": 19709693, "end": 19722915}, {"filename": "/runtime/syntax/plm.vim", "start": 19722915, "end": 19728276}, {"filename": "/runtime/syntax/plp.vim", "start": 19728276, "end": 19729543}, {"filename": "/runtime/syntax/plsql.vim", "start": 19729543, "end": 19792401}, {"filename": "/runtime/syntax/po.vim", "start": 19792401, "end": 19800655}, {"filename": "/runtime/syntax/pod.vim", "start": 19800655, "end": 19811542}, {"filename": "/runtime/syntax/poefilter.vim", "start": 19811542, "end": 19817329}, {"filename": "/runtime/syntax/poke.vim", "start": 19817329, "end": 19822518}, {"filename": "/runtime/syntax/postscr.vim", "start": 19822518, "end": 19868691}, {"filename": "/runtime/syntax/pov.vim", "start": 19868691, "end": 19878014}, {"filename": "/runtime/syntax/povini.vim", "start": 19878014, "end": 19881502}, {"filename": "/runtime/syntax/ppd.vim", "start": 19881502, "end": 19882372}, {"filename": "/runtime/syntax/ppwiz.vim", "start": 19882372, "end": 19885152}, {"filename": "/runtime/syntax/pq.vim", "start": 19885152, "end": 19901234}, {"filename": "/runtime/syntax/prescribe.vim", "start": 19901234, "end": 19903617}, {"filename": "/runtime/syntax/privoxy.vim", "start": 19903617, "end": 19909005}, {"filename": "/runtime/syntax/procmail.vim", "start": 19909005, "end": 19911024}, {"filename": "/runtime/syntax/progress.vim", "start": 19911024, "end": 19943087}, {"filename": "/runtime/syntax/prolog.vim", "start": 19943087, "end": 19947581}, {"filename": "/runtime/syntax/promela.vim", "start": 19947581, "end": 19949309}, {"filename": "/runtime/syntax/proto.vim", "start": 19949309, "end": 19952852}, {"filename": "/runtime/syntax/protocols.vim", "start": 19952852, "end": 19954281}, {"filename": "/runtime/syntax/prql.vim", "start": 19954281, "end": 19960717}, {"filename": "/runtime/syntax/ps1.vim", "start": 19960717, "end": 19968226}, {"filename": "/runtime/syntax/ps1xml.vim", "start": 19968226, "end": 19969502}, {"filename": "/runtime/syntax/psf.vim", "start": 19969502, "end": 19973792}, {"filename": "/runtime/syntax/psl.vim", "start": 19973792, "end": 19976320}, {"filename": "/runtime/syntax/ptcap.vim", "start": 19976320, "end": 19980449}, {"filename": "/runtime/syntax/ptx.vim", "start": 19980449, "end": 19982213}, {"filename": "/runtime/syntax/purifylog.vim", "start": 19982213, "end": 19985570}, {"filename": "/runtime/syntax/pymanifest.vim", "start": 19985570, "end": 19986642}, {"filename": "/runtime/syntax/pyrex.vim", "start": 19986642, "end": 19988305}, {"filename": "/runtime/syntax/python.vim", "start": 19988305, "end": 20007920}, {"filename": "/runtime/syntax/python2.vim", "start": 20007920, "end": 20023756}, {"filename": "/runtime/syntax/qb64.vim", "start": 20023756, "end": 20031721}, {"filename": "/runtime/syntax/qf.lua", "start": 20031721, "end": 20032076}, {"filename": "/runtime/syntax/qf.vim", "start": 20032076, "end": 20033109}, {"filename": "/runtime/syntax/qml.vim", "start": 20033109, "end": 20086880}, {"filename": "/runtime/syntax/quake.vim", "start": 20086880, "end": 20095434}, {"filename": "/runtime/syntax/quarto.vim", "start": 20095434, "end": 20096301}, {"filename": "/runtime/syntax/query.lua", "start": 20096301, "end": 20096408}, {"filename": "/runtime/syntax/r.vim", "start": 20096408, "end": 20111514}, {"filename": "/runtime/syntax/racc.vim", "start": 20111514, "end": 20117133}, {"filename": "/runtime/syntax/racket.vim", "start": 20117133, "end": 20152129}, {"filename": "/runtime/syntax/radiance.vim", "start": 20152129, "end": 20159284}, {"filename": "/runtime/syntax/radvd.vim", "start": 20159284, "end": 20161756}, {"filename": "/runtime/syntax/raku.vim", "start": 20161756, "end": 20237449}, {"filename": "/runtime/syntax/raml.vim", "start": 20237449, "end": 20241899}, {"filename": "/runtime/syntax/rapid.vim", "start": 20241899, "end": 20288908}, {"filename": "/runtime/syntax/rasi.vim", "start": 20288908, "end": 20301933}, {"filename": "/runtime/syntax/ratpoison.vim", "start": 20301933, "end": 20319963}, {"filename": "/runtime/syntax/rc.vim", "start": 20319963, "end": 20329987}, {"filename": "/runtime/syntax/rcs.vim", "start": 20329987, "end": 20332120}, {"filename": "/runtime/syntax/rcslog.vim", "start": 20332120, "end": 20332677}, {"filename": "/runtime/syntax/readline.vim", "start": 20332677, "end": 20350164}, {"filename": "/runtime/syntax/rebol.vim", "start": 20350164, "end": 20357331}, {"filename": "/runtime/syntax/redif.vim", "start": 20357331, "end": 20445272}, {"filename": "/runtime/syntax/registry.vim", "start": 20445272, "end": 20448627}, {"filename": "/runtime/syntax/rego.vim", "start": 20448627, "end": 20454309}, {"filename": "/runtime/syntax/remind.vim", "start": 20454309, "end": 20457022}, {"filename": "/runtime/syntax/requirements.vim", "start": 20457022, "end": 20460408}, {"filename": "/runtime/syntax/resolv.vim", "start": 20460408, "end": 20466769}, {"filename": "/runtime/syntax/reva.vim", "start": 20466769, "end": 20474360}, {"filename": "/runtime/syntax/rexx.vim", "start": 20474360, "end": 20489691}, {"filename": "/runtime/syntax/rhelp.vim", "start": 20489691, "end": 20501954}, {"filename": "/runtime/syntax/rib.vim", "start": 20501954, "end": 20504146}, {"filename": "/runtime/syntax/rmd.vim", "start": 20504146, "end": 20515206}, {"filename": "/runtime/syntax/rnc.vim", "start": 20515206, "end": 20517469}, {"filename": "/runtime/syntax/rng.vim", "start": 20517469, "end": 20518286}, {"filename": "/runtime/syntax/rnoweb.vim", "start": 20518286, "end": 20520201}, {"filename": "/runtime/syntax/robots.vim", "start": 20520201, "end": 20521731}, {"filename": "/runtime/syntax/routeros.vim", "start": 20521731, "end": 20525093}, {"filename": "/runtime/syntax/rpcgen.vim", "start": 20525093, "end": 20526855}, {"filename": "/runtime/syntax/rpl.vim", "start": 20526855, "end": 20552555}, {"filename": "/runtime/syntax/rrst.vim", "start": 20552555, "end": 20553861}, {"filename": "/runtime/syntax/rst.vim", "start": 20553861, "end": 20566647}, {"filename": "/runtime/syntax/rtf.vim", "start": 20566647, "end": 20568978}, {"filename": "/runtime/syntax/ruby.vim", "start": 20568978, "end": 20610823}, {"filename": "/runtime/syntax/rust.vim", "start": 20610823, "end": 20633608}, {"filename": "/runtime/syntax/salt.vim", "start": 20633608, "end": 20633913}, {"filename": "/runtime/syntax/samba.vim", "start": 20633913, "end": 20640035}, {"filename": "/runtime/syntax/sas.vim", "start": 20640035, "end": 20674122}, {"filename": "/runtime/syntax/sass.vim", "start": 20674122, "end": 20680534}, {"filename": "/runtime/syntax/sather.vim", "start": 20680534, "end": 20684023}, {"filename": "/runtime/syntax/sbt.vim", "start": 20684023, "end": 20684903}, {"filename": "/runtime/syntax/scala.vim", "start": 20684903, "end": 20696582}, {"filename": "/runtime/syntax/scdoc.vim", "start": 20696582, "end": 20699325}, {"filename": "/runtime/syntax/scheme.vim", "start": 20699325, "end": 20718955}, {"filename": "/runtime/syntax/scilab.vim", "start": 20718955, "end": 20722165}, {"filename": "/runtime/syntax/screen.vim", "start": 20722165, "end": 20733013}, {"filename": "/runtime/syntax/scss.vim", "start": 20733013, "end": 20733539}, {"filename": "/runtime/syntax/sd.vim", "start": 20733539, "end": 20735762}, {"filename": "/runtime/syntax/sdc.vim", "start": 20735762, "end": 20738994}, {"filename": "/runtime/syntax/sdl.vim", "start": 20738994, "end": 20745557}, {"filename": "/runtime/syntax/sed.vim", "start": 20745557, "end": 20751436}, {"filename": "/runtime/syntax/sendpr.vim", "start": 20751436, "end": 20752374}, {"filename": "/runtime/syntax/sensors.vim", "start": 20752374, "end": 20753887}, {"filename": "/runtime/syntax/services.vim", "start": 20753887, "end": 20755710}, {"filename": "/runtime/syntax/setserial.vim", "start": 20755710, "end": 20760939}, {"filename": "/runtime/syntax/sexplib.vim", "start": 20760939, "end": 20764624}, {"filename": "/runtime/syntax/sgf.vim", "start": 20764624, "end": 20766209}, {"filename": "/runtime/syntax/sgml.vim", "start": 20766209, "end": 20775080}, {"filename": "/runtime/syntax/sgmldecl.vim", "start": 20775080, "end": 20777121}, {"filename": "/runtime/syntax/sgmllnx.vim", "start": 20777121, "end": 20778972}, {"filename": "/runtime/syntax/sh.vim", "start": 20778972, "end": 20847107}, {"filename": "/runtime/syntax/shada.vim", "start": 20847107, "end": 20852697}, {"filename": "/runtime/syntax/shaderslang.vim", "start": 20852697, "end": 20882388}, {"filename": "/runtime/syntax/shared/README.txt", "start": 20882388, "end": 20882510}, {"filename": "/runtime/syntax/shared/debarchitectures.vim", "start": 20882510, "end": 20883602}, {"filename": "/runtime/syntax/shared/debversions.vim", "start": 20883602, "end": 20884957}, {"filename": "/runtime/syntax/shared/hgcommitDiff.vim", "start": 20884957, "end": 20906757}, {"filename": "/runtime/syntax/shared/typescriptcommon.vim", "start": 20906757, "end": 21044080}, {"filename": "/runtime/syntax/sicad.vim", "start": 21044080, "end": 21062555}, {"filename": "/runtime/syntax/sieve.vim", "start": 21062555, "end": 21064226}, {"filename": "/runtime/syntax/sil.vim", "start": 21064226, "end": 21072767}, {"filename": "/runtime/syntax/simula.vim", "start": 21072767, "end": 21076138}, {"filename": "/runtime/syntax/sinda.vim", "start": 21076138, "end": 21079804}, {"filename": "/runtime/syntax/sindacmp.vim", "start": 21079804, "end": 21081078}, {"filename": "/runtime/syntax/sindaout.vim", "start": 21081078, "end": 21083039}, {"filename": "/runtime/syntax/sisu.vim", "start": 21083039, "end": 21105219}, {"filename": "/runtime/syntax/skhd.vim", "start": 21105219, "end": 21110652}, {"filename": "/runtime/syntax/skill.vim", "start": 21110652, "end": 21136497}, {"filename": "/runtime/syntax/sl.vim", "start": 21136497, "end": 21140581}, {"filename": "/runtime/syntax/slang.vim", "start": 21140581, "end": 21143883}, {"filename": "/runtime/syntax/slice.vim", "start": 21143883, "end": 21146811}, {"filename": "/runtime/syntax/slpconf.vim", "start": 21146811, "end": 21158602}, {"filename": "/runtime/syntax/slpreg.vim", "start": 21158602, "end": 21163948}, {"filename": "/runtime/syntax/slpspi.vim", "start": 21163948, "end": 21165129}, {"filename": "/runtime/syntax/slrnrc.vim", "start": 21165129, "end": 21177940}, {"filename": "/runtime/syntax/slrnsc.vim", "start": 21177940, "end": 21181038}, {"filename": "/runtime/syntax/sm.vim", "start": 21181038, "end": 21183984}, {"filename": "/runtime/syntax/smarty.vim", "start": 21183984, "end": 21186867}, {"filename": "/runtime/syntax/smcl.vim", "start": 21186867, "end": 21198910}, {"filename": "/runtime/syntax/smil.vim", "start": 21198910, "end": 21204526}, {"filename": "/runtime/syntax/smith.vim", "start": 21204526, "end": 21205319}, {"filename": "/runtime/syntax/sml.vim", "start": 21205319, "end": 21215938}, {"filename": "/runtime/syntax/snnsnet.vim", "start": 21215938, "end": 21218420}, {"filename": "/runtime/syntax/snnspat.vim", "start": 21218420, "end": 21220648}, {"filename": "/runtime/syntax/snnsres.vim", "start": 21220648, "end": 21222581}, {"filename": "/runtime/syntax/snobol4.vim", "start": 21222581, "end": 21227915}, {"filename": "/runtime/syntax/solidity.vim", "start": 21227915, "end": 21250595}, {"filename": "/runtime/syntax/spajson.vim", "start": 21250595, "end": 21252487}, {"filename": "/runtime/syntax/spec.vim", "start": 21252487, "end": 21265785}, {"filename": "/runtime/syntax/specman.vim", "start": 21265785, "end": 21273134}, {"filename": "/runtime/syntax/spice.vim", "start": 21273134, "end": 21275377}, {"filename": "/runtime/syntax/splint.vim", "start": 21275377, "end": 21291163}, {"filename": "/runtime/syntax/spup.vim", "start": 21291163, "end": 21305080}, {"filename": "/runtime/syntax/spyce.vim", "start": 21305080, "end": 21309535}, {"filename": "/runtime/syntax/sql.vim", "start": 21309535, "end": 21310655}, {"filename": "/runtime/syntax/sqlanywhere.vim", "start": 21310655, "end": 21352584}, {"filename": "/runtime/syntax/sqlforms.vim", "start": 21352584, "end": 21359318}, {"filename": "/runtime/syntax/sqlhana.vim", "start": 21359318, "end": 21370345}, {"filename": "/runtime/syntax/sqlinformix.vim", "start": 21370345, "end": 21376247}, {"filename": "/runtime/syntax/sqlj.vim", "start": 21376247, "end": 21379217}, {"filename": "/runtime/syntax/sqloracle.vim", "start": 21379217, "end": 21386981}, {"filename": "/runtime/syntax/sqr.vim", "start": 21386981, "end": 21399993}, {"filename": "/runtime/syntax/squid.vim", "start": 21399993, "end": 21406924}, {"filename": "/runtime/syntax/squirrel.vim", "start": 21406924, "end": 21408424}, {"filename": "/runtime/syntax/srec.vim", "start": 21408424, "end": 21412239}, {"filename": "/runtime/syntax/srt.vim", "start": 21412239, "end": 21414379}, {"filename": "/runtime/syntax/ssa.vim", "start": 21414379, "end": 21416734}, {"filename": "/runtime/syntax/sshallowedsigners.vim", "start": 21416734, "end": 21418341}, {"filename": "/runtime/syntax/sshauthorizedkeys.vim", "start": 21418341, "end": 21419500}, {"filename": "/runtime/syntax/sshconfig.vim", "start": 21419500, "end": 21433019}, {"filename": "/runtime/syntax/sshdconfig.vim", "start": 21433019, "end": 21447051}, {"filename": "/runtime/syntax/sshknownhosts.vim", "start": 21447051, "end": 21448338}, {"filename": "/runtime/syntax/sshpublickey.vim", "start": 21448338, "end": 21449823}, {"filename": "/runtime/syntax/st.vim", "start": 21449823, "end": 21452577}, {"filename": "/runtime/syntax/stata.vim", "start": 21452577, "end": 21479633}, {"filename": "/runtime/syntax/stp.vim", "start": 21479633, "end": 21485060}, {"filename": "/runtime/syntax/strace.vim", "start": 21485060, "end": 21487096}, {"filename": "/runtime/syntax/structurizr.vim", "start": 21487096, "end": 21490330}, {"filename": "/runtime/syntax/stylus.vim", "start": 21490330, "end": 21493535}, {"filename": "/runtime/syntax/sudoers.vim", "start": 21493535, "end": 21532613}, {"filename": "/runtime/syntax/svelte.vim", "start": 21532613, "end": 21538504}, {"filename": "/runtime/syntax/svg.vim", "start": 21538504, "end": 21538940}, {"filename": "/runtime/syntax/svn.vim", "start": 21538940, "end": 21540792}, {"filename": "/runtime/syntax/swayconfig.vim", "start": 21540792, "end": 21555781}, {"filename": "/runtime/syntax/swift.vim", "start": 21555781, "end": 21564609}, {"filename": "/runtime/syntax/swiftgyb.vim", "start": 21564609, "end": 21565431}, {"filename": "/runtime/syntax/swig.vim", "start": 21565431, "end": 21569946}, {"filename": "/runtime/syntax/synload.vim", "start": 21569946, "end": 21571992}, {"filename": "/runtime/syntax/syntax.vim", "start": 21571992, "end": 21573446}, {"filename": "/runtime/syntax/sysctl.vim", "start": 21573446, "end": 21574599}, {"filename": "/runtime/syntax/systemd.vim", "start": 21574599, "end": 21574783}, {"filename": "/runtime/syntax/systemverilog.vim", "start": 21574783, "end": 21578538}, {"filename": "/runtime/syntax/tads.vim", "start": 21578538, "end": 21585400}, {"filename": "/runtime/syntax/tags.vim", "start": 21585400, "end": 21586651}, {"filename": "/runtime/syntax/tak.vim", "start": 21586651, "end": 21589571}, {"filename": "/runtime/syntax/takcmp.vim", "start": 21589571, "end": 21591164}, {"filename": "/runtime/syntax/takout.vim", "start": 21591164, "end": 21593164}, {"filename": "/runtime/syntax/tap.vim", "start": 21593164, "end": 21597304}, {"filename": "/runtime/syntax/tar.vim", "start": 21597304, "end": 21597707}, {"filename": "/runtime/syntax/taskdata.vim", "start": 21597707, "end": 21598914}, {"filename": "/runtime/syntax/taskedit.vim", "start": 21598914, "end": 21600044}, {"filename": "/runtime/syntax/tasm.vim", "start": 21600044, "end": 21605736}, {"filename": "/runtime/syntax/tcl.vim", "start": 21605736, "end": 21623010}, {"filename": "/runtime/syntax/tcsh.vim", "start": 21623010, "end": 21636591}, {"filename": "/runtime/syntax/template.vim", "start": 21636591, "end": 21637106}, {"filename": "/runtime/syntax/tera.vim", "start": 21637106, "end": 21641597}, {"filename": "/runtime/syntax/teraterm.vim", "start": 21641597, "end": 21646577}, {"filename": "/runtime/syntax/terminfo.vim", "start": 21646577, "end": 21651380}, {"filename": "/runtime/syntax/terraform.vim", "start": 21651380, "end": 21652546}, {"filename": "/runtime/syntax/tex.vim", "start": 21652546, "end": 21721953}, {"filename": "/runtime/syntax/texinfo.vim", "start": 21721953, "end": 21723537}, {"filename": "/runtime/syntax/texmf.vim", "start": 21723537, "end": 21725818}, {"filename": "/runtime/syntax/tf.vim", "start": 21725818, "end": 21732723}, {"filename": "/runtime/syntax/thrift.vim", "start": 21732723, "end": 21735299}, {"filename": "/runtime/syntax/tiasm.vim", "start": 21735299, "end": 21738309}, {"filename": "/runtime/syntax/tidy.vim", "start": 21738309, "end": 21751868}, {"filename": "/runtime/syntax/tilde.vim", "start": 21751868, "end": 21753407}, {"filename": "/runtime/syntax/tli.vim", "start": 21753407, "end": 21755065}, {"filename": "/runtime/syntax/tmux.vim", "start": 21755065, "end": 21764707}, {"filename": "/runtime/syntax/tolk.vim", "start": 21764707, "end": 21765724}, {"filename": "/runtime/syntax/toml.vim", "start": 21765724, "end": 21768848}, {"filename": "/runtime/syntax/tpp.vim", "start": 21768848, "end": 21772264}, {"filename": "/runtime/syntax/trasys.vim", "start": 21772264, "end": 21777297}, {"filename": "/runtime/syntax/treetop.vim", "start": 21777297, "end": 21780965}, {"filename": "/runtime/syntax/trustees.vim", "start": 21780965, "end": 21782547}, {"filename": "/runtime/syntax/tsalt.vim", "start": 21782547, "end": 21790871}, {"filename": "/runtime/syntax/tsscl.vim", "start": 21790871, "end": 21795991}, {"filename": "/runtime/syntax/tssgm.vim", "start": 21795991, "end": 21798778}, {"filename": "/runtime/syntax/tssop.vim", "start": 21798778, "end": 21800563}, {"filename": "/runtime/syntax/tsv.vim", "start": 21800563, "end": 21800861}, {"filename": "/runtime/syntax/tt2.vim", "start": 21800861, "end": 21808816}, {"filename": "/runtime/syntax/tt2html.vim", "start": 21808816, "end": 21809434}, {"filename": "/runtime/syntax/tt2js.vim", "start": 21809434, "end": 21810026}, {"filename": "/runtime/syntax/tutor.lua", "start": 21810026, "end": 21810076}, {"filename": "/runtime/syntax/tutor.vim", "start": 21810076, "end": 21813518}, {"filename": "/runtime/syntax/typescript.vim", "start": 21813518, "end": 21815152}, {"filename": "/runtime/syntax/typescriptreact.vim", "start": 21815152, "end": 21819247}, {"filename": "/runtime/syntax/typst.vim", "start": 21819247, "end": 21828626}, {"filename": "/runtime/syntax/uc.vim", "start": 21828626, "end": 21835260}, {"filename": "/runtime/syntax/uci.vim", "start": 21835260, "end": 21836495}, {"filename": "/runtime/syntax/udevconf.vim", "start": 21836495, "end": 21837722}, {"filename": "/runtime/syntax/udevperm.vim", "start": 21837722, "end": 21840131}, {"filename": "/runtime/syntax/udevrules.vim", "start": 21840131, "end": 21847091}, {"filename": "/runtime/syntax/uil.vim", "start": 21847091, "end": 21849912}, {"filename": "/runtime/syntax/unison.vim", "start": 21849912, "end": 21855793}, {"filename": "/runtime/syntax/updatedb.vim", "start": 21855793, "end": 21857013}, {"filename": "/runtime/syntax/upstart.vim", "start": 21857013, "end": 21860164}, {"filename": "/runtime/syntax/upstreamdat.vim", "start": 21860164, "end": 21874212}, {"filename": "/runtime/syntax/upstreaminstalllog.vim", "start": 21874212, "end": 21875183}, {"filename": "/runtime/syntax/upstreamlog.vim", "start": 21875183, "end": 21877803}, {"filename": "/runtime/syntax/upstreamrpt.vim", "start": 21877803, "end": 21892000}, {"filename": "/runtime/syntax/urlshortcut.vim", "start": 21892000, "end": 21892348}, {"filename": "/runtime/syntax/usserverlog.vim", "start": 21892348, "end": 21895466}, {"filename": "/runtime/syntax/usw2kagtlog.vim", "start": 21895466, "end": 21898168}, {"filename": "/runtime/syntax/valgrind.vim", "start": 21898168, "end": 21901384}, {"filename": "/runtime/syntax/vb.vim", "start": 21901384, "end": 21922506}, {"filename": "/runtime/syntax/vdf.vim", "start": 21922506, "end": 21923907}, {"filename": "/runtime/syntax/vera.vim", "start": 21923907, "end": 21943459}, {"filename": "/runtime/syntax/verilog.vim", "start": 21943459, "end": 21948514}, {"filename": "/runtime/syntax/verilogams.vim", "start": 21948514, "end": 21954597}, {"filename": "/runtime/syntax/vgrindefs.vim", "start": 21954597, "end": 21955877}, {"filename": "/runtime/syntax/vhdl.vim", "start": 21955877, "end": 21965535}, {"filename": "/runtime/syntax/vim.vim", "start": 21965535, "end": 22109366}, {"filename": "/runtime/syntax/vim/generated.vim", "start": 22109366, "end": 22141354}, {"filename": "/runtime/syntax/viminfo.vim", "start": 22141354, "end": 22142661}, {"filename": "/runtime/syntax/vimnormal.vim", "start": 22142661, "end": 22143369}, {"filename": "/runtime/syntax/virata.vim", "start": 22143369, "end": 22154982}, {"filename": "/runtime/syntax/vmasm.vim", "start": 22154982, "end": 22163494}, {"filename": "/runtime/syntax/voscm.vim", "start": 22163494, "end": 22170469}, {"filename": "/runtime/syntax/vrml.vim", "start": 22170469, "end": 22183257}, {"filename": "/runtime/syntax/vroom.vim", "start": 22183257, "end": 22186749}, {"filename": "/runtime/syntax/vsejcl.vim", "start": 22186749, "end": 22187753}, {"filename": "/runtime/syntax/vue.vim", "start": 22187753, "end": 22188186}, {"filename": "/runtime/syntax/wat.vim", "start": 22188186, "end": 22193052}, {"filename": "/runtime/syntax/wdiff.vim", "start": 22193052, "end": 22193768}, {"filename": "/runtime/syntax/wdl.vim", "start": 22193768, "end": 22195414}, {"filename": "/runtime/syntax/web.vim", "start": 22195414, "end": 22196528}, {"filename": "/runtime/syntax/webmacro.vim", "start": 22196528, "end": 22199613}, {"filename": "/runtime/syntax/wget.vim", "start": 22199613, "end": 22203842}, {"filename": "/runtime/syntax/wget2.vim", "start": 22203842, "end": 22208320}, {"filename": "/runtime/syntax/whitespace.vim", "start": 22208320, "end": 22208677}, {"filename": "/runtime/syntax/winbatch.vim", "start": 22208677, "end": 22220829}, {"filename": "/runtime/syntax/wks.vim", "start": 22220829, "end": 22221547}, {"filename": "/runtime/syntax/wml.vim", "start": 22221547, "end": 22227278}, {"filename": "/runtime/syntax/wsh.vim", "start": 22227278, "end": 22228520}, {"filename": "/runtime/syntax/wsml.vim", "start": 22228520, "end": 22233920}, {"filename": "/runtime/syntax/wvdial.vim", "start": 22233920, "end": 22235013}, {"filename": "/runtime/syntax/xbl.vim", "start": 22235013, "end": 22235581}, {"filename": "/runtime/syntax/xcompose.vim", "start": 22235581, "end": 22236864}, {"filename": "/runtime/syntax/xdefaults.vim", "start": 22236864, "end": 22242751}, {"filename": "/runtime/syntax/xf86conf.vim", "start": 22242751, "end": 22257851}, {"filename": "/runtime/syntax/xhtml.vim", "start": 22257851, "end": 22258037}, {"filename": "/runtime/syntax/xinetd.vim", "start": 22258037, "end": 22272245}, {"filename": "/runtime/syntax/xkb.vim", "start": 22272245, "end": 22275558}, {"filename": "/runtime/syntax/xmath.vim", "start": 22275558, "end": 22286484}, {"filename": "/runtime/syntax/xml.vim", "start": 22286484, "end": 22295355}, {"filename": "/runtime/syntax/xmodmap.vim", "start": 22295355, "end": 22341866}, {"filename": "/runtime/syntax/xpm.vim", "start": 22341866, "end": 22346849}, {"filename": "/runtime/syntax/xpm2.vim", "start": 22346849, "end": 22351546}, {"filename": "/runtime/syntax/xquery.vim", "start": 22351546, "end": 22358737}, {"filename": "/runtime/syntax/xs.vim", "start": 22358737, "end": 22605689}, {"filename": "/runtime/syntax/xsd.vim", "start": 22605689, "end": 22606894}, {"filename": "/runtime/syntax/xslt.vim", "start": 22606894, "end": 22608323}, {"filename": "/runtime/syntax/xxd.vim", "start": 22608323, "end": 22609185}, {"filename": "/runtime/syntax/yacc.vim", "start": 22609185, "end": 22614859}, {"filename": "/runtime/syntax/yaml.vim", "start": 22614859, "end": 22631105}, {"filename": "/runtime/syntax/z8a.vim", "start": 22631105, "end": 22633999}, {"filename": "/runtime/syntax/zathurarc.vim", "start": 22633999, "end": 22638206}, {"filename": "/runtime/syntax/zig.vim", "start": 22638206, "end": 22649236}, {"filename": "/runtime/syntax/zimbu.vim", "start": 22649236, "end": 22655750}, {"filename": "/runtime/syntax/zip.lua", "start": 22655750, "end": 22655975}, {"filename": "/runtime/syntax/zir.vim", "start": 22655975, "end": 22657586}, {"filename": "/runtime/syntax/zserio.vim", "start": 22657586, "end": 22661980}, {"filename": "/runtime/syntax/zsh.vim", "start": 22661980, "end": 22685622}, {"filename": "/runtime/tutor/en/vim-01-beginner.tutor", "start": 22685622, "end": 22720583}, {"filename": "/runtime/tutor/en/vim-02-beginner.tutor", "start": 22720583, "end": 22731104}, {"filename": "/runtime/tutor/ja/vim-01-beginner.tutor", "start": 22731104, "end": 22778612}, {"filename": "/runtime/tutor/ja/vim-02-beginner.tutor", "start": 22778612, "end": 22786652}, {"filename": "/runtime/tutor/tutor.tutor", "start": 22786652, "end": 22792705}, {"filename": "/runtime/tutor/zh/vim-01-beginner.tutor", "start": 22792705, "end": 22828395}, {"filename": "/runtime/tutor/zh/vim-02-beginner.tutor", "start": 22828395, "end": 22835019}, {"filename": "/runtime/windows_icon.rc", "start": 22835019, "end": 22835247}], "remote_package_size": 22835247});

  })();

// end include: /tmp/tmpwyme55lu.js
// include: /tmp/tmpy99c42ju.js

    // All the pre-js content up to here must remain later on, we need to run
    // it.
    if ((typeof ENVIRONMENT_IS_WASM_WORKER != 'undefined' && ENVIRONMENT_IS_WASM_WORKER) || (typeof ENVIRONMENT_IS_PTHREAD != 'undefined' && ENVIRONMENT_IS_PTHREAD) || (typeof ENVIRONMENT_IS_AUDIO_WORKLET != 'undefined' && ENVIRONMENT_IS_AUDIO_WORKLET)) Module['preRun'] = [];
    var necessaryPreJSTasks = Module['preRun'].slice();
  // end include: /tmp/tmpy99c42ju.js
// include: /tmp/tmp_oa7foap.js

    if (!Module['preRun']) throw 'Module.preRun should exist because file support used it; did a pre-js delete it?';
    necessaryPreJSTasks.forEach((task) => {
      if (Module['preRun'].indexOf(task) < 0) throw 'All preRun tasks that exist before user pre-js code should remain after; did you replace Module or modify Module.preRun?';
    });
  // end include: /tmp/tmp_oa7foap.js


var programArgs = [];
var thisProgram = './this.program';
var quit_ = (status, toThrow) => {
  throw toThrow;
};

if (typeof __filename != 'undefined') { // Node
  _scriptName = __filename;
} else
if (ENVIRONMENT_IS_WORKER) {
  _scriptName = self.location.href;
}

// `/` should be present at the end if `scriptDirectory` is not empty
var scriptDirectory = '';
function locateFile(path) {
  if (Module['locateFile']) {
    return Module['locateFile'](path, scriptDirectory);
  }
  return scriptDirectory + path;
}

// Hooks that are implemented differently in different runtime environments.
var readAsync, readBinary;

if (ENVIRONMENT_IS_NODE) {
  const isNode = globalThis.process?.versions?.node && globalThis.process?.type != 'renderer';
  if (!isNode) throw new Error('not compiled for this environment (did you build to HTML and try to run it not on the web, or set ENVIRONMENT to something - like node - and run it someplace else - like on the web?)');

  // These modules will usually be used on Node.js. Load them eagerly to avoid
  // the complexity of lazy-loading.
  var fs = require('node:fs');

  scriptDirectory = __dirname + '/';

// include: node_shell_read.js
readBinary = (filename) => {
  // We need to re-wrap `file://` strings to URLs.
  filename = isFileURI(filename) ? new URL(filename) : filename;
  var ret = fs.readFileSync(filename);
  assert(Buffer.isBuffer(ret));
  return ret;
};

readAsync = async (filename, binary = true) => {
  // See the comment in the `readBinary` function.
  filename = isFileURI(filename) ? new URL(filename) : filename;
  var ret = fs.readFileSync(filename, binary ? undefined : 'utf8');
  assert(binary ? Buffer.isBuffer(ret) : typeof ret == 'string');
  return ret;
};
// end include: node_shell_read.js
  if (process.argv.length > 1) {
    thisProgram = process.argv[1].replace(/\\/g, '/');
  }

  programArgs = process.argv.slice(2);

  quit_ = (status, toThrow) => {
    process.exitCode = status;
    throw toThrow;
  };

} else
if (ENVIRONMENT_IS_SHELL) {

} else

// Note that this includes Node.js workers when relevant (pthreads is enabled).
// Node.js workers are detected as a combination of ENVIRONMENT_IS_WORKER and
// ENVIRONMENT_IS_NODE.
if (ENVIRONMENT_IS_WEB || ENVIRONMENT_IS_WORKER) {
  try {
    scriptDirectory = new URL('.', _scriptName).href; // includes trailing slash
  } catch {
    // Must be a `blob:` or `data:` URL (e.g. `blob:http://site.com/etc/etc`), we cannot
    // infer anything from them.
  }

  if (!(globalThis.window || globalThis.WorkerGlobalScope)) throw new Error('not compiled for this environment (did you build to HTML and try to run it not on the web, or set ENVIRONMENT to something - like node - and run it someplace else - like on the web?)');

  // Differentiate the Web Worker from the Node Worker case, as reading must
  // be done differently.
  if (!ENVIRONMENT_IS_NODE)
  {
// include: web_or_worker_shell_read.js
if (ENVIRONMENT_IS_WORKER) {
    readBinary = (url) => {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', url, false);
      xhr.responseType = 'arraybuffer';
      xhr.send(null);
      return new Uint8Array(/** @type{!ArrayBuffer} */(xhr.response));
    };
  }

  readAsync = async (url) => {
    // Fetch has some additional restrictions over XHR, like it can't be used on a file:// url.
    // See https://github.com/github/fetch/pull/92#issuecomment-140665932
    // Cordova or Electron apps are typically loaded from a file:// url.
    // So use XHR on webview if URL is a file URL.
    if (isFileURI(url)) {
      return new Promise((resolve, reject) => {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', url, true);
        xhr.responseType = 'arraybuffer';
        xhr.onload = () => {
          if (xhr.status == 200 || (xhr.status == 0 && xhr.response)) { // file URLs can return 0
            resolve(xhr.response);
            return;
          }
          reject(xhr.status);
        };
        xhr.onerror = reject;
        xhr.send(null);
      });
    }
    var response = await fetch(url, { credentials: 'same-origin' });
    if (response.ok) {
      return response.arrayBuffer();
    }
    throw new Error(response.status + ' : ' + response.url);
  };
// end include: web_or_worker_shell_read.js
  }
} else
{
  throw new Error('environment detection error');
}

// Set up the out() and err() hooks, which are how we can print to stdout or
// stderr, respectively.
// Normally just binding console.log/console.error here works fine, but
// under node (with workers) we see missing/out-of-order messages so route
// directly to stdout and stderr.
// See https://github.com/emscripten-core/emscripten/issues/14804
var defaultPrint = console.log.bind(console);
var defaultPrintErr = console.error.bind(console);
if (ENVIRONMENT_IS_NODE) {
  var utils = require('node:util');
  var stringify = (a) => typeof a == 'object' ? utils.inspect(a) : a;
  defaultPrint = (...args) => fs.writeSync(1, args.map(stringify).join(' ') + '\n');
  defaultPrintErr = (...args) => fs.writeSync(2, args.map(stringify).join(' ') + '\n');
}
var out = defaultPrint;
var err = defaultPrintErr;


var PROXYFS = 'PROXYFS is no longer included by default; build with -lproxyfs.js';
var WORKERFS = 'WORKERFS is no longer included by default; build with -lworkerfs.js';
var FETCHFS = 'FETCHFS is no longer included by default; build with -lfetchfs.js';
var ICASEFS = 'ICASEFS is no longer included by default; build with -licasefs.js';
var JSFILEFS = 'JSFILEFS is no longer included by default; build with -ljsfilefs.js';
var OPFS = 'OPFS is no longer included by default; build with -lopfs.js';

var NODEFS = 'NODEFS is no longer included by default; build with -lnodefs.js';

// perform assertions in shell.js after we set up out() and err(), as otherwise
// if an assertion fails it cannot print the message
assert(
  ENVIRONMENT_IS_WEB || ENVIRONMENT_IS_WORKER || ENVIRONMENT_IS_NODE, 'pthreads do not work in this environment yet (need Web Workers, or an alternative to them)');

assert(!ENVIRONMENT_IS_SHELL, 'shell environment detected but not enabled at build time (add `shell` to `-sENVIRONMENT` to enable)');

// end include: shell.js

// include: preamble.js
// === Preamble library stuff ===

// Documentation for the public APIs defined in this file must be updated in:
//    site/source/docs/api_reference/preamble.js.rst
// A prebuilt local version of the documentation is available at:
//    site/build/text/docs/api_reference/preamble.js.txt
// You can also build docs locally as HTML or other formats in site/
// An online HTML version (which may be of a different version of Emscripten)
//    is up at http://kripken.github.io/emscripten-site/docs/api_reference/preamble.js.html

var wasmBinary;

if (!globalThis.WebAssembly) {
  err('no native wasm support detected');
}

// Wasm globals

// For sending to workers.
var wasmModule;

//========================================
// Runtime essentials
//========================================

// whether we are quitting the application. no code should run after this.
// set in exit() and abort()
var ABORT = false;

// set by exit() and abort().  Passed to 'onExit' handler.
// NOTE: This is also used as the process return code in shell environments
// but only when noExitRuntime is false.
var EXITSTATUS;

// In STRICT mode, we only define assert() when ASSERTIONS is set.  i.e. we
// don't define it at all in release modes.  This matches the behaviour of
// MINIMAL_RUNTIME.
// TODO(sbc): Make this the default even without STRICT enabled.
/** @type {function(*, string=)} */
function assert(condition, text) {
  if (!condition) {
    abort('Assertion failed' + (text ? ': ' + text : ''));
  }
}

// We used to include malloc/free by default in the past. Show a helpful error in
// builds with assertions.

/**
 * Indicates whether filename is delivered via file protocol (as opposed to http/https)
 * @noinline
 */
var isFileURI = (filename) => filename.startsWith('file://');

// include: runtime_common.js
// include: runtime_stack_check.js
// Initializes the stack cookie. Called at the startup of main and at the startup of each thread in pthreads mode.
function writeStackCookie() {
  var max = _emscripten_stack_get_end();
  assert((max & 3) == 0);
  // If the stack ends at address zero we write our cookies 4 bytes into the
  // stack.  This prevents interference with SAFE_HEAP and ASAN which also
  // monitor writes to address zero.
  if (max == 0) {
    max += 4;
  }
  // The stack grow downwards towards _emscripten_stack_get_end.
  // We write cookies to the final two words in the stack and detect if they are
  // ever overwritten.
  HEAPU32[((max)>>2)] = 0x02135467;
  HEAPU32[(((max)+(4))>>2)] = 0x89BACDFE;
  // Also test the global address 0 for integrity.
  HEAPU32[((0)>>2)] = 1668509029;
}

function checkStackCookie() {
  if (ABORT) return;
  var max = _emscripten_stack_get_end();
  // See writeStackCookie().
  if (max == 0) {
    max += 4;
  }
  var cookie1 = HEAPU32[((max)>>2)];
  var cookie2 = HEAPU32[(((max)+(4))>>2)];
  if (cookie1 != 0x02135467 || cookie2 != 0x89BACDFE) {
    abort(`Stack overflow! Stack cookie has been overwritten at ${ptrToString(max)}, expected hex dwords 0x89BACDFE and 0x2135467, but received ${ptrToString(cookie2)} ${ptrToString(cookie1)}`);
  }
  // Also test the global address 0 for integrity.
  if (HEAPU32[((0)>>2)] != 0x63736d65 /* 'emsc' */) {
    abort('Runtime error: The application has corrupted its heap memory area (address zero)!');
  }
}
// end include: runtime_stack_check.js
// include: runtime_exceptions.js
// Base Emscripten EH error class
class EmscriptenEH extends Error {}

class EmscriptenSjLj extends EmscriptenEH {}

class CppException extends EmscriptenEH {
  constructor(excPtr) {
    super(excPtr);
    this.excPtr = excPtr;
    const excInfo = getExceptionMessage(this);
    this.name = excInfo[0];
    this.message = excInfo[1];
  }
}

// end include: runtime_exceptions.js
// include: runtime_debug.js
var runtimeDebug = true; // Switch to false at runtime to disable logging at the right times

// Used by XXXXX_DEBUG settings to output debug messages.
function dbg(...args) {
  if (!runtimeDebug && typeof runtimeDebug != 'undefined') return;
  // Avoid using the console for debugging in multi-threaded node applications
  // See https://github.com/emscripten-core/emscripten/issues/14804
  if (ENVIRONMENT_IS_NODE) {
    // TODO(sbc): Unify with err/out implementation in shell.sh.
    var fs = require('node:fs');
    var utils = require('node:util');
    function stringify(a) {
      switch (typeof a) {
        case 'object': return utils.inspect(a);
        case 'undefined': return 'undefined';
      }
      return a;
    }
    fs.writeSync(2, args.map(stringify).join(' ') + '\n');
  } else
  // TODO(sbc): Make this configurable somehow.  Its not always convenient for
  // logging to show up as warnings.
  console.warn(...args);
}

// Endianness check
(() => {
  var h16 = new Int16Array(1);
  var h8 = new Int8Array(h16.buffer);
  h16[0] = 0x6373;
  if (h8[0] !== 0x73 || h8[1] !== 0x63) abort('Runtime error: expected the system to be little-endian! (Run with -sSUPPORT_BIG_ENDIAN to bypass)');
})();

function consumedModuleProp(prop) {
  var value = Module[prop];
  var msg = `Attempt to modify \`Module.${prop}\` after it has already been processed.  This can happen, for example, when code is injected via '--post-js' rather than '--pre-js'`;
  if (Array.isArray(value)) {
    value = new Proxy(value, {
      set(target, key, val) {
        abort(msg);
        return false;
      },
      defineProperty(target, key, descriptor) {
        abort(msg);
        return false;
      },
      deleteProperty(target, key) {
        abort(msg);
        return false;
      }
    });
  }
  Object.defineProperty(Module, prop, {
    configurable: true,
    get() { return value; },
    set() {
      abort(msg);
    }
  });
}

function makeInvalidEarlyAccess(name) {
  return () => assert(false, `call to '${name}' via reference taken before Wasm module initialization`);

}

function ignoredModuleProp(prop) {
  if (Object.getOwnPropertyDescriptor(Module, prop)) {
    abort(`\`Module.${prop}\` was supplied but \`${prop}\` not included in INCOMING_MODULE_JS_API`);
  }
}

// forcing the filesystem exports a few things by default
function isExportedByForceFilesystem(name) {
  return name === 'FS_createPath' ||
         name === 'FS_createDataFile' ||
         name === 'FS_createPreloadedFile' ||
         name === 'FS_preloadFile' ||
         name === 'FS_unlink' ||
         name === 'addRunDependency' ||
         // The old FS has some functionality that WasmFS lacks.
         name === 'FS_createLazyFile' ||
         name === 'FS_createDevice' ||
         name === 'removeRunDependency';
}

function missingLibrarySymbol(sym) {

  // Any symbol that is not included from the JS library is also (by definition)
  // not exported on the Module object.
  unexportedRuntimeSymbol(sym);
}

function unexportedRuntimeSymbol(sym) {
  if (ENVIRONMENT_IS_PTHREAD) {
    return;
  }
  if (!Object.getOwnPropertyDescriptor(Module, sym)) {
    Object.defineProperty(Module, sym, {
      configurable: true,
      get() {
        var msg = `'${sym}' was not exported. add it to EXPORTED_RUNTIME_METHODS (see the Emscripten FAQ)`;
        if (isExportedByForceFilesystem(sym)) {
          msg += '. Alternatively, forcing filesystem support (-sFORCE_FILESYSTEM) can export this for you';
        }
        abort(msg);
      },
    });
  }
}

/**
 * Override `err`/`out`/`dbg` to report thread / worker information
 */
function initWorkerLogging() {
  function getLogPrefix() {
    var t = 0;
    if (runtimeInitialized && typeof _pthread_self != 'undefined'
    ) {
      t = _pthread_self();
    }
    return `w:${workerID},t:${ptrToString(t)}:`;
  }

  // Prefix all dbg() messages with the calling thread info.
  var origDbg = dbg;
  dbg = (...args) => origDbg(getLogPrefix(), ...args);
}

initWorkerLogging();

// end include: runtime_debug.js
if (ENVIRONMENT_IS_NODE && (ENVIRONMENT_IS_PTHREAD)) {
  // Create as web-worker-like an environment as we can.
  globalThis.self = globalThis;
  var parentPort = worker_threads.parentPort;
  // Deno and Bun already have `postMessage` defined on the global scope and
  // deliver messages to `globalThis.onmessage`, so we must not duplicate that
  // behavior here if `postMessage` is already present.
  if (!globalThis.postMessage) {
    parentPort.on('message', (msg) => globalThis.onmessage?.({ data: msg }));
    globalThis.postMessage = (msg) => parentPort.postMessage(msg);
  }
  // Node.js Workers do not pass postMessage()s and uncaught exception events to the parent
  // thread necessarily in the same order where they were generated in sequential program order.
  // See https://github.com/nodejs/node/issues/59617
  // To remedy this, capture all uncaughtExceptions in the Worker, and sequentialize those over
  // to the same postMessage pipe that other messages use.
  process.on("uncaughtException", (err) => {
    postMessage({ cmd: 8, error: err });
    // Also shut down the Worker to match the same semantics as if this uncaughtException
    // handler was not registered.
    // (n.b. this will not shut down the whole Node.js app process, but just the Worker)
    process.exit(1);
  });
}

// include: runtime_pthread.js
// Pthread Web Worker handling code.
// This code runs only on pthread web workers and handles pthread setup
// and communication with the main thread via postMessage.

// Unique ID of the current pthread worker (zero on non-pthread-workers
// including the main thread).
var workerID = 0;

var startWorker;

if (ENVIRONMENT_IS_PTHREAD) {
  // Thread-local guard variable for one-time init of the JS state
  var initializedJS = false;

  // Turn unhandled rejected promises into errors so that the main thread will be
  // notified about them.
  self.onunhandledrejection = (e) => { throw e.reason || e; };

  function handleMessage(e) {
    try {
      var msgData = e.data;
      //dbg('msgData: ' + Object.keys(msgData));
      var cmd = msgData.cmd;
      if (cmd == 1) { // Preload command that is called once per worker to parse and load the Emscripten code.
        workerID = msgData.workerID;

        // Until we initialize the runtime, queue up any further incoming messages.
        let messageQueue = [];
        self.onmessage = (e) => messageQueue.push(e);

        // And add a callback for when the runtime is initialized.
        startWorker = () => {
          // Notify the main thread that this thread has loaded.
          postMessage({ cmd: 3 });
          // Process any messages that were queued before the thread was ready.
          for (let msg of messageQueue) {
            handleMessage(msg);
          }
          // Restore the real message handler.
          self.onmessage = handleMessage;
        };

        // Use `const` here to ensure that the variable is scoped only to
        // that iteration, allowing safe reference from a closure.
        for (const handler of msgData.handlers) {
          // If the main module has a handler for a certain event, but no
          // handler exists on the pthread worker, then proxy that handler
          // back to the main thread.
          if (!Module[handler] || Module[handler].proxy) {
            Module[handler] = (...args) => {
              postMessage({ cmd: 9, handler, args: args });
            }
            // Rebind the out / err handlers if needed
            if (handler == 'print') out = Module[handler];
            if (handler == 'printErr') err = Module[handler];
          }
        }

        wasmMemory = msgData.wasmMemory;
        updateMemoryViews();

        wasmModule = msgData.wasmModule;
        createWasm();
        run();
      } else if (cmd == 2) {
        assert(msgData.pthread_ptr);
        assert(wasmMemory, "CMD_RUN received before CMD_LOAD");
        // Call inside JS module to set up the stack frame for this pthread in JS module scope.
        // This needs to be the first thing that we do, as we cannot call to any C/C++ functions
        // until the thread stack is initialized.
        establishStackSpace(msgData.pthread_ptr);

        // Pass the thread address to wasm to store it for fast access.
        __emscripten_thread_init(msgData.pthread_ptr, /*is_main=*/0, /*is_runtime=*/0, /*can_block=*/1, 0, 0);

        PThread.threadInitTLS();

        // Await mailbox notifications with `Atomics.waitAsync` so we can start
        // using the fast `Atomics.notify` notification path.
        __emscripten_thread_mailbox_await(msgData.pthread_ptr);

        if (!initializedJS) {
          initializedJS = true;
        }

        try {
          invokeEntryPoint(msgData.start_routine, msgData.arg);
        } catch(ex) {
          if (ex != 'unwind') {
            // The pthread "crashed".  Do not call `_emscripten_thread_exit` (which
            // would make this thread joinable).  Instead, re-throw the exception
            // and let the top level handler propagate it back to the main thread.
            throw ex;
          }
        }
      } else if (cmd == 4) {
        if (initializedJS) {
          checkMailbox();
        }
      } else if (cmd) {
        // The received message looks like something that should be handled by this message
        // handler, (since there is a cmd field present), but is not one of the
        // recognized commands:
        err(`worker: received unknown command ${cmd}`);
        err(msgData);
      }
    } catch(ex) {
      err(`worker: onmessage() captured an uncaught exception: ${ex}`);
      if (ex?.stack) err(ex.stack);
      if (runtimeInitialized) __emscripten_thread_crashed();
      throw ex;
    }
  };

  self.onmessage = handleMessage;

} // ENVIRONMENT_IS_PTHREAD
// end include: runtime_pthread.js
// Memory management

var runtimeInitialized = false;



function updateMemoryViews() {
  // When memory growth is disabled this function should be called exactly once.
  assert(!HEAP8, 'updateMemoryViews should only be called once when ALLOW_MEMORY_GROWTH=0');
  var b = wasmMemory.buffer;
  HEAP8 = new Int8Array(b);
  HEAP16 = new Int16Array(b);
  Module['HEAPU8'] = HEAPU8 = new Uint8Array(b);
  HEAPU16 = new Uint16Array(b);
  HEAP32 = new Int32Array(b);
  HEAPU32 = new Uint32Array(b);
  HEAPF32 = new Float32Array(b);
  HEAPF64 = new Float64Array(b);
  HEAP64 = new BigInt64Array(b);
  HEAPU64 = new BigUint64Array(b);
}

// In non-standalone/normal mode, we create the memory here.
// include: runtime_init_memory.js
// Create the wasm memory. (Note: this only applies if IMPORTED_MEMORY is defined)

// check for full engine support (use string 'subarray' to avoid closure compiler confusion)

function initMemory() {

  if ((ENVIRONMENT_IS_PTHREAD)) { return }

  {
    var INITIAL_MEMORY = 268435456;

    assert(INITIAL_MEMORY >= 8388608, `INITIAL_MEMORY should be larger than STACK_SIZE, was ${INITIAL_MEMORY}! (STACK_SIZE=8388608)`);
    /** @suppress {checkTypes} */
    wasmMemory = new WebAssembly.Memory({
      'initial': INITIAL_MEMORY / 65536,
      'maximum': INITIAL_MEMORY / 65536,
      'shared': true,
    });
  }

  updateMemoryViews();
}

// end include: runtime_init_memory.js

// include: memoryprofiler.js
// end include: memoryprofiler.js
// end include: runtime_common.js
assert(globalThis.Int32Array && globalThis.Float64Array && Int32Array.prototype.subarray && Int32Array.prototype.set,
       'JS engine does not provide full typed array support');

function preRun() {
  assert(!ENVIRONMENT_IS_PTHREAD); // PThreads reuse the runtime from the main thread.
  var preRun = Module['preRun'];
  if (preRun) {
    if (typeof preRun == 'function') preRun = [preRun];
    onPreRuns.push(...preRun);
  }
  consumedModuleProp('preRun');
  // Begin ATPRERUNS hooks
  callRuntimeCallbacks(onPreRuns);
  // End ATPRERUNS hooks
}

function initRuntime() {
  assert(!runtimeInitialized);
  runtimeInitialized = true;

  if (ENVIRONMENT_IS_PTHREAD) return startWorker();

  setStackLimits();

  checkStackCookie();

  // Begin ATINITS hooks
  SOCKFS.root = FS.mount(SOCKFS, {}, null);
if (!Module['noFSInit'] && !FS.initialized) FS.init();
TTY.init();
PIPEFS.root = FS.mount(PIPEFS, {}, null);
  // End ATINITS hooks

  wasmExports['__wasm_call_ctors']();

  // Begin ATPOSTCTORS hooks
  FS.ignorePermissions = false;
  // End ATPOSTCTORS hooks

  checkStackCookie();
}

function postRun() {
  checkStackCookie();

  var postRun = Module['postRun'];
  if (postRun) {
    if (typeof postRun == 'function') postRun = [postRun];
    onPostRuns.push(...postRun);
  }
  consumedModuleProp('postRun');

  // Begin ATPOSTRUNS hooks
  callRuntimeCallbacks(onPostRuns);
  // End ATPOSTRUNS hooks
}

/**
 * @param {string|number=} what
 */
function abort(what) {
  Module['onAbort']?.(what);

  what = `Aborted(${what})`;
  // TODO(sbc): Should we remove printing and leave it up to whoever
  // catches the exception?
  err(what);

  ABORT = true;

  // Use a wasm runtime error, because a JS error might be seen as a foreign
  // exception, which means we'd run destructors on it. We need the error to
  // simply make the program stop.
  // FIXME This approach does not work in Wasm EH because it currently does not assume
  // all RuntimeErrors are from traps; it decides whether a RuntimeError is from
  // a trap or not based on a hidden field within the object. So at the moment
  // we don't have a way of throwing a wasm trap from JS. TODO Make a JS API that
  // allows this in the wasm spec.

  // Suppress closure compiler warning here. Closure compiler's builtin extern
  // definition for WebAssembly.RuntimeError claims it takes no arguments even
  // though it can.
  // TODO(https://github.com/google/closure-compiler/pull/3913): Remove if/when upstream closure gets fixed.
  /** @suppress {checkTypes} */
  var e = new WebAssembly.RuntimeError(what);

  // Throw the error whether or not MODULARIZE is set because abort is used
  // in code paths apart from instantiation where an exception is expected
  // to be thrown when abort is called.
  throw e;
}

function createExportWrapper(name, func, nargs) {
  assert(func);
  return (...args) => {
    assert(runtimeInitialized, `native function \`${name}\` called before runtime initialization`);
    // Only assert for too many arguments. Too few can be valid since the missing arguments will be zero filled.
    assert(args.length <= nargs, `native function \`${name}\` called with ${args.length} args but expects ${nargs}`);
    return func(...args);
  };
}

var wasmBinaryFile;

function findWasmBinary() {
  return locateFile('nvim.wasm');
}

function getBinarySync(file) {
  if (readBinary) {
    return readBinary(file);
  }
  // Throwing a plain string here, even though it not normally advisable since
  // this gets turning into an `abort` in instantiateArrayBuffer.
  throw 'both async and sync fetching of the wasm failed';
}

async function getWasmBinary(binaryFile) {
  // If we don't have the binary yet, load it asynchronously using readAsync.
  if (!wasmBinary) {
    // Fetch the binary using readAsync
    try {
      var response = await readAsync(binaryFile);
      return new Uint8Array(response);
    } catch {
      // Fall back to getBinarySync below;
    }
  }

  // Otherwise, getBinarySync should be able to get it synchronously
  return getBinarySync(binaryFile);
}

async function instantiateArrayBuffer(binaryFile, imports) {
  try {
    var binary = await getWasmBinary(binaryFile);
    var instance = await WebAssembly.instantiate(binary, imports);
    return instance;
  } catch (reason) {
    err(`failed to asynchronously prepare wasm: ${reason}`);

    // Warn on some common problems.
    if (isFileURI(binaryFile)) {
      err(`warning: Loading from a file URI (${binaryFile}) is not supported in most browsers. See https://emscripten.org/docs/getting_started/FAQ.html#how-do-i-run-a-local-webserver-for-testing-why-does-my-program-stall-in-downloading-or-preparing`);
    }
    abort(reason);
  }
}

async function instantiateAsync(binary, binaryFile, imports) {
  if (!binary
      // Don't use streaming for file:// delivered objects in a webview, fetch them synchronously.
      && !isFileURI(binaryFile)
      // Avoid instantiateStreaming() on Node.js environment for now, as while
      // Node.js v18.1.0 implements it, it does not have a full fetch()
      // implementation yet.
      //
      // Reference:
      //   https://github.com/emscripten-core/emscripten/pull/16917
      && !ENVIRONMENT_IS_NODE
     ) {
    try {
      var response = fetch(binaryFile, { credentials: 'same-origin' });
      var instantiationResult = await WebAssembly.instantiateStreaming(response, imports);
      return instantiationResult;
    } catch (reason) {
      // We expect the most common failure cause to be a bad MIME type for the binary,
      // in which case falling back to ArrayBuffer instantiation should work.
      err(`wasm streaming compile failed: ${reason}`);
      err('falling back to ArrayBuffer instantiation');
      // fall back of instantiateArrayBuffer below
    };
  }
  return instantiateArrayBuffer(binaryFile, imports);
}

function getWasmImports() {
  assignWasmImports();
  // prepare imports
  var imports = {
    'env': wasmImports,
    'wasi_snapshot_preview1': wasmImports,
  };
  return imports;
}

// Create the wasm instance.
// Receives the wasm imports, returns the exports.
async function createWasm() {
  // Load the wasm module and create an instance of using native support in the JS engine.
  // handle a generated wasm instance, receiving its exports and
  // performing other necessary setup
  function receiveInstance(instance, module) {
    wasmExports = instance.exports;

    registerTLSInit(wasmExports['_emscripten_tls_init']);

    assignWasmExports(wasmExports);

    // We now have the Wasm module loaded up, keep a reference to the compiled module so we can post it to the workers.
    wasmModule = module;
    return wasmExports;
  }

  // Prefer streaming instantiation if available.
  // Async compilation can be confusing when an error on the page overwrites Module
  // (for example, if the order of elements is wrong, and the one defining Module is
  // later), so we save Module and check it later.
  var trueModule = Module;
  function receiveInstantiationResult(result) {
    // 'result' is a ResultObject object which has both the module and instance.
    // receiveInstance() will swap in the exports (to Module.asm) so they can be called
    assert(Module === trueModule, 'the Module object should not be replaced during async compilation - perhaps the order of HTML elements is wrong?');
    trueModule = null;
    return receiveInstance(result['instance'], result['module']);
  }

  var info = getWasmImports();

  // User shell pages can write their own Module.instantiateWasm = function(imports, successCallback) callback
  // to manually instantiate the Wasm module themselves. This allows pages to
  // run the instantiation parallel to any other async startup actions they are
  // performing.
  // Also pthreads and wasm workers initialize the wasm instance through this
  // path.
  var instantiateWasm = Module['instantiateWasm'];
  if (instantiateWasm) {
    return new Promise((resolve) => {
      try {
        instantiateWasm(info, (inst, mod) => resolve(receiveInstance(inst, mod)));
      } catch(e) {
        err(`Module.instantiateWasm callback failed with error: ${e}`);
        throw e;
      }
    });
  }

  if ((ENVIRONMENT_IS_PTHREAD)) {
    // Instantiate from the module that was received via postMessage from
    // the main thread. We can just use sync instantiation in the worker.
    assert(wasmModule, "wasmModule should have been received via postMessage");
    var instance = new WebAssembly.Instance(wasmModule, getWasmImports());
    return receiveInstance(instance, wasmModule);
  }

  wasmBinaryFile ??= findWasmBinary();
  var result = await instantiateAsync(wasmBinary, wasmBinaryFile, info);
  var exports = receiveInstantiationResult(result);
  return exports;
}

// end include: preamble.js

// Begin JS library code


  class ExitStatus {
      name = 'ExitStatus';
      constructor(status) {
        this.message = `Program terminated with exit(${status})`;
        this.status = status;
      }
    }

  /** @type {!Int16Array} */
  var HEAP16;

  /** @type {!Int32Array} */
  var HEAP32;

  /** not-@type {!BigInt64Array} */
  var HEAP64;

  /** @type {!Int8Array} */
  var HEAP8;

  /** @type {!Float32Array} */
  var HEAPF32;

  /** @type {!Float64Array} */
  var HEAPF64;

  /** @type {!Uint16Array} */
  var HEAPU16;

  /** @type {!Uint32Array} */
  var HEAPU32;

  /** not-@type {!BigUint64Array} */
  var HEAPU64;

  /** @type {!Uint8Array} */
  var HEAPU8;

  
  var terminateWorker = (worker) => {
      worker.terminate();
      // terminate() can be asynchronous, so in theory the worker can continue
      // to run for some amount of time after termination.  However from our POV
      // the worker is now dead and we don't want to hear from it again, so we stub
      // out its message handler here.  This avoids having to check in each of
      // the onmessage handlers if the message was coming from a valid worker.
      worker.onmessage = (e) => {
        var cmd = e.data.cmd;
        err(`received "${cmd}" command from terminated worker: ${worker.workerID}`);
      };
    };
  
  var cleanupThread = (pthread_ptr) => {
      assert(!ENVIRONMENT_IS_PTHREAD, 'cleanupThread() should only be called from the main thread');
      assert(pthread_ptr, 'null pthread_ptr passed to cleanupThread');
      var worker = PThread.pthreads[pthread_ptr];
      assert(worker);
      PThread.returnWorkerToPool(worker);
    };
  
  var callRuntimeCallbacks = (callbacks) => {
      while (callbacks.length > 0) {
        // Pass the module as the first argument.
        callbacks.shift()(Module);
      }
    };
  var onPreRuns = [];
  var addOnPreRun = (cb) => onPreRuns.push(cb);
  
  var spawnThread = (threadParams) => {
      assert(!ENVIRONMENT_IS_PTHREAD, 'spawnThread() should only be called from the main thread');
      assert(threadParams.pthread_ptr, 'spawnThread called with null pthread ptr');
  
      var worker = PThread.getNewWorker();
      if (!worker) {
        // No available workers in the PThread pool.
        return 6;
      }
      assert(!worker.pthread_ptr);
  
      // Add to pthreads map
      PThread.pthreads[threadParams.pthread_ptr] = worker;
  
      worker.pthread_ptr = threadParams.pthread_ptr;
      var msg = {
          cmd: 2,
          start_routine: threadParams.startRoutine,
          arg: threadParams.arg,
          pthread_ptr: threadParams.pthread_ptr,
      };
      // Ask the worker to start executing its pthread entry point function.
      worker.postMessage(msg, threadParams.transferList);
      return 0;
    };
  
  
  
  var runtimeKeepaliveCounter = 0;
  var keepRuntimeAlive = () => noExitRuntime || runtimeKeepaliveCounter > 0;
  
  var stackSave = () => _emscripten_stack_get_current();
  
  var stackRestore = (val) => __emscripten_stack_restore(val);
  
  var stackAlloc = (sz) => __emscripten_stack_alloc(sz);
  
  
  /** @type{function(number, (number|boolean), ...number)} */
  var proxyToMainThread = (funcIndex, emAsmAddr, proxyMode, ...callArgs) => {
      // EM_ASM proxying is done by passing a pointer to the address of the EM_ASM
      // content as `emAsmAddr`.  JS library proxying is done by passing an index
      // into `proxiedJSCallArgs` as `funcIndex`. If `emAsmAddr` is non-zero then
      // `funcIndex` will be ignored.
      // Additional arguments are passed after the first three are the actual
      // function arguments.
      // The serialization buffer contains the number of call params, and then
      // all the args here.
      //
      // We also pass 'proxyMode' to C separately, since C needs to look at it.
      //
      // Allocate a buffer (on the stack), which will be copied if necessary by
      // the C code.
      //
      // First passed parameter specifies the number of arguments to the function.
      // When BigInt support is enabled, we must handle types in a more complex
      // way, detecting at runtime if a value is a BigInt or not (as we have no
      // type info here). To do that, add a "prefix" before each value that
      // indicates if it is a BigInt, which effectively doubles the number of
      // values we serialize for proxying. TODO: pack this?
      var bufSize = 8 * callArgs.length * 2;
      var sp = stackSave();
      var args = stackAlloc(bufSize);
      var b = ((args)>>3);
      for (var arg of callArgs) {
        if (typeof arg == 'bigint') {
          // The prefix is non-zero to indicate a bigint.
          HEAP64[b++] = 1n;
          HEAP64[b++] = arg;
        } else {
          // The prefix is zero to indicate a JS Number.
          HEAP64[b++] = 0n;
          HEAPF64[b++] = arg;
        }
      }
      var rtn = __emscripten_run_js_on_main_thread(funcIndex, emAsmAddr, bufSize, args, proxyMode);
      stackRestore(sp);
      return rtn;
    };
  
  function _proc_exit(code) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(0, 0, 1, code);
  
      EXITSTATUS = code;
      if (!keepRuntimeAlive()) {
        PThread.terminateAllThreads();
        Module['onExit']?.(code);
        ABORT = true;
      }
      quit_(code, new ExitStatus(code));
    
  }
  
  
  
  
  
  
  function exitOnMainThread(returnCode) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(1, 0, 0, returnCode);
  
      _exit(returnCode);
    
  }
  
  
  /** @param {boolean|number=} implicit */
  var exitJS = (status, implicit) => {
      EXITSTATUS = status;
  
      checkUnflushedContent();
  
      if (ENVIRONMENT_IS_PTHREAD) {
        // implicit exit can never happen on a pthread
        assert(!implicit);
        // When running in a pthread we propagate the exit back to the main thread
        // where it can decide if the whole process should be shut down or not.
        // The pthread may have decided not to exit its own runtime, for example
        // because it runs a main loop, but that doesn't affect the main thread.
        exitOnMainThread(status);
        throw 'unwind';
      }
  
      // if exit() was called explicitly, warn the user if the runtime isn't actually being shut down
      if (keepRuntimeAlive() && !implicit) {
        var msg = `program exited (with status: ${status}), but keepRuntimeAlive() is set (counter=${runtimeKeepaliveCounter}) due to an async operation, so halting execution but not exiting the runtime or preventing further async execution (you can use emscripten_force_exit, if you want to force a true shutdown)`;
        err(msg);
      }
  
      _proc_exit(status);
    };
  var _exit = exitJS;
  
  
  
  var waitAsyncPolyfilled = (!Atomics.waitAsync || (globalThis.navigator?.userAgent && Number((navigator.userAgent.match(/Chrom(e|ium)\/([0-9]+)\./)||[])[2]) < 91));;
  
  function ptrToString(ptr) {
      assert(typeof ptr === 'number', `ptrToString expects a number, got ${typeof ptr}`);
      // Convert to 32-bit unsigned value
      ptr >>>= 0;
      return '0x' + ptr.toString(16).padStart(8, '0');
    }
  
  var PThread = {
  unusedWorkers:[],
  tlsInitFunctions:[],
  pthreads:{
  },
  nextWorkerID:1,
  init() {
        if ((!(ENVIRONMENT_IS_PTHREAD))) {
          PThread.initMainThread();
        }
      },
  initMainThread() {
      },
  terminateAllThreads:() => {
        assert(!ENVIRONMENT_IS_PTHREAD, 'terminateAllThreads() should only be called from the main thread');
        // Attempt to kill all workers.  Sadly (at least on the web) there is no
        // way to terminate a worker synchronously, or to be notified when a
        // worker is actually terminated.  This means there is some risk that
        // pthreads will continue to be executing after `worker.terminate` has
        // returned.  For this reason, we don't call `returnWorkerToPool` here or
        // free the underlying pthread data structures.
        for (var worker of Object.values(PThread.pthreads)) {
          terminateWorker(worker);
        }
        for (var worker of PThread.unusedWorkers) {
          terminateWorker(worker);
        }
        PThread.unusedWorkers = [];
        PThread.pthreads = {};
      },
  terminateRuntime:() => {
        assert(!ENVIRONMENT_IS_PTHREAD, 'terminateRuntime() should only be called from the main thread');
        PThread.terminateAllThreads();
        var pthread_ptr = _pthread_self();
        ___set_thread_state(0, 0, 0, 1);
        if (!waitAsyncPolyfilled) {
          // Break the waitAsync loop.  Note that checkMailbox will not
          // re-register since the `___set_thread_state` above causes _pthread_self
          // to return 0.
          Atomics.notify(HEAP32, ((pthread_ptr)>>2));
        }
      },
  returnWorkerToPool:(worker) => {
        // We don't want to run main thread queued calls here, since we are doing
        // some operations that leave the worker queue in an invalid state until
        // we are completely done (it would be bad if free() ends up calling a
        // queued pthread_create which looks at the global data structures we are
        // modifying). To achieve that, defer the free() until the very end, when
        // we are all done.
        var pthread_ptr = worker.pthread_ptr;
        delete PThread.pthreads[pthread_ptr];
        // Note: worker is intentionally not terminated so the pool can
        // dynamically grow.
        PThread.unusedWorkers.push(worker);
        // Not a running Worker anymore
        // Detach the worker from the pthread object, and return it to the
        // worker pool as an unused worker.
        worker.pthread_ptr = 0;
  
        // Finally, free the underlying (and now-unused) pthread structure in
        // linear memory.
        __emscripten_thread_free_data(pthread_ptr);
      },
  threadInitTLS() {
        // Call thread init functions (these are the _emscripten_tls_init for each
        // module loaded.
        PThread.tlsInitFunctions.forEach((f) => f());
      },
  loadWasmModuleToWorker:(worker) => new Promise((onFinishedLoading) => {
        worker.onmessage = (e) => {
          var d = e.data;
          var cmd = d.cmd;
  
          // If this message is intended to a recipient that is not the main
          // thread, forward it to the target thread. This is currently only
          // used by `CMD_CHECK_MAILBOX`.
          if (d.targetThread) {
            // pthreads should not be relaying messages to themselves.
            assert(d.targetThread != _pthread_self());
            var targetWorker = PThread.pthreads[d.targetThread];
            if (!targetWorker) err(`worker sent message (${cmd}) to pthread (${d.targetThread}) that no longer exists`);
            targetWorker?.postMessage(d);
            return;
          }
  
          if (d === 'setimmediate' || d === '_si') {
            // Worker wants to postMessage() to itself to implement setImmediate()
            // emulation.
            worker.postMessage(d);
            return;
          }
  
          switch (cmd) {
            case 4:
              checkMailbox();
              break;
            case 5:
              spawnThread(d);
              break;
            case 6:
              // cleanupThread needs to be run via callUserCallback since it calls
              // back into user code to free thread data. Without this it's possible
              // the unwind or ExitStatus exception could escape here.
              callUserCallback(() => cleanupThread(d.thread));
              break;
            case 3:
              if (ENVIRONMENT_IS_NODE && !worker.strongref) {
                // Once worker is loaded & idle, mark it as weakly referenced,
                // so that mere existence of a Worker in the pool does not prevent
                // Node.js from exiting the app.
                worker.unref();
              }
              onFinishedLoading(worker);
              break;
            case 8:
              // Message handler for Node.js specific out-of-order behavior:
              // https://github.com/nodejs/node/issues/59617
              // A pthread sent an uncaught exception event. Re-raise it on the main thread.
              worker.onerror(d.error);
              break;
            case 9:
              Module[d.handler](...d.args);
              break;
            default:
              // The received message looks like something that should be handled by this message
              // handler, (since there is a e.data.cmd field present), but is not one of the
              // recognized commands:
              if (cmd) err(`worker sent an unknown command ${cmd}`);
          }
        };
  
        worker.onerror = (e) => {
          var message = 'worker sent an error!';
          if (worker.pthread_ptr) {
            message = `Pthread ${ptrToString(worker.pthread_ptr)} sent an error!`;
          }
          err(`${message} ${e.filename}:${e.lineno}: ${e.message}`);
          throw e;
        };
  
        if (ENVIRONMENT_IS_NODE) {
          worker.on('message', (data) => worker.onmessage({ data: data }));
          worker.on('error', (e) => worker.onerror(e));
  
        }
  
        assert(wasmMemory instanceof WebAssembly.Memory, 'wasmMemory should have been loaded by now');
        assert(wasmModule instanceof WebAssembly.Module, 'wasmModule should have been loaded by now');
  
        // When running on a pthread, none of the incoming parameters on the module
        // object are present. Proxy known handlers back to the main thread if specified.
        var handlers = [];
        var knownHandlers = [
          'onExit',
          'onAbort',
          'print',
          'printErr',
        ];
        for (var handler of knownHandlers) {
          if (Module.propertyIsEnumerable(handler)) {
            handlers.push(handler);
          }
        }
  
        // Ask the new worker to load up the Emscripten-compiled page. This is a heavy operation.
        worker.postMessage({
          cmd: 1,
          handlers: handlers,
          wasmMemory,
          wasmModule,
          workerID: worker.workerID,
        });
      }),
  allocateUnusedWorker() {
        var worker;
        var pthreadMainJs = _scriptName;
        worker = new Worker(pthreadMainJs, {
          // This is the way that we signal to the node worker that it is hosting
          // a pthread.
          'workerData': 'em-pthread',
          // This is the way that we signal to the Web Worker that it is hosting
          // a pthread.
          'name': 'em-pthread-' + PThread.nextWorkerID,
  });
        worker.workerID = PThread.nextWorkerID++;
        PThread.unusedWorkers.push(worker);
        return worker;
      },
  getNewWorker() {
        if (PThread.unusedWorkers.length == 0) {
  // PTHREAD_POOL_SIZE_STRICT should show a warning and, if set to level `2`, return from the function.
  // However, if we're in Node.js, then we can create new workers on the fly and PTHREAD_POOL_SIZE_STRICT
  // should be ignored altogether.
          if (!ENVIRONMENT_IS_NODE) {
              err('Tried to spawn a new thread, but the thread pool is exhausted.\n' +
              'This might result in a deadlock unless some threads eventually exit or the code explicitly breaks out to the event loop.\n' +
              'If you want to increase the pool size, use setting `-sPTHREAD_POOL_SIZE=...`.'
                + '\nIf you want to throw an explicit error instead of the risk of deadlocking in those cases, use setting `-sPTHREAD_POOL_SIZE_STRICT=2`.'
              );
          }
          var newWorker = PThread.allocateUnusedWorker();
          PThread.loadWasmModuleToWorker(newWorker);
        }
        return PThread.unusedWorkers.pop();
      },
  };

  var onPostRuns = [];
  var addOnPostRun = (cb) => onPostRuns.push(cb);



  
  
  function establishStackSpace(pthread_ptr) {
      var stackHigh = HEAPU32[(((pthread_ptr)+(48))>>2)];
      var stackSize = HEAPU32[(((pthread_ptr)+(52))>>2)];
      var stackLow = stackHigh - stackSize;
      assert(stackHigh != 0);
      assert(stackLow != 0);
      assert(stackHigh > stackLow, 'stackHigh must be higher then stackLow');
      // Set stack limits used by `emscripten/stack.h` function.  These limits are
      // cached in wasm-side globals to make checks as fast as possible.
      _emscripten_stack_set_limits(stackHigh, stackLow);
  
      setStackLimits();
  
      // Call inside wasm module to set up the stack frame for this pthread in wasm module scope
      stackRestore(stackHigh);
  
      // Write the stack cookie last, after we have set up the proper bounds and
      // current position of the stack.
      writeStackCookie();
    }

  
    /**
   * @param {number} ptr
   * @param {string} type
   */
  function getValue(ptr, type = 'i8') {
    if (type.endsWith('*')) type = '*';
    switch (type) {
      case 'i1': return HEAP8[ptr];
      case 'i8': return HEAP8[ptr];
      case 'i16': return HEAP16[((ptr)>>1)];
      case 'i32': return HEAP32[((ptr)>>2)];
      case 'i64': return HEAP64[((ptr)>>3)];
      case 'float': return HEAPF32[((ptr)>>2)];
      case 'double': return HEAPF64[((ptr)>>3)];
      case '*': return HEAPU32[((ptr)>>2)];
      default: abort(`invalid type for getValue: ${type}`);
    }
  }

  
  
  
  
  var wasmTableMirror = [];
  
  
  var getWasmTableEntry = (funcPtr) => {
      var func = wasmTableMirror[funcPtr];
      if (!func) {
        /** @suppress {checkTypes} */
        wasmTableMirror[funcPtr] = func = wasmTable.get(funcPtr);
      }
      /** @suppress {checkTypes} */
      assert(wasmTable.get(funcPtr) == func, 'table mirror is out of date');
      return func;
    };
  var invokeEntryPoint = (ptr, arg) => {
      // An old thread on this worker may have been canceled without returning the
      // `runtimeKeepaliveCounter` to zero. Reset it now so the new thread won't
      // be affected.
      runtimeKeepaliveCounter = 0;
  
      // Same for noExitRuntime.  The default for pthreads should always be false
      // otherwise pthreads would never complete and attempts to pthread_join to
      // them would block forever.
      // pthreads can still choose to set `noExitRuntime` explicitly, or
      // call emscripten_unwind_to_js_event_loop to extend their lifetime beyond
      // their main function.  See comment in src/runtime_pthread.js for more.
      noExitRuntime = 0;
  
      // pthread entry points are always of signature 'void *ThreadMain(void *arg)'
      // Native codebases sometimes spawn threads with other thread entry point
      // signatures, such as void ThreadMain(void *arg), void *ThreadMain(), or
      // void ThreadMain().  That is not acceptable per C/C++ specification, but
      // x86 compiler ABI extensions enable that to work. If you find the
      // following line to crash, either change the signature to "proper" void
      // *ThreadMain(void *arg) form, or try linking with the Emscripten linker
      // flag -sEMULATE_FUNCTION_POINTER_CASTS to add in emulation for this x86
      // ABI extension.
  
      var result = getWasmTableEntry(ptr)(arg);
  
      checkStackCookie();
      function finish(result) {
        // In MINIMAL_RUNTIME the noExitRuntime concept does not apply to
        // pthreads. To exit a pthread with live runtime, use the function
        // emscripten_unwind_to_js_event_loop() in the pthread body.
        if (keepRuntimeAlive()) {
          EXITSTATUS = result;
          return;
        }
        __emscripten_thread_exit(result);
      }
      finish(result);
    };

  var noExitRuntime = true;


  var registerTLSInit = (tlsInitFunc) => PThread.tlsInitFunctions.push(tlsInitFunc);

  var setStackLimits = () => {
      var stackLow = _emscripten_stack_get_base();
      var stackHigh = _emscripten_stack_get_end();
      ___set_stack_limits(stackLow, stackHigh);
    };

  
    /**
   * @param {number} ptr
   * @param {number} value
   * @param {string} type
   */
  function setValue(ptr, value, type = 'i8') {
    if (type.endsWith('*')) type = '*';
    switch (type) {
      case 'i1': HEAP8[ptr] = value; break;
      case 'i8': HEAP8[ptr] = value; break;
      case 'i16': HEAP16[((ptr)>>1)] = value; break;
      case 'i32': HEAP32[((ptr)>>2)] = value; break;
      case 'i64': HEAP64[((ptr)>>3)] = BigInt(value); break;
      case 'float': HEAPF32[((ptr)>>2)] = value; break;
      case 'double': HEAPF64[((ptr)>>3)] = value; break;
      case '*': HEAPU32[((ptr)>>2)] = value; break;
      default: abort(`invalid type for setValue: ${type}`);
    }
  }



  var warnOnce = (text) => {
      warnOnce.shown ||= {};
      if (!warnOnce.shown[text]) {
        warnOnce.shown[text] = 1;
        if (ENVIRONMENT_IS_NODE) text = 'warning: ' + text;
        err(text);
      }
    };

  var wasmMemory;

  var UTF8Decoder = globalThis.TextDecoder && new TextDecoder();
  
  
    /**
   * heapOrArray is either a regular array, or a JavaScript typed array view.
   * @param {number} idx
   * @param {number=} maxBytesToRead
   * @param {boolean=} ignoreNul
   * @return {number}
   */
  var findStringEnd = (heapOrArray, idx, maxBytesToRead, ignoreNul) => {
      var maxIdx = idx + maxBytesToRead;
      if (ignoreNul) return maxIdx;
      // TextDecoder needs to know the byte length in advance, it doesn't stop on
      // null terminator by itself.
      // As a tiny code save trick, compare idx against maxIdx using a negation,
      // so that maxBytesToRead=undefined/NaN means Infinity.
      while (heapOrArray[idx] && !(idx >= maxIdx)) ++idx;
      return idx;
    };
  
  
    /**
   * Given a pointer 'idx' to a null-terminated UTF8-encoded string in the given
   * array that contains uint8 values, returns a copy of that string as a
   * Javascript String object.
   * heapOrArray is either a regular array, or a JavaScript typed array view.
   * @param {number=} idx
   * @param {number=} maxBytesToRead
   * @param {boolean=} ignoreNul - If true, the function will not stop on a NUL character.
   * @return {string}
   */
  var UTF8ArrayToString = (heapOrArray, idx = 0, maxBytesToRead, ignoreNul) => {
  
      var endPtr = findStringEnd(heapOrArray, idx, maxBytesToRead, ignoreNul);
  
      // When using conditional TextDecoder, skip it for short strings as the overhead of the native call is not worth it.
      if (endPtr - idx > 16 && heapOrArray.buffer && UTF8Decoder) {
        return UTF8Decoder.decode(heapOrArray.buffer instanceof ArrayBuffer ? heapOrArray.subarray(idx, endPtr) : heapOrArray.slice(idx, endPtr));
      }
      var str = '';
      while (idx < endPtr) {
        // For UTF8 byte structure, see:
        // http://en.wikipedia.org/wiki/UTF-8#Description
        // https://www.ietf.org/rfc/rfc2279.txt
        // https://tools.ietf.org/html/rfc3629
        var u0 = heapOrArray[idx++];
        if (!(u0 & 0x80)) { str += String.fromCharCode(u0); continue; }
        var u1 = heapOrArray[idx++] & 63;
        if ((u0 & 0xE0) == 0xC0) { str += String.fromCharCode(((u0 & 31) << 6) | u1); continue; }
        var u2 = heapOrArray[idx++] & 63;
        if ((u0 & 0xF0) == 0xE0) {
          u0 = ((u0 & 15) << 12) | (u1 << 6) | u2;
        } else {
          if ((u0 & 0xF8) != 0xF0) warnOnce(`Invalid UTF-8 leading byte ${ptrToString(u0)} encountered when deserializing a UTF-8 string in wasm memory to a JS string!`);
          u0 = ((u0 & 7) << 18) | (u1 << 12) | (u2 << 6) | (heapOrArray[idx++] & 63);
        }
  
        if (u0 < 0x10000) {
          str += String.fromCharCode(u0);
        } else {
          var ch = u0 - 0x10000;
          str += String.fromCharCode(0xD800 | (ch >> 10), 0xDC00 | (ch & 0x3FF));
        }
      }
      return str;
    };
  
    /**
   * Given a pointer 'ptr' to a null-terminated UTF8-encoded string in the
   * emscripten HEAP, returns a copy of that string as a Javascript String object.
   *
   * @param {number} ptr
   * @param {number=} maxBytesToRead - An optional length that specifies the
   *   maximum number of bytes to read. You can omit this parameter to scan the
   *   string until the first 0 byte. If maxBytesToRead is passed, and the string
   *   at [ptr, ptr+maxBytesToReadr[ contains a null byte in the middle, then the
   *   string will cut short at that byte index.
   * @param {boolean=} ignoreNul - If true, the function will not stop on a NUL character.
   * @return {string}
   */
  var UTF8ToString = (ptr, maxBytesToRead, ignoreNul) => {
      assert(typeof ptr == 'number', `UTF8ToString expects a number (got ${typeof ptr})`);
      return ptr ? UTF8ArrayToString(HEAPU8, ptr, maxBytesToRead, ignoreNul) : '';
    };
  var ___assert_fail = (condition, filename, line, func) =>
      abort(`Assertion failed: ${UTF8ToString(condition)}, at: ` + [filename ? UTF8ToString(filename) : 'unknown filename', line, func ? UTF8ToString(func) : 'unknown function']);

  var ___call_sighandler = (fp, sig) => getWasmTableEntry(fp)(sig);

  var exceptionCaught =  [];
  
  
  var uncaughtExceptionCount = 0;
  var ___cxa_begin_catch = (ptr) => {
      var info = new ExceptionInfo(ptr);
      if (!info.get_caught()) {
        info.set_caught(true);
        uncaughtExceptionCount--;
      }
      info.set_rethrown(false);
      exceptionCaught.push(info);
      return ___cxa_get_exception_ptr(ptr);
    };

  var exceptionLast = null;
  
  class ExceptionInfo {
      // excPtr - Thrown object pointer to wrap. Metadata pointer is calculated from it.
      constructor(excPtr) {
        this.excPtr = excPtr;
        this.ptr = excPtr - 24;
      }
  
      set_type(type) {
        HEAPU32[(((this.ptr)+(4))>>2)] = type;
      }
  
      get_type() {
        return HEAPU32[(((this.ptr)+(4))>>2)];
      }
  
      set_destructor(destructor) {
        HEAPU32[(((this.ptr)+(8))>>2)] = destructor;
      }
  
      get_destructor() {
        return HEAPU32[(((this.ptr)+(8))>>2)];
      }
  
      set_caught(caught) {
        caught = caught ? 1 : 0;
        HEAP8[(this.ptr)+(12)] = caught;
      }
  
      get_caught() {
        return HEAP8[(this.ptr)+(12)] != 0;
      }
  
      set_rethrown(rethrown) {
        rethrown = rethrown ? 1 : 0;
        HEAP8[(this.ptr)+(13)] = rethrown;
      }
  
      get_rethrown() {
        return HEAP8[(this.ptr)+(13)] != 0;
      }
  
      // Initialize native structure fields. Should be called once after allocated.
      init(type, destructor) {
        this.set_adjusted_ptr(0);
        this.set_type(type);
        this.set_destructor(destructor);
      }
  
      set_adjusted_ptr(adjustedPtr) {
        HEAPU32[(((this.ptr)+(16))>>2)] = adjustedPtr;
      }
  
      get_adjusted_ptr() {
        return HEAPU32[(((this.ptr)+(16))>>2)];
      }
    }
  
  
  var setTempRet0 = (val) => __emscripten_tempret_set(val);
  var findMatchingCatch = (args) => {
      var thrown = exceptionLast?.excPtr;
      if (!thrown) {
        // just pass through the null ptr
        setTempRet0(0);
        return 0;
      }
      var info = new ExceptionInfo(thrown);
      info.set_adjusted_ptr(thrown);
      var thrownType = info.get_type();
      if (!thrownType) {
        // just pass through the thrown ptr
        setTempRet0(0);
        return thrown;
      }
  
      // can_catch receives a **, add indirection
      // The different catch blocks are denoted by different types.
      // Due to inheritance, those types may not precisely match the
      // type of the thrown object. Find one which matches, and
      // return the type of the catch block which should be called.
      for (var caughtType of args) {
        if (caughtType === 0 || caughtType === thrownType) {
          // Catch all clause matched or exactly the same type is caught
          break;
        }
        var adjusted_ptr_addr = info.ptr + 16;
        if (___cxa_can_catch(caughtType, thrownType, adjusted_ptr_addr)) {
          setTempRet0(caughtType);
          return thrown;
        }
      }
      setTempRet0(thrownType);
      return thrown;
    };
  var ___cxa_find_matching_catch_2 = () => findMatchingCatch([]);

  var ___cxa_find_matching_catch_3 = (arg0) => findMatchingCatch([arg0]);

  
  
  var ___handle_stack_overflow = (requested) => {
      var base = _emscripten_stack_get_base();
      var end = _emscripten_stack_get_end();
      abort(`stack overflow (Attempt to set SP to ${ptrToString(requested)}` +
            `, with stack limits [${ptrToString(end)} - ${ptrToString(base)}` +
            ']). If you require more stack space build with -sSTACK_SIZE=<bytes>');
    };

  
  
  
  
  function pthreadCreateProxied(pthread_ptr, attr, startRoutine, arg) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(2, 0, 1, pthread_ptr, attr, startRoutine, arg);
  return ___pthread_create_js(pthread_ptr, attr, startRoutine, arg)
  }
  
  
  var _emscripten_has_threading_support = () => !!globalThis.SharedArrayBuffer;
  
  var ___pthread_create_js = (pthread_ptr, attr, startRoutine, arg) => {
      if (!_emscripten_has_threading_support()) {
        dbg('pthread_create: environment does not support SharedArrayBuffer, pthreads are not available');
        return 6;
      }
  
      // List of JS objects that will transfer ownership to the Worker hosting the thread
      var transferList = [];
      var error = 0;
  
      // Synchronously proxy the thread creation to main thread if possible. If we
      // need to transfer ownership of objects, then proxy asynchronously via
      // postMessage.
      if (ENVIRONMENT_IS_PTHREAD && (transferList.length === 0 || error)) {
        return pthreadCreateProxied(pthread_ptr, attr, startRoutine, arg);
      }
  
      // If on the main thread, and accessing Canvas/OffscreenCanvas failed, abort
      // with the detected error.
      if (error) return error;
  
      var threadParams = {
        startRoutine,
        pthread_ptr,
        arg,
        transferList,
      };
  
      if (ENVIRONMENT_IS_PTHREAD) {
        // The prepopulated pool of web workers that can host pthreads is stored
        // in the main JS thread. Therefore if a pthread is attempting to spawn a
        // new thread, the thread creation must be deferred to the main JS thread.
        threadParams.cmd = 5;
        postMessage(threadParams, transferList);
        // When we defer thread creation this way, we have no way to detect thread
        // creation synchronously today, so we have to assume success and return 0.
        return 0;
      }
  
      // We are the main thread, so we have the pthread warmup pool in this
      // thread and can fire off JS thread creation directly ourselves.
      return spawnThread(threadParams);
    };

  var ___resumeException = (ptr) => {
      if (!exceptionLast) {
        exceptionLast = new CppException(ptr);
      }
      throw exceptionLast;
    };

  var initRandomFill = () => {
      // This block is not needed on v19+ since crypto.getRandomValues is builtin
      if (ENVIRONMENT_IS_NODE) {
        var nodeCrypto = require('node:crypto');
        return (view) => (nodeCrypto.randomFillSync(view), 0);
      }
  
      // like with most Web APIs, we can't use Web Crypto API directly on shared memory,
      // so we need to create an intermediate buffer and copy it to the destination
      return (view) => (view.set(crypto.getRandomValues(new Uint8Array(view.byteLength))), 0);
    };
  var randomFill = (view) => (randomFill = initRandomFill())(view);
  
  var PATH = {
  isAbs:(path) => path.charAt(0) === '/',
  splitPath:(filename) => {
        var splitPathRe = /^(\/?|)([\s\S]*?)((?:\.{1,2}|[^\/]+?|)(\.[^.\/]*|))(?:[\/]*)$/;
        return splitPathRe.exec(filename).slice(1);
      },
  normalizeArray:(parts, allowAboveRoot) => {
        // if the path tries to go above the root, `up` ends up > 0
        var up = 0;
        for (var i = parts.length - 1; i >= 0; i--) {
          var last = parts[i];
          if (last === '.') {
            parts.splice(i, 1);
          } else if (last === '..') {
            parts.splice(i, 1);
            up++;
          } else if (up) {
            parts.splice(i, 1);
            up--;
          }
        }
        // if the path is allowed to go above the root, restore leading ..s
        if (allowAboveRoot) {
          for (; up; up--) {
            parts.unshift('..');
          }
        }
        return parts;
      },
  normalize:(path) => {
        var isAbsolute = PATH.isAbs(path),
            trailingSlash = path.slice(-1) === '/';
        // Normalize the path
        path = PATH.normalizeArray(path.split('/').filter((p) => !!p), !isAbsolute).join('/');
        if (!path && !isAbsolute) {
          path = '.';
        }
        if (path && trailingSlash) {
          path += '/';
        }
        return (isAbsolute ? '/' : '') + path;
      },
  dirname:(path) => {
        var result = PATH.splitPath(path),
            root = result[0],
            dir = result[1];
        if (!root && !dir) {
          // No dirname whatsoever
          return '.';
        }
        if (dir) {
          // It has a dirname, strip trailing slash
          dir = dir.slice(0, -1);
        }
        return root + dir;
      },
  basename:(path) => path && path.match(/([^\/]+|\/)\/*$/)[1],
join:(...paths) => PATH.normalize(paths.join('/')),
join2:(l, r) => PATH.normalize(l + '/' + r),
};


var PATH_FS = {
resolve:(...args) => {
      var resolvedPath = '',
        resolvedAbsolute = false;
      for (var i = args.length - 1; i >= -1 && !resolvedAbsolute; i--) {
        var path = (i >= 0) ? args[i] : FS.cwd();
        // Skip empty and invalid entries
        if (typeof path != 'string') {
          throw new TypeError('Arguments to path.resolve must be strings');
        } else if (!path) {
          return ''; // an invalid portion invalidates the whole thing
        }
        resolvedPath = path + '/' + resolvedPath;
        resolvedAbsolute = PATH.isAbs(path);
      }
      // At this point the path should be resolved to a full absolute path, but
      // handle relative paths to be safe (might happen when process.cwd() fails)
      resolvedPath = PATH.normalizeArray(resolvedPath.split('/').filter((p) => !!p), !resolvedAbsolute).join('/');
      return ((resolvedAbsolute ? '/' : '') + resolvedPath) || '.';
    },
relative:(from, to) => {
      from = PATH_FS.resolve(from).slice(1);
      to = PATH_FS.resolve(to).slice(1);
      function trim(arr) {
        var start = 0;
        for (; start < arr.length; start++) {
          if (arr[start] !== '') break;
        }
        var end = arr.length - 1;
        for (; end >= 0; end--) {
          if (arr[end] !== '') break;
        }
        if (start > end) return [];
        return arr.slice(start, end - start + 1);
      }
      var fromParts = trim(from.split('/'));
      var toParts = trim(to.split('/'));
      var length = Math.min(fromParts.length, toParts.length);
      var samePartsLength = length;
      for (var i = 0; i < length; i++) {
        if (fromParts[i] !== toParts[i]) {
          samePartsLength = i;
          break;
        }
      }
      var outputParts = [];
      for (var i = samePartsLength; i < fromParts.length; i++) {
        outputParts.push('..');
      }
      outputParts = outputParts.concat(toParts.slice(samePartsLength));
      return outputParts.join('/');
    },
};



var FS_stdin_getChar_buffer = [];

var lengthBytesUTF8 = (str) => {
    var len = 0;
    for (var i = 0; i < str.length; ++i) {
      // Gotcha: charCodeAt returns a 16-bit word that is a UTF-16 encoded code
      // unit, not a Unicode code point of the character! So decode
      // UTF16->UTF32->UTF8.
      // See http://unicode.org/faq/utf_bom.html#utf16-3
      var c = str.charCodeAt(i); // possibly a lead surrogate
      if (c <= 0x7F) {
        len++;
      } else if (c <= 0x7FF) {
        len += 2;
      } else if (c >= 0xD800 && c <= 0xDFFF) {
        len += 4; ++i;
      } else {
        len += 3;
      }
    }
    return len;
  };

var stringToUTF8Array = (str, heap, outIdx, maxBytesToWrite) => {
    assert(typeof str === 'string', `stringToUTF8Array expects a string (got ${typeof str})`);
    // Parameter maxBytesToWrite is not optional. Negative values, 0, null,
    // undefined and false each don't write out any bytes.
    if (!(maxBytesToWrite > 0))
      return 0;

    var startIdx = outIdx;
    var endIdx = outIdx + maxBytesToWrite - 1; // -1 for string null terminator.
    for (var i = 0; i < str.length; ++i) {
      // For UTF8 byte structure, see http://en.wikipedia.org/wiki/UTF-8#Description
      // and https://www.ietf.org/rfc/rfc2279.txt
      // and https://tools.ietf.org/html/rfc3629
      var u = str.codePointAt(i);
      if (u <= 0x7F) {
        if (outIdx >= endIdx) break;
        heap[outIdx++] = u;
      } else if (u <= 0x7FF) {
        if (outIdx + 1 >= endIdx) break;
        heap[outIdx++] = 0xC0 | (u >> 6);
        heap[outIdx++] = 0x80 | (u & 63);
      } else if (u <= 0xFFFF) {
        if (outIdx + 2 >= endIdx) break;
        heap[outIdx++] = 0xE0 | (u >> 12);
        heap[outIdx++] = 0x80 | ((u >> 6) & 63);
        heap[outIdx++] = 0x80 | (u & 63);
      } else {
        if (outIdx + 3 >= endIdx) break;
        if (u > 0x10FFFF) warnOnce(`Invalid Unicode code point ${ptrToString(u)} encountered when serializing a JS string to a UTF-8 string in wasm memory! (Valid unicode code points should be in range 0-0x10FFFF).`);
        heap[outIdx++] = 0xF0 | (u >> 18);
        heap[outIdx++] = 0x80 | ((u >> 12) & 63);
        heap[outIdx++] = 0x80 | ((u >> 6) & 63);
        heap[outIdx++] = 0x80 | (u & 63);
        // Gotcha: if codePoint is over 0xFFFF, it is represented as a surrogate pair in UTF-16.
        // We need to manually skip over the second code unit for correct iteration.
        i++;
      }
    }
    // Null-terminate the pointer to the buffer.
    heap[outIdx] = 0;
    return outIdx - startIdx;
  };
/** @type {function(string, boolean=, number=)} */
  var intArrayFromString = (stringy, dontAddNull, length) => {
      var len = length > 0 ? length : lengthBytesUTF8(stringy)+1;
      var u8array = new Array(len);
      var numBytesWritten = stringToUTF8Array(stringy, u8array, 0, u8array.length);
      if (dontAddNull) u8array.length = numBytesWritten;
      return u8array;
    };
  var FS_stdin_getChar = () => {
      if (!FS_stdin_getChar_buffer.length) {
        var result = null;
        if (ENVIRONMENT_IS_NODE) {
          // we will read data by chunks of BUFSIZE
          var BUFSIZE = 256;
          var buf = Buffer.alloc(BUFSIZE);
          var bytesRead = 0;
  
          // For some reason we must suppress a closure warning here, even though
          // fd definitely exists on process.stdin, and is even the proper way to
          // get the fd of stdin,
          // https://github.com/nodejs/help/issues/2136#issuecomment-523649904
          // This started to happen after moving this logic out of library_tty.js,
          // so it is related to the surrounding code in some unclear manner.
          /** @suppress {missingProperties} */
          var fd = process.stdin.fd;
  
          try {
            bytesRead = fs.readSync(fd, buf, 0, BUFSIZE);
          } catch(e) {
            // Cross-platform differences: on Windows, reading EOF throws an
            // exception, but on other OSes, reading EOF returns 0. Uniformize
            // behavior by treating the EOF exception to return 0.
            if (e.toString().includes('EOF')) bytesRead = 0;
            else throw e;
          }
  
          if (bytesRead > 0) {
            result = buf.slice(0, bytesRead).toString('utf-8');
          }
        } else
        if (globalThis.window?.prompt) {
          // Browser.
          result = window.prompt('Input: ');  // returns null on cancel
          if (result !== null) {
            result += '\n';
          }
        } else
        {}
        if (!result) {
          return null;
        }
        FS_stdin_getChar_buffer = intArrayFromString(result, true);
      }
      return FS_stdin_getChar_buffer.shift();
    };
  var TTY = {
  ttys:[],
  init() {
        // https://github.com/emscripten-core/emscripten/pull/1555
        // if (ENVIRONMENT_IS_NODE) {
        //   // currently, FS.init does not distinguish if process.stdin is a file or TTY
        //   // device, it always assumes it's a TTY device. because of this, we're forcing
        //   // process.stdin to UTF8 encoding to at least make stdin reading compatible
        //   // with text files until FS.init can be refactored.
        //   process.stdin.setEncoding('utf8');
        // }
      },
  shutdown() {
        // https://github.com/emscripten-core/emscripten/pull/1555
        // if (ENVIRONMENT_IS_NODE) {
        //   // inolen: any idea as to why node -e 'process.stdin.read()' wouldn't exit immediately (with process.stdin being a tty)?
        //   // isaacs: because now it's reading from the stream, you've expressed interest in it, so that read() kicks off a _read() which creates a ReadReq operation
        //   // inolen: I thought read() in that case was a synchronous operation that just grabbed some amount of buffered data if it exists?
        //   // isaacs: it is. but it also triggers a _read() call, which calls readStart() on the handle
        //   // isaacs: do process.stdin.pause() and i'd think it'd probably close the pending call
        //   process.stdin.pause();
        // }
      },
  register(dev, ops) {
        TTY.ttys[dev] = { input: [], output: [], ops: ops };
        FS.registerDevice(dev, TTY.stream_ops);
      },
  stream_ops:{
  open(stream) {
          var tty = TTY.ttys[stream.node.rdev];
          if (!tty) {
            throw new FS.ErrnoError(43);
          }
          stream.tty = tty;
          stream.seekable = false;
        },
  close(stream) {
          // flush any pending line data
          stream.tty.ops.fsync(stream.tty);
        },
  fsync(stream) {
          stream.tty.ops.fsync(stream.tty);
        },
  read(stream, buffer, offset, length, pos /* ignored */) {
          if (!stream.tty || !stream.tty.ops.get_char) {
            throw new FS.ErrnoError(60);
          }
          var bytesRead = 0;
          for (var i = 0; i < length; i++) {
            var result;
            try {
              result = stream.tty.ops.get_char(stream.tty);
            } catch (e) {
              throw new FS.ErrnoError(29);
            }
            if (result === undefined && bytesRead === 0) {
              throw new FS.ErrnoError(6);
            }
            if (result === null || result === undefined) break;
            bytesRead++;
            buffer[offset+i] = result;
          }
          if (bytesRead) {
            stream.node.atime = Date.now();
          }
          return bytesRead;
        },
  write(stream, buffer, offset, length, pos) {
          if (!stream.tty || !stream.tty.ops.put_char) {
            throw new FS.ErrnoError(60);
          }
          try {
            for (var i = 0; i < length; i++) {
              stream.tty.ops.put_char(stream.tty, buffer[offset+i]);
            }
          } catch (e) {
            throw new FS.ErrnoError(29);
          }
          if (length) {
            stream.node.mtime = stream.node.ctime = Date.now();
          }
          return i;
        },
  },
  default_tty_ops:{
  get_char(tty) {
          return FS_stdin_getChar();
        },
  put_char(tty, val) {
          if (val === null || val === 10) {
            out(UTF8ArrayToString(tty.output));
            tty.output = [];
          } else {
            if (val != 0) tty.output.push(val); // val == 0 would cut text output off in the middle.
          }
        },
  fsync(tty) {
          if (tty.output?.length > 0) {
            out(UTF8ArrayToString(tty.output));
            tty.output = [];
          }
        },
  ioctl_tcgets(tty) {
          // typical setting
          return {
            c_iflag: 25856,
            c_oflag: 5,
            c_cflag: 191,
            c_lflag: 35387,
            c_cc: [
              0x03, 0x1c, 0x7f, 0x15, 0x04, 0x00, 0x01, 0x00, 0x11, 0x13, 0x1a, 0x00,
              0x12, 0x0f, 0x17, 0x16, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
              0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ]
          };
        },
  ioctl_tcsets(tty, optional_actions, data) {
          // currently just ignore
          return 0;
        },
  ioctl_tiocgwinsz(tty) {
          return [24, 80];
        },
  },
  default_tty1_ops:{
  put_char(tty, val) {
          if (val === null || val === 10) {
            err(UTF8ArrayToString(tty.output));
            tty.output = [];
          } else {
            if (val != 0) tty.output.push(val);
          }
        },
  fsync(tty) {
          if (tty.output?.length > 0) {
            err(UTF8ArrayToString(tty.output));
            tty.output = [];
          }
        },
  },
  };
  
  
  var mmapAlloc = (size) => {
      abort('internal error: mmapAlloc called but `emscripten_builtin_memalign` native symbol not exported');
    };
  var MEMFS = {
  ops_table:null,
  mount(mount) {
        return MEMFS.createNode(null, '/', 16895, 0);
      },
  createNode(parent, name, mode, dev) {
        if (FS.isBlkdev(mode) || FS.isFIFO(mode)) {
          // not supported
          throw new FS.ErrnoError(63);
        }
        MEMFS.ops_table ||= {
          dir: {
            node: {
              getattr: MEMFS.node_ops.getattr,
              setattr: MEMFS.node_ops.setattr,
              lookup: MEMFS.node_ops.lookup,
              mknod: MEMFS.node_ops.mknod,
              rename: MEMFS.node_ops.rename,
              unlink: MEMFS.node_ops.unlink,
              rmdir: MEMFS.node_ops.rmdir,
              readdir: MEMFS.node_ops.readdir,
              symlink: MEMFS.node_ops.symlink
            },
            stream: {
              llseek: MEMFS.stream_ops.llseek
            }
          },
          file: {
            node: {
              getattr: MEMFS.node_ops.getattr,
              setattr: MEMFS.node_ops.setattr
            },
            stream: {
              llseek: MEMFS.stream_ops.llseek,
              read: MEMFS.stream_ops.read,
              write: MEMFS.stream_ops.write,
              mmap: MEMFS.stream_ops.mmap,
              msync: MEMFS.stream_ops.msync
            }
          },
          link: {
            node: {
              getattr: MEMFS.node_ops.getattr,
              setattr: MEMFS.node_ops.setattr,
              readlink: MEMFS.node_ops.readlink
            },
            stream: {}
          },
          chrdev: {
            node: {
              getattr: MEMFS.node_ops.getattr,
              setattr: MEMFS.node_ops.setattr
            },
            stream: FS.chrdev_stream_ops
          }
        };
        var node = FS.createNode(parent, name, mode, dev);
        if (FS.isDir(node.mode)) {
          node.node_ops = MEMFS.ops_table.dir.node;
          node.stream_ops = MEMFS.ops_table.dir.stream;
          node.contents = {};
        } else if (FS.isFile(node.mode)) {
          node.node_ops = MEMFS.ops_table.file.node;
          node.stream_ops = MEMFS.ops_table.file.stream;
          // The actual number of bytes used in the typed array, as opposed to
          // contents.length which gives the whole capacity.
          node.usedBytes = 0;
          // The byte data of the file is stored in a typed array.
          // Note: typed arrays are not resizable like normal JS arrays are, so
          // there is a small penalty involved for appending file writes that
          // continuously grow a file similar to std::vector capacity vs used.
          node.contents = MEMFS.emptyFileContents ??= new Uint8Array(0);
        } else if (FS.isLink(node.mode)) {
          node.node_ops = MEMFS.ops_table.link.node;
          node.stream_ops = MEMFS.ops_table.link.stream;
        } else if (FS.isChrdev(node.mode)) {
          node.node_ops = MEMFS.ops_table.chrdev.node;
          node.stream_ops = MEMFS.ops_table.chrdev.stream;
        }
        node.atime = node.mtime = node.ctime = Date.now();
        // add the new node to the parent
        if (parent) {
          parent.contents[name] = node;
          parent.atime = parent.mtime = parent.ctime = node.atime;
        }
        return node;
      },
  getFileDataAsTypedArray(node) {
        assert(FS.isFile(node.mode), 'getFileDataAsTypedArray called on non-file');
        return node.contents.subarray(0, node.usedBytes); // Make sure to not return excess unused bytes.
      },
  expandFileStorage(node, newCapacity) {
        var prevCapacity = node.contents.length;
        if (prevCapacity >= newCapacity) return; // No need to expand, the storage was already large enough.
        // Don't expand strictly to the given requested limit if it's only a very
        // small increase, but instead geometrically grow capacity.
        // For small filesizes (<1MB), perform size*2 geometric increase, but for
        // large sizes, do a much more conservative size*1.125 increase to avoid
        // overshooting the allocation cap by a very large margin.
        var CAPACITY_DOUBLING_MAX = 1024 * 1024;
        newCapacity = Math.max(newCapacity, (prevCapacity * (prevCapacity < CAPACITY_DOUBLING_MAX ? 2.0 : 1.125)) >>> 0);
        if (prevCapacity) newCapacity = Math.max(newCapacity, 256); // At minimum allocate 256b for each file when expanding.
        var oldContents = MEMFS.getFileDataAsTypedArray(node);
        node.contents = new Uint8Array(newCapacity); // Allocate new storage.
        node.contents.set(oldContents);
      },
  resizeFileStorage(node, newSize) {
        if (node.usedBytes == newSize) return;
        var oldContents = node.contents;
        node.contents = new Uint8Array(newSize); // Allocate new storage.
        node.contents.set(oldContents.subarray(0, Math.min(newSize, node.usedBytes))); // Copy old data over to the new storage.
        node.usedBytes = newSize;
      },
  node_ops:{
  getattr(node) {
          var attr = {};
          // device numbers reuse inode numbers.
          attr.dev = FS.isChrdev(node.mode) ? node.id : 1;
          attr.ino = node.id;
          attr.mode = node.mode;
          attr.nlink = 1;
          attr.uid = 0;
          attr.gid = 0;
          attr.rdev = node.rdev;
          if (FS.isDir(node.mode)) {
            attr.size = 4096;
          } else if (FS.isFile(node.mode)) {
            attr.size = node.usedBytes;
          } else if (FS.isLink(node.mode)) {
            attr.size = node.link.length;
          } else {
            attr.size = 0;
          }
          attr.atime = new Date(node.atime);
          attr.mtime = new Date(node.mtime);
          attr.ctime = new Date(node.ctime);
          // NOTE: In our implementation, st_blocks = Math.ceil(st_size/st_blksize),
          //       but this is not required by the standard.
          attr.blksize = 4096;
          attr.blocks = Math.ceil(attr.size / attr.blksize);
          return attr;
        },
  setattr(node, attr) {
          for (const key of ["mode", "atime", "mtime", "ctime"]) {
            if (attr[key] != null) {
              node[key] = attr[key];
            }
          }
          if (attr.size !== undefined) {
            MEMFS.resizeFileStorage(node, attr.size);
          }
        },
  lookup(parent, name) {
          throw new FS.ErrnoError(44);
        },
  mknod(parent, name, mode, dev) {
          return MEMFS.createNode(parent, name, mode, dev);
        },
  rename(old_node, new_dir, new_name) {
          var new_node;
          try {
            new_node = FS.lookupNode(new_dir, new_name);
          } catch (e) {}
          if (new_node) {
            if (FS.isDir(old_node.mode)) {
              // if we're overwriting a directory at new_name, make sure it's empty.
              for (var i in new_node.contents) {
                throw new FS.ErrnoError(55);
              }
            }
            FS.hashRemoveNode(new_node);
          }
          // do the internal rewiring
          delete old_node.parent.contents[old_node.name];
          new_dir.contents[new_name] = old_node;
          old_node.name = new_name;
          new_dir.ctime = new_dir.mtime = old_node.parent.ctime = old_node.parent.mtime = Date.now();
        },
  unlink(parent, name) {
          delete parent.contents[name];
          parent.ctime = parent.mtime = Date.now();
        },
  rmdir(parent, name) {
          var node = FS.lookupNode(parent, name);
          for (var i in node.contents) {
            throw new FS.ErrnoError(55);
          }
          delete parent.contents[name];
          parent.ctime = parent.mtime = Date.now();
        },
  readdir(node) {
          return ['.', '..', ...Object.keys(node.contents)];
        },
  symlink(parent, newname, oldpath) {
          var node = MEMFS.createNode(parent, newname, 0o777 | 40960, 0);
          node.link = oldpath;
          return node;
        },
  readlink(node) {
          if (!FS.isLink(node.mode)) {
            throw new FS.ErrnoError(28);
          }
          return node.link;
        },
  },
  stream_ops:{
  read(stream, buffer, offset, length, position) {
          var contents = stream.node.contents;
          if (position >= stream.node.usedBytes) return 0;
          var size = Math.min(stream.node.usedBytes - position, length);
          assert(size >= 0);
          buffer.set(contents.subarray(position, position + size), offset);
          return size;
        },
  write(stream, buffer, offset, length, position, canOwn) {
          assert(buffer.subarray, 'FS.write expects a TypedArray');
  
          if (!length) return 0;
          var node = stream.node;
          node.mtime = node.ctime = Date.now();
  
          if (canOwn) {
            assert(position === 0, 'canOwn must imply no weird position inside the file');
            node.contents = buffer.subarray(offset, offset + length);
            node.usedBytes = length;
          } else if (node.usedBytes === 0 && position === 0) { // If this is a simple first write to an empty file, do a fast set since we don't need to care about old data.
            node.contents = buffer.slice(offset, offset + length);
            node.usedBytes = length;
          } else {
            MEMFS.expandFileStorage(node, position+length);
            // Use typed array write which is available.
            node.contents.set(buffer.subarray(offset, offset + length), position);
            node.usedBytes = Math.max(node.usedBytes, position + length);
          }
          return length;
        },
  llseek(stream, offset, whence) {
          var position = offset;
          if (whence === 1) {
            position += stream.position;
          } else if (whence === 2) {
            if (FS.isFile(stream.node.mode)) {
              position += stream.node.usedBytes;
            }
          }
          if (position < 0) {
            throw new FS.ErrnoError(28);
          }
          return position;
        },
  mmap(stream, length, position, prot, flags) {
          if (!FS.isFile(stream.node.mode)) {
            throw new FS.ErrnoError(43);
          }
          var ptr;
          var allocated;
          var contents = stream.node.contents;
          // Only make a new copy when MAP_PRIVATE is specified.
          if (!(flags & 2) && contents.buffer === HEAP8.buffer) {
            // We can't emulate MAP_SHARED when the file is not backed by the
            // buffer we're mapping to (e.g. the HEAP buffer).
            allocated = false;
            ptr = contents.byteOffset;
          } else {
            allocated = true;
            ptr = mmapAlloc(length);
            if (!ptr) {
              throw new FS.ErrnoError(48);
            }
            if (contents) {
              // Try to avoid unnecessary slices.
              if (position > 0 || position + length < contents.length) {
                if (contents.subarray) {
                  contents = contents.subarray(position, position + length);
                } else {
                  contents = Array.prototype.slice.call(contents, position, position + length);
                }
              }
              HEAP8.set(contents, ptr);
            }
          }
          return { ptr, allocated };
        },
  msync(stream, buffer, offset, length, mmapFlags) {
          MEMFS.stream_ops.write(stream, buffer, 0, length, offset, false);
          // should we check if bytesWritten and length are the same?
          return 0;
        },
  },
  };
  
  var FS_modeStringToFlags = (str) => {
      if (typeof str != 'string') return str;
      var flagModes = {
        'r': 0,
        'r+': 2,
        'w': 512 | 64 | 1,
        'w+': 512 | 64 | 2,
        'a': 1024 | 64 | 1,
        'a+': 1024 | 64 | 2,
      };
      var flags = flagModes[str];
      if (typeof flags == 'undefined') {
        throw new Error(`Unknown file open mode: ${str}`);
      }
      return flags;
    };
  
  var FS_fileDataToTypedArray = (data) => {
      if (typeof data == 'string') {
        data = intArrayFromString(data, true);
      }
      if (!data.subarray) {
        data = new Uint8Array(data);
      }
      return data;
    };
  
  var FS_getMode = (canRead, canWrite) => {
      var mode = 0;
      if (canRead) mode |= 292 | 73;
      if (canWrite) mode |= 146;
      return mode;
    };
  
  
  
  
  var IDBFS = {
  dbs:{
  },
  indexedDB:() => {
        assert(typeof indexedDB != 'undefined', 'IDBFS used, but indexedDB not supported');
        return indexedDB;
      },
  DB_VERSION:21,
  DB_STORE_NAME:"FILE_DATA",
  queuePersist:(mount) => {
        function onPersistComplete() {
          if (mount.idbPersistState === 'again') startPersist(); // If a new sync request has appeared in between, kick off a new sync
          else {
            mount.idbPersistState = 0; // Otherwise reset sync state back to idle to wait for a new sync later
            IDBFS.onAutoPersistStateChanged?.(false);
          }
        }
        function startPersist() {
          mount.idbPersistState = 'idb'; // Mark that we are currently running a sync operation
          IDBFS.onAutoPersistStateChanged?.(true);
          IDBFS.syncfs(mount, /*populate:*/false, onPersistComplete);
        }
  
        if (!mount.idbPersistState) {
          // Programs typically write/copy/move multiple files in the in-memory
          // filesystem within a single app frame, so when a filesystem sync
          // command is triggered, do not start it immediately, but only after
          // the current frame is finished. This way all the modified files
          // inside the main loop tick will be batched up to the same sync.
          mount.idbPersistState = setTimeout(startPersist, 0);
        } else if (mount.idbPersistState === 'idb') {
          // There is an active IndexedDB sync operation in-flight, but we now
          // have accumulated more files to sync. We should therefore queue up
          // a new sync after the current one finishes so that all writes
          // will be properly persisted.
          mount.idbPersistState = 'again';
        }
      },
  mount:(mount) => {
        // reuse core MEMFS functionality
        var mnt = MEMFS.mount(mount);
        // If the automatic IDBFS persistence option has been selected, then automatically persist
        // all modifications to the filesystem as they occur.
        if (mount?.opts?.autoPersist) {
          mount.idbPersistState = 0; // IndexedDB sync starts in idle state
          var memfs_node_ops = mnt.node_ops;
          mnt.node_ops = {...mnt.node_ops}; // Clone node_ops to inject write tracking
          mnt.node_ops.mknod = (parent, name, mode, dev) => {
            var node = memfs_node_ops.mknod(parent, name, mode, dev);
            // Propagate injected node_ops to the newly created child node
            node.node_ops = mnt.node_ops;
            // Remember for each IDBFS node which IDBFS mount point they came from so we know which mount to persist on modification.
            node.idbfs_mount = mnt.mount;
            // Remember original MEMFS stream_ops for this node
            node.memfs_stream_ops = node.stream_ops;
            // Clone stream_ops to inject write tracking
            node.stream_ops = {...node.stream_ops};
  
            // Track all file writes
            node.stream_ops.write = (stream, buffer, offset, length, position, canOwn) => {
              // This file has been modified, we must persist IndexedDB when this file closes
              stream.node.isModified = true;
              return node.memfs_stream_ops.write(stream, buffer, offset, length, position, canOwn);
            };
  
            // Persist IndexedDB on file close
            node.stream_ops.close = (stream) => {
              var n = stream.node;
              if (n.isModified) {
                IDBFS.queuePersist(n.idbfs_mount);
                n.isModified = false;
              }
              if (n.memfs_stream_ops.close) return n.memfs_stream_ops.close(stream);
            };
  
            // Persist the node we just created to IndexedDB
            IDBFS.queuePersist(mnt.mount);
  
            return node;
          };
          // Also kick off persisting the filesystem on other operations that modify the filesystem.
          mnt.node_ops.rmdir   = (...args) => (IDBFS.queuePersist(mnt.mount), memfs_node_ops.rmdir(...args));
          mnt.node_ops.symlink = (...args) => (IDBFS.queuePersist(mnt.mount), memfs_node_ops.symlink(...args));
          mnt.node_ops.unlink  = (...args) => (IDBFS.queuePersist(mnt.mount), memfs_node_ops.unlink(...args));
          mnt.node_ops.rename  = (...args) => (IDBFS.queuePersist(mnt.mount), memfs_node_ops.rename(...args));
        }
        return mnt;
      },
  syncfs:(mount, populate, callback) => {
        IDBFS.getLocalSet(mount, (err, local) => {
          if (err) return callback(err);
  
          IDBFS.getRemoteSet(mount, (err, remote) => {
            if (err) return callback(err);
  
            var src = populate ? remote : local;
            var dst = populate ? local : remote;
  
            IDBFS.reconcile(src, dst, callback);
          });
        });
      },
  quit:() => {
        for (var value of Object.values(IDBFS.dbs)) {
          value.close()
        }
        IDBFS.dbs = {};
      },
  getDB:(name, callback) => {
        // check the cache first
        var db = IDBFS.dbs[name];
        if (db) {
          return callback(null, db);
        }
  
        var req;
        try {
          req = IDBFS.indexedDB().open(name, IDBFS.DB_VERSION);
        } catch (e) {
          return callback(e);
        }
        if (!req) {
          return callback("Unable to connect to IndexedDB");
        }
        req.onupgradeneeded = (e) => {
          var db = /** @type {IDBDatabase} */ (e.target.result);
          var transaction = e.target.transaction;
  
          var fileStore;
  
          if (db.objectStoreNames.contains(IDBFS.DB_STORE_NAME)) {
            fileStore = transaction.objectStore(IDBFS.DB_STORE_NAME);
          } else {
            fileStore = db.createObjectStore(IDBFS.DB_STORE_NAME);
          }
  
          if (!fileStore.indexNames.contains('timestamp')) {
            fileStore.createIndex('timestamp', 'timestamp', { unique: false });
          }
        };
        req.onsuccess = () => {
          db = /** @type {IDBDatabase} */ (req.result);
  
          // add to the cache
          IDBFS.dbs[name] = db;
          callback(null, db);
        };
        req.onerror = (e) => {
          callback(e.target.error);
          e.preventDefault();
        };
      },
  getLocalSet:(mount, callback) => {
        var entries = {};
  
        function isRealDir(p) {
          return p !== '.' && p !== '..';
        };
        function toAbsolute(root) {
          return (p) => PATH.join2(root, p);
        };
  
        var check = FS.readdir(mount.mountpoint).filter(isRealDir).map(toAbsolute(mount.mountpoint));
  
        while (check.length) {
          var path = check.pop();
          var stat;
  
          try {
            stat = FS.lstat(path);
          } catch (e) {
            return callback(e);
          }
  
          if (FS.isDir(stat.mode)) {
            check.push(...FS.readdir(path).filter(isRealDir).map(toAbsolute(path)));
          }
  
          entries[path] = { 'timestamp': stat.mtime };
        }
  
        return callback(null, { type: 'local', entries: entries });
      },
  getRemoteSet:(mount, callback) => {
        var entries = {};
  
        IDBFS.getDB(mount.mountpoint, (err, db) => {
          if (err) return callback(err);
  
          try {
            var transaction = db.transaction([IDBFS.DB_STORE_NAME], 'readonly');
            transaction.onerror = (e) => {
              callback(e.target.error);
              e.preventDefault();
            };
  
            var store = transaction.objectStore(IDBFS.DB_STORE_NAME);
            var index = store.index('timestamp');
  
            index.openKeyCursor().onsuccess = (event) => {
              var cursor = event.target.result;
  
              if (!cursor) {
                return callback(null, { type: 'remote', db, entries });
              }
  
              entries[cursor.primaryKey] = { 'timestamp': cursor.key };
  
              cursor.continue();
            };
          } catch (e) {
            return callback(e);
          }
        });
      },
  loadLocalEntry:(path, callback) => {
        var stat, node;
  
        try {
          var lookup = FS.lookupPath(path);
          node = lookup.node;
          stat = FS.lstat(path);
        } catch (e) {
          return callback(e);
        }
  
        if (FS.isDir(stat.mode)) {
          return callback(null, { 'timestamp': stat.mtime, 'mode': stat.mode });
        } else if (FS.isLink(stat.mode)) {
          return callback(null, { 'timestamp': stat.mtime, 'mode': stat.mode, 'link': node.link, });
        } else if (FS.isFile(stat.mode)) {
          // Performance consideration: storing a normal JavaScript array to a IndexedDB is much slower than storing a typed array.
          // Therefore always convert the file contents to a typed array first before writing the data to IndexedDB.
          node.contents = MEMFS.getFileDataAsTypedArray(node);
          return callback(null, { 'timestamp': stat.mtime, 'mode': stat.mode, 'contents': node.contents });
        } else {
          return callback(new Error('node type not supported'));
        }
      },
  storeLocalEntry:(path, entry, callback) => {
        try {
          if (FS.isDir(entry['mode'])) {
            FS.mkdirTree(path, entry['mode']);
          } else if (FS.isLink(entry['mode'])) {
            FS.symlink(entry['link'], path);
          } else if (FS.isFile(entry['mode'])) {
            FS.writeFile(path, entry['contents'], { canOwn: true });
          } else {
            return callback(new Error('node type not supported'));
          }
  
          FS.chmod(path, entry['mode']);
          FS.utime(path, entry['timestamp'], entry['timestamp']);
        } catch (e) {
          return callback(e);
        }
  
        callback(null);
      },
  removeLocalEntry:(path, callback) => {
        try {
          var stat = FS.lstat(path);
  
          if (FS.isDir(stat.mode)) {
            FS.rmdir(path);
          } else {
            FS.unlink(path);
          }
        } catch (e) {
          return callback(e);
        }
  
        callback(null);
      },
  loadRemoteEntry:(store, path, callback) => {
        var req = store.get(path);
        req.onsuccess = (event) => callback(null, event.target.result);
        req.onerror = (e) => {
          callback(e.target.error);
          e.preventDefault();
        };
      },
  storeRemoteEntry:(store, path, entry, callback) => {
        try {
          var req = store.put(entry, path);
        } catch (e) {
          callback(e);
          return;
        }
        req.onsuccess = (event) => callback();
        req.onerror = (e) => {
          callback(e.target.error);
          e.preventDefault();
        };
      },
  removeRemoteEntry:(store, path, callback) => {
        var req = store.delete(path);
        req.onsuccess = (event) => callback();
        req.onerror = (e) => {
          callback(e.target.error);
          e.preventDefault();
        };
      },
  reconcile:(src, dst, callback) => {
        var total = 0;
  
        var create = [];
        for (var [key, e] of Object.entries(src.entries)) {
          var e2 = dst.entries[key];
          if (!e2 || e['timestamp'].getTime() != e2['timestamp'].getTime()) {
            create.push(key);
            total++;
          }
        }
  
        var remove = [];
        for (var key of Object.keys(dst.entries)) {
          if (!src.entries[key]) {
            remove.push(key);
            total++;
          }
        }
  
        if (!total) {
          return callback(null);
        }
  
        var errored = false;
        var db = src.type === 'remote' ? src.db : dst.db;
        var transaction = db.transaction([IDBFS.DB_STORE_NAME], 'readwrite');
        var store = transaction.objectStore(IDBFS.DB_STORE_NAME);
  
        function done(err) {
          if (err && !errored) {
            errored = true;
            return callback(err);
          }
        };
  
        // transaction may abort if (for example) there is a QuotaExceededError
        transaction.onerror = transaction.onabort = (e) => {
          done(e.target.error);
          e.preventDefault();
        };
  
        transaction.oncomplete = (e) => {
          if (!errored) {
            callback(null);
          }
        };
  
        // sort paths in ascending order so directory entries are created
        // before the files inside them
        for (const path of create.sort()) {
          if (dst.type === 'local') {
            IDBFS.loadRemoteEntry(store, path, (err, entry) => {
              if (err) return done(err);
              IDBFS.storeLocalEntry(path, entry, done);
            });
          } else {
            IDBFS.loadLocalEntry(path, (err, entry) => {
              if (err) return done(err);
              IDBFS.storeRemoteEntry(store, path, entry, done);
            });
          }
        }
  
        // sort paths in descending order so files are deleted before their
        // parent directories
        for (var path of remove.sort().reverse()) {
          if (dst.type === 'local') {
            IDBFS.removeLocalEntry(path, done);
          } else {
            IDBFS.removeRemoteEntry(store, path, done);
          }
        }
      },
  };
  
  
  
  var strError = (errno) => UTF8ToString(_strerror(errno));
  
  var ERRNO_CODES = {
      'EPERM': 63,
      'ENOENT': 44,
      'ESRCH': 71,
      'EINTR': 27,
      'EIO': 29,
      'ENXIO': 60,
      'E2BIG': 1,
      'ENOEXEC': 45,
      'EBADF': 8,
      'ECHILD': 12,
      'EAGAIN': 6,
      'EWOULDBLOCK': 6,
      'ENOMEM': 48,
      'EACCES': 2,
      'EFAULT': 21,
      'ENOTBLK': 105,
      'EBUSY': 10,
      'EEXIST': 20,
      'EXDEV': 75,
      'ENODEV': 43,
      'ENOTDIR': 54,
      'EISDIR': 31,
      'EINVAL': 28,
      'ENFILE': 41,
      'EMFILE': 33,
      'ENOTTY': 59,
      'ETXTBSY': 74,
      'EFBIG': 22,
      'ENOSPC': 51,
      'ESPIPE': 70,
      'EROFS': 69,
      'EMLINK': 34,
      'EPIPE': 64,
      'EDOM': 18,
      'ERANGE': 68,
      'ENOMSG': 49,
      'EIDRM': 24,
      'ECHRNG': 106,
      'EL2NSYNC': 156,
      'EL3HLT': 107,
      'EL3RST': 108,
      'ELNRNG': 109,
      'EUNATCH': 110,
      'ENOCSI': 111,
      'EL2HLT': 112,
      'EDEADLK': 16,
      'ENOLCK': 46,
      'EBADE': 113,
      'EBADR': 114,
      'EXFULL': 115,
      'ENOANO': 104,
      'EBADRQC': 103,
      'EBADSLT': 102,
      'EDEADLOCK': 16,
      'EBFONT': 101,
      'ENOSTR': 100,
      'ENODATA': 116,
      'ETIME': 117,
      'ENOSR': 118,
      'ENONET': 119,
      'ENOPKG': 120,
      'EREMOTE': 121,
      'ENOLINK': 47,
      'EADV': 122,
      'ESRMNT': 123,
      'ECOMM': 124,
      'EPROTO': 65,
      'EMULTIHOP': 36,
      'EDOTDOT': 125,
      'EBADMSG': 9,
      'ENOTUNIQ': 126,
      'EBADFD': 127,
      'EREMCHG': 128,
      'ELIBACC': 129,
      'ELIBBAD': 130,
      'ELIBSCN': 131,
      'ELIBMAX': 132,
      'ELIBEXEC': 133,
      'ENOSYS': 52,
      'ENOTEMPTY': 55,
      'ENAMETOOLONG': 37,
      'ELOOP': 32,
      'EOPNOTSUPP': 138,
      'EPFNOSUPPORT': 139,
      'ECONNRESET': 15,
      'ENOBUFS': 42,
      'EAFNOSUPPORT': 5,
      'EPROTOTYPE': 67,
      'ENOTSOCK': 57,
      'ENOPROTOOPT': 50,
      'ESHUTDOWN': 140,
      'ECONNREFUSED': 14,
      'EADDRINUSE': 3,
      'ECONNABORTED': 13,
      'ENETUNREACH': 40,
      'ENETDOWN': 38,
      'ETIMEDOUT': 73,
      'EHOSTDOWN': 142,
      'EHOSTUNREACH': 23,
      'EINPROGRESS': 26,
      'EALREADY': 7,
      'EDESTADDRREQ': 17,
      'EMSGSIZE': 35,
      'EPROTONOSUPPORT': 66,
      'ESOCKTNOSUPPORT': 137,
      'EADDRNOTAVAIL': 4,
      'ENETRESET': 39,
      'EISCONN': 30,
      'ENOTCONN': 53,
      'ETOOMANYREFS': 141,
      'EUSERS': 136,
      'EDQUOT': 19,
      'ESTALE': 72,
      'ENOTSUP': 138,
      'ENOMEDIUM': 148,
      'EILSEQ': 25,
      'EOVERFLOW': 61,
      'ECANCELED': 11,
      'ENOTRECOVERABLE': 56,
      'EOWNERDEAD': 62,
      'ESTRPIPE': 135,
    };
  
  var asyncLoad = async (url) => {
      var arrayBuffer = await readAsync(url);
      assert(arrayBuffer, `Loading data file "${url}" failed (no arrayBuffer).`);
      return new Uint8Array(arrayBuffer);
    };
  
  
  var FS_createDataFile = (...args) => FS.createDataFile(...args);
  
  var getUniqueRunDependency = (id) => {
      var orig = id;
      while (1) {
        if (!runDependencyTracking[id]) return id;
        id = orig + Math.random();
      }
    };
  
  var dependenciesPromise = null;
  var resolveRunDependencies = async () => dependenciesPromise;
  var runDependencies = 0;
  
  
  
  var runDependencyTracking = {
  };
  
  var runDependencyWatcher = null;
  var removeRunDependency = (id) => {
      runDependencies--;
  
      Module['monitorRunDependencies']?.(runDependencies);
  
      assert(id, 'removeRunDependency requires an ID');
      assert(runDependencyTracking[id]);
      delete runDependencyTracking[id];
      if (!runDependencies) {
        if (runDependencyWatcher !== null) {
          clearInterval(runDependencyWatcher);
          runDependencyWatcher = null;
        }
        dependenciesPromise.resolve();
      }
    };
  
  
  
  var addRunDependency = (id) => {
      if (!runDependencies) {
        var resolve;
        dependenciesPromise = new Promise((r) => resolve = r);
        dependenciesPromise.resolve = resolve;
      }
      runDependencies++;
  
      Module['monitorRunDependencies']?.(runDependencies);
  
      assert(id, 'addRunDependency requires an ID')
      assert(!runDependencyTracking[id]);
      runDependencyTracking[id] = 1;
      if (runDependencyWatcher === null && globalThis.setInterval) {
        // Check for missing dependencies every few seconds
        runDependencyWatcher = setInterval(() => {
          if (ABORT) {
            clearInterval(runDependencyWatcher);
            runDependencyWatcher = null;
            return;
          }
          var shown = false;
          for (var dep in runDependencyTracking) {
            if (!shown) {
              shown = true;
              err('still waiting on run dependencies:');
            }
            err(`dependency: ${dep}`);
          }
          if (shown) {
            err('(end of list)');
          }
        }, 10000);
        // Prevent this timer from keeping the runtime alive if nothing
        // else is.
        runDependencyWatcher.unref?.()
      }
    };
  
  
  var preloadPlugins = [];
  var FS_handledByPreloadPlugin = async (byteArray, fullname) => {
      // Ensure plugins are ready.
      if (typeof Browser != 'undefined') Browser.init();
  
      for (var plugin of preloadPlugins) {
        if (plugin['canHandle'](fullname)) {
          assert(plugin['handle'].constructor.name === 'AsyncFunction', 'Filesystem plugin handlers must be async functions (See #24914)')
          return plugin['handle'](byteArray, fullname);
        }
      }
      // If no plugin handled this file then return the original/unmodified
      // byteArray.
      return byteArray;
    };
  var FS_preloadFile = async (parent, name, url, canRead, canWrite, dontCreateFile, canOwn, preFinish) => {
      // TODO we should allow people to just pass in a complete filename instead
      // of parent and name being that we just join them anyways
      var fullname = name ? PATH_FS.resolve(PATH.join2(parent, name)) : parent;
      var dep = getUniqueRunDependency(`cp ${fullname}`); // might have several active requests for the same fullname
      addRunDependency(dep);
  
      try {
        var byteArray = url;
        if (typeof url == 'string') {
          byteArray = await asyncLoad(url);
        }
  
        byteArray = await FS_handledByPreloadPlugin(byteArray, fullname);
        preFinish?.();
        if (!dontCreateFile) {
          FS_createDataFile(parent, name, byteArray, canRead, canWrite, canOwn);
        }
      } finally {
        removeRunDependency(dep);
      }
    };
  var FS_createPreloadedFile = (parent, name, url, canRead, canWrite, onload, onerror, dontCreateFile, canOwn, preFinish) => {
      FS_preloadFile(parent, name, url, canRead, canWrite, dontCreateFile, canOwn, preFinish).then(onload).catch(onerror);
    };
  var FS = {
  root:null,
  mounts:[],
  devices:{
  },
  streams:[],
  nextInode:1,
  nameTable:null,
  currentPath:"/",
  initialized:false,
  ignorePermissions:true,
  filesystems:null,
  syncFSRequests:0,
  ErrnoError:class extends Error {
        name = 'ErrnoError';
        // We set the `name` property to be able to identify `FS.ErrnoError`
        // - the `name` is a standard ECMA-262 property of error objects. Kind of good to have it anyway.
        // - when using PROXYFS, an error can come from an underlying FS
        // as different FS objects have their own FS.ErrnoError each,
        // the test `err instanceof FS.ErrnoError` won't detect an error coming from another filesystem, causing bugs.
        // we'll use the reliable test `err.name == "ErrnoError"` instead
        constructor(errno) {
          super(runtimeInitialized ? strError(errno) : '');
          this.errno = errno;
          for (var key in ERRNO_CODES) {
            if (ERRNO_CODES[key] === errno) {
              this.code = key;
              break;
            }
          }
        }
      },
  FSStream:class {
        shared = {};
        get object() {
          return this.node;
        }
        set object(val) {
          this.node = val;
        }
        get isRead() {
          return (this.flags & 2097155) !== 1;
        }
        get isWrite() {
          return (this.flags & 2097155) !== 0;
        }
        get isAppend() {
          return (this.flags & 1024);
        }
        get flags() {
          return this.shared.flags;
        }
        set flags(val) {
          this.shared.flags = val;
        }
        get position() {
          return this.shared.position;
        }
        set position(val) {
          this.shared.position = val;
        }
      },
  FSNode:class {
        node_ops = {};
        stream_ops = {};
        readMode = 292 | 73;
        writeMode = 146;
        mounted = null;
        constructor(parent, name, mode, rdev) {
          if (!parent) {
            parent = this;  // root node sets parent to itself
          }
          this.parent = parent;
          this.mount = parent.mount;
          this.id = FS.nextInode++;
          this.name = name;
          this.mode = mode;
          this.rdev = rdev;
          this.atime = this.mtime = this.ctime = Date.now();
        }
        get read() {
          return (this.mode & this.readMode) === this.readMode;
        }
        set read(val) {
          val ? this.mode |= this.readMode : this.mode &= ~this.readMode;
        }
        get write() {
          return (this.mode & this.writeMode) === this.writeMode;
        }
        set write(val) {
          val ? this.mode |= this.writeMode : this.mode &= ~this.writeMode;
        }
        get isFolder() {
          return FS.isDir(this.mode);
        }
        get isDevice() {
          return FS.isChrdev(this.mode);
        }
      },
  lookupPath(path, opts = {}) {
        if (!path) {
          throw new FS.ErrnoError(44);
        }
        opts.follow_mount ??= true
  
        if (!PATH.isAbs(path)) {
          path = FS.cwd() + '/' + path;
        }
  
        // limit max consecutive symlinks to SYMLOOP_MAX.
        linkloop: for (var nlinks = 0; nlinks < 40; nlinks++) {
          // split the absolute path
          var parts = path.split('/').filter((p) => !!p);
  
          // start at the root
          var current = FS.root;
          var current_path = '/';
  
          for (var i = 0; i < parts.length; i++) {
            var islast = (i === parts.length-1);
            if (islast && opts.parent) {
              // stop resolving
              break;
            }
  
            if (parts[i] === '.') {
              continue;
            }
  
            if (parts[i] === '..') {
              current_path = PATH.dirname(current_path);
              if (FS.isRoot(current)) {
                path = current_path + '/' + parts.slice(i + 1).join('/');
                // We're making progress here, don't let many consecutive ..'s
                // lead to ELOOP
                nlinks--;
                continue linkloop;
              } else {
                current = current.parent;
              }
              continue;
            }
  
            current_path = PATH.join2(current_path, parts[i]);
            try {
              current = FS.lookupNode(current, parts[i]);
            } catch (e) {
              // if noent_okay is true, suppress a ENOENT in the last component
              // and return an object with an undefined node. This is needed for
              // resolving symlinks in the path when creating a file.
              if ((e?.errno === 44) && islast && opts.noent_okay) {
                return { path: current_path };
              }
              throw e;
            }
  
            // jump to the mount's root node if this is a mountpoint
            if (FS.isMountpoint(current) && (!islast || opts.follow_mount)) {
              current = current.mounted.root;
            }
  
            // by default, lookupPath will not follow a symlink if it is the final path component.
            // setting opts.follow = true will override this behavior.
            if (FS.isLink(current.mode) && (!islast || opts.follow)) {
              if (!current.node_ops.readlink) {
                throw new FS.ErrnoError(52);
              }
              var link = current.node_ops.readlink(current);
              if (!PATH.isAbs(link)) {
                link = PATH.dirname(current_path) + '/' + link;
              }
              path = link + '/' + parts.slice(i + 1).join('/');
              continue linkloop;
            }
          }
          return { path: current_path, node: current };
        }
        throw new FS.ErrnoError(32);
      },
  getPath(node) {
        var path;
        while (true) {
          if (FS.isRoot(node)) {
            var mount = node.mount.mountpoint;
            if (!path) return mount;
            return mount[mount.length-1] !== '/' ? `${mount}/${path}` : mount + path;
          }
          path = path ? `${node.name}/${path}` : node.name;
          node = node.parent;
        }
      },
  hashName(parentid, name) {
        var hash = 0;
  
        for (var i = 0; i < name.length; i++) {
          hash = ((hash << 5) - hash + name.charCodeAt(i)) | 0;
        }
        return ((parentid + hash) >>> 0) % FS.nameTable.length;
      },
  hashAddNode(node) {
        var hash = FS.hashName(node.parent.id, node.name);
        node.name_next = FS.nameTable[hash];
        FS.nameTable[hash] = node;
      },
  hashRemoveNode(node) {
        var hash = FS.hashName(node.parent.id, node.name);
        if (FS.nameTable[hash] === node) {
          FS.nameTable[hash] = node.name_next;
        } else {
          var current = FS.nameTable[hash];
          while (current) {
            if (current.name_next === node) {
              current.name_next = node.name_next;
              break;
            }
            current = current.name_next;
          }
        }
      },
  lookupNode(parent, name) {
        var errCode = FS.mayLookup(parent);
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        var hash = FS.hashName(parent.id, name);
        for (var node = FS.nameTable[hash]; node; node = node.name_next) {
          var nodeName = node.name;
          if (node.parent.id === parent.id && nodeName === name) {
            return node;
          }
        }
        // if we failed to find it in the cache, call into the VFS
        return FS.lookup(parent, name);
      },
  createNode(parent, name, mode, rdev) {
        assert(typeof parent == 'object')
        var node = new FS.FSNode(parent, name, mode, rdev);
  
        FS.hashAddNode(node);
  
        return node;
      },
  destroyNode(node) {
        FS.hashRemoveNode(node);
      },
  isRoot(node) {
        return node === node.parent;
      },
  isMountpoint(node) {
        return !!node.mounted;
      },
  isFile(mode) {
        return (mode & 61440) === 32768;
      },
  isDir(mode) {
        return (mode & 61440) === 16384;
      },
  isLink(mode) {
        return (mode & 61440) === 40960;
      },
  isChrdev(mode) {
        return (mode & 61440) === 8192;
      },
  isBlkdev(mode) {
        return (mode & 61440) === 24576;
      },
  isFIFO(mode) {
        return (mode & 61440) === 4096;
      },
  isSocket(mode) {
        return (mode & 49152) === 49152;
      },
  flagsToPermissionString(flag) {
        var perms = ['r', 'w', 'rw'][flag & 3];
        if ((flag & 512)) {
          perms += 'w';
        }
        return perms;
      },
  nodePermissions(node, perms) {
        if (FS.ignorePermissions) {
          return 0;
        }
        // return 0 if any user, group or owner bits are set.
        if (perms.includes('r') && !(node.mode & 292)) {
          return 2;
        }
        if (perms.includes('w') && !(node.mode & 146)) {
          return 2;
        }
        if (perms.includes('x') && !(node.mode & 73)) {
          return 2;
        }
        return 0;
      },
  mayLookup(dir) {
        if (!FS.isDir(dir.mode)) return 54;
        var errCode = FS.nodePermissions(dir, 'x');
        if (errCode) return errCode;
        if (!dir.node_ops.lookup) return 2;
        return 0;
      },
  mayCreate(dir, name) {
        if (!FS.isDir(dir.mode)) {
          return 54;
        }
        try {
          var node = FS.lookupNode(dir, name);
          return 20;
        } catch (e) {
        }
        return FS.nodePermissions(dir, 'wx');
      },
  mayDelete(dir, name, isdir) {
        var node;
        try {
          node = FS.lookupNode(dir, name);
        } catch (e) {
          return e.errno;
        }
        var errCode = FS.nodePermissions(dir, 'wx');
        if (errCode) {
          return errCode;
        }
        if (isdir) {
          if (!FS.isDir(node.mode)) {
            return 54;
          }
          if (FS.isRoot(node) || FS.getPath(node) === FS.cwd()) {
            return 10;
          }
        } else if (FS.isDir(node.mode)) {
          return 31;
        }
        return 0;
      },
  mayOpen(node, flags) {
        if (!node) {
          return 44;
        }
        if (FS.isLink(node.mode)) {
          return 32;
        }
        var mode = FS.flagsToPermissionString(flags);
        if (FS.isDir(node.mode)) {
          // opening for write
          // TODO: check for O_SEARCH? (== search for dir only)
          if (mode !== 'r' || (flags & (512 | 64))) {
            return 31;
          }
        }
        return FS.nodePermissions(node, mode);
      },
  checkOpExists(op, err) {
        if (!op) {
          throw new FS.ErrnoError(err);
        }
        return op;
      },
  MAX_OPEN_FDS:4096,
  nextfd() {
        for (var fd = 0; fd <= FS.MAX_OPEN_FDS; fd++) {
          if (!FS.streams[fd]) {
            return fd;
          }
        }
        throw new FS.ErrnoError(33);
      },
  getStreamChecked(fd) {
        var stream = FS.getStream(fd);
        if (!stream) {
          throw new FS.ErrnoError(8);
        }
        return stream;
      },
  getStream:(fd) => FS.streams[fd],
  createStream(stream, fd = -1) {
        assert(fd >= -1);
  
        // clone it, so we can return an instance of FSStream
        stream = Object.assign(new FS.FSStream(), stream);
        if (fd == -1) {
          fd = FS.nextfd();
        }
        stream.fd = fd;
        FS.streams[fd] = stream;
        return stream;
      },
  closeStream(fd) {
        FS.streams[fd] = null;
      },
  dupStream(origStream, fd = -1) {
        var stream = FS.createStream(origStream, fd);
        stream.stream_ops?.dup?.(stream);
        return stream;
      },
  doSetAttr(stream, node, attr) {
        var setattr = stream?.stream_ops.setattr;
        var arg = setattr ? stream : node;
        setattr ??= node.node_ops.setattr;
        FS.checkOpExists(setattr, 63)
        try {
          setattr(arg, attr);
        } catch (e) {
          if (e instanceof RangeError) {
            throw new FS.ErrnoError(22);
          }
          throw e;
        }
      },
  chrdev_stream_ops:{
  open(stream) {
          var device = FS.getDevice(stream.node.rdev);
          // override node's stream ops with the device's
          stream.stream_ops = device.stream_ops;
          // forward the open call
          stream.stream_ops.open?.(stream);
        },
  llseek() {
          throw new FS.ErrnoError(70);
        },
  },
  major:(dev) => ((dev) >> 8),
  minor:(dev) => ((dev) & 0xff),
  makedev:(ma, mi) => ((ma) << 8 | (mi)),
  registerDevice(dev, ops) {
        FS.devices[dev] = { stream_ops: ops };
      },
  getDevice:(dev) => FS.devices[dev],
  getMounts(mount) {
        var mounts = [];
        var check = [mount];
  
        while (check.length) {
          var m = check.pop();
  
          mounts.push(m);
  
          check.push(...m.mounts);
        }
  
        return mounts;
      },
  syncfs(populate, callback) {
        if (typeof populate == 'function') {
          callback = populate;
          populate = false;
        }
  
        FS.syncFSRequests++;
  
        if (FS.syncFSRequests > 1) {
          err(`warning: ${FS.syncFSRequests} FS.syncfs operations in flight at once, probably just doing extra work`);
        }
  
        var mounts = FS.getMounts(FS.root.mount);
        var completed = 0;
  
        function doCallback(errCode) {
          assert(FS.syncFSRequests > 0);
          FS.syncFSRequests--;
          return callback(errCode);
        }
  
        function done(errCode) {
          if (errCode) {
            if (!done.errored) {
              done.errored = true;
              return doCallback(errCode);
            }
            return;
          }
          if (++completed >= mounts.length) {
            doCallback(null);
          }
        };
  
        // sync all mounts
        for (var mount of mounts) {
          if (mount.type.syncfs) {
            mount.type.syncfs(mount, populate, done);
          } else {
            done(null);
          }
        }
      },
  mount(type, opts, mountpoint) {
        if (typeof type == 'string') {
          // The filesystem was not included, and instead we have an error
          // message stored in the variable.
          throw type;
        }
        var root = mountpoint === '/';
        var pseudo = !mountpoint;
        var node;
  
        if (root && FS.root) {
          throw new FS.ErrnoError(10);
        } else if (!root && !pseudo) {
          var lookup = FS.lookupPath(mountpoint, { follow_mount: false });
  
          mountpoint = lookup.path;  // use the absolute path
          node = lookup.node;
  
          if (FS.isMountpoint(node)) {
            throw new FS.ErrnoError(10);
          }
  
          if (!FS.isDir(node.mode)) {
            throw new FS.ErrnoError(54);
          }
        }
  
        var mount = {
          type,
          opts,
          mountpoint,
          mounts: []
        };
  
        // create a root node for the fs
        var mountRoot = type.mount(mount);
        mountRoot.mount = mount;
        mount.root = mountRoot;
  
        if (root) {
          FS.root = mountRoot;
        } else if (node) {
          // set as a mountpoint
          node.mounted = mount;
  
          // add the new mount to the current mount's children
          if (node.mount) {
            node.mount.mounts.push(mount);
          }
        }
  
        return mountRoot;
      },
  unmount(mountpoint) {
        var lookup = FS.lookupPath(mountpoint, { follow_mount: false });
  
        if (!FS.isMountpoint(lookup.node)) {
          throw new FS.ErrnoError(28);
        }
  
        // destroy the nodes for this mount, and all its child mounts
        var node = lookup.node;
        var mount = node.mounted;
        var mounts = FS.getMounts(mount);
  
        for (var [hash, current] of Object.entries(FS.nameTable)) {
          while (current) {
            var next = current.name_next;
  
            if (mounts.includes(current.mount)) {
              FS.destroyNode(current);
            }
  
            current = next;
          }
        }
  
        // no longer a mountpoint
        node.mounted = null;
  
        // remove this mount from the child mounts
        var idx = node.mount.mounts.indexOf(mount);
        assert(idx !== -1);
        node.mount.mounts.splice(idx, 1);
      },
  lookup(parent, name) {
        return parent.node_ops.lookup(parent, name);
      },
  mknod(path, mode, dev) {
        var lookup = FS.lookupPath(path, { parent: true });
        var parent = lookup.node;
        var name = PATH.basename(path);
        if (!name) {
          throw new FS.ErrnoError(28);
        }
        if (name === '.' || name === '..') {
          throw new FS.ErrnoError(20);
        }
        var errCode = FS.mayCreate(parent, name);
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        if (!parent.node_ops.mknod) {
          throw new FS.ErrnoError(63);
        }
        return parent.node_ops.mknod(parent, name, mode, dev);
      },
  statfs(path) {
        return FS.statfsNode(FS.lookupPath(path, {follow: true}).node);
      },
  statfsStream(stream) {
        // We keep a separate statfsStream function because noderawfs overrides
        // it. In noderawfs, stream.node is sometimes null. Instead, we need to
        // look at stream.path.
        return FS.statfsNode(stream.node);
      },
  statfsNode(node) {
        // NOTE: None of the defaults here are true. We're just returning safe and
        //       sane values. Currently nodefs and rawfs replace these defaults,
        //       other file systems leave them alone.
        var rtn = {
          bsize: 4096,
          frsize: 4096,
          blocks: 1e6,
          bfree: 5e5,
          bavail: 5e5,
          files: FS.nextInode,
          ffree: FS.nextInode - 1,
          fsid: 42,
          flags: 2,
          namelen: 255,
        };
  
        if (node.node_ops.statfs) {
          Object.assign(rtn, node.node_ops.statfs(node.mount.opts.root));
        }
        return rtn;
      },
  create(path, mode = 0o666) {
        mode &= 4095;
        mode |= 32768;
        return FS.mknod(path, mode, 0);
      },
  mkdir(path, mode = 0o777) {
        mode &= 511 | 512;
        mode |= 16384;
        return FS.mknod(path, mode, 0);
      },
  mkdirTree(path, mode) {
        var dirs = path.split('/');
        var d = '';
        for (var dir of dirs) {
          if (!dir) continue;
          if (d || PATH.isAbs(path)) d += '/';
          d += dir;
          try {
            FS.mkdir(d, mode);
          } catch(e) {
            if (e.errno != 20) throw e;
          }
        }
      },
  mkdev(path, mode, dev) {
        if (typeof dev == 'undefined') {
          dev = mode;
          mode = 0o666;
        }
        mode |= 8192;
        return FS.mknod(path, mode, dev);
      },
  symlink(oldpath, newpath) {
        if (!PATH_FS.resolve(oldpath)) {
          throw new FS.ErrnoError(44);
        }
        var lookup = FS.lookupPath(newpath, { parent: true });
        var parent = lookup.node;
        if (!parent) {
          throw new FS.ErrnoError(44);
        }
        var newname = PATH.basename(newpath);
        var errCode = FS.mayCreate(parent, newname);
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        if (!parent.node_ops.symlink) {
          throw new FS.ErrnoError(63);
        }
        return parent.node_ops.symlink(parent, newname, oldpath);
      },
  rename(old_path, new_path) {
        var old_dirname = PATH.dirname(old_path);
        var new_dirname = PATH.dirname(new_path);
        var old_name = PATH.basename(old_path);
        var new_name = PATH.basename(new_path);
        // parents must exist
        var lookup, old_dir, new_dir;
  
        // let the errors from non existent directories percolate up
        lookup = FS.lookupPath(old_path, { parent: true });
        old_dir = lookup.node;
        lookup = FS.lookupPath(new_path, { parent: true });
        new_dir = lookup.node;
  
        if (!old_dir || !new_dir) throw new FS.ErrnoError(44);
        // need to be part of the same mount
        if (old_dir.mount !== new_dir.mount) {
          throw new FS.ErrnoError(75);
        }
        // source must exist
        var old_node = FS.lookupNode(old_dir, old_name);
        // old path should not be an ancestor of the new path
        var relative = PATH_FS.relative(old_path, new_dirname);
        if (relative.charAt(0) !== '.') {
          throw new FS.ErrnoError(28);
        }
        // new path should not be an ancestor of the old path
        relative = PATH_FS.relative(new_path, old_dirname);
        if (relative.charAt(0) !== '.') {
          throw new FS.ErrnoError(55);
        }
        // see if the new path already exists
        var new_node;
        try {
          new_node = FS.lookupNode(new_dir, new_name);
        } catch (e) {
          // not fatal
        }
        // early out if nothing needs to change
        if (old_node === new_node) {
          return;
        }
        // we'll need to delete the old entry
        var isdir = FS.isDir(old_node.mode);
        var errCode = FS.mayDelete(old_dir, old_name, isdir);
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        // need delete permissions if we'll be overwriting.
        // need create permissions if new doesn't already exist.
        errCode = new_node ?
          FS.mayDelete(new_dir, new_name, isdir) :
          FS.mayCreate(new_dir, new_name);
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        if (!old_dir.node_ops.rename) {
          throw new FS.ErrnoError(63);
        }
        if (FS.isMountpoint(old_node) || (new_node && FS.isMountpoint(new_node))) {
          throw new FS.ErrnoError(10);
        }
        // if we are going to change the parent, check write permissions
        if (new_dir !== old_dir) {
          errCode = FS.nodePermissions(old_dir, 'w');
          if (errCode) {
            throw new FS.ErrnoError(errCode);
          }
        }
        // remove the node from the lookup hash
        FS.hashRemoveNode(old_node);
        // do the underlying fs rename
        try {
          old_dir.node_ops.rename(old_node, new_dir, new_name);
          // update old node (we do this here to avoid each backend
          // needing to)
          old_node.parent = new_dir;
        } catch (e) {
          throw e;
        } finally {
          // add the node back to the hash (in case node_ops.rename
          // changed its name)
          FS.hashAddNode(old_node);
        }
      },
  rmdir(path) {
        var lookup = FS.lookupPath(path, { parent: true });
        var parent = lookup.node;
        var name = PATH.basename(path);
        var node = FS.lookupNode(parent, name);
        var errCode = FS.mayDelete(parent, name, true);
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        if (!parent.node_ops.rmdir) {
          throw new FS.ErrnoError(63);
        }
        if (FS.isMountpoint(node)) {
          throw new FS.ErrnoError(10);
        }
        parent.node_ops.rmdir(parent, name);
        FS.destroyNode(node);
      },
  readdir(path) {
        var lookup = FS.lookupPath(path, { follow: true });
        var node = lookup.node;
        var readdir = FS.checkOpExists(node.node_ops.readdir, 54);
        return readdir(node);
      },
  unlink(path) {
        var lookup = FS.lookupPath(path, { parent: true });
        var parent = lookup.node;
        if (!parent) {
          throw new FS.ErrnoError(44);
        }
        var name = PATH.basename(path);
        var node = FS.lookupNode(parent, name);
        var errCode = FS.mayDelete(parent, name, false);
        if (errCode) {
          // According to POSIX, we should map EISDIR to EPERM, but
          // we instead do what Linux does (and we must, as we use
          // the musl linux libc).
          throw new FS.ErrnoError(errCode);
        }
        if (!parent.node_ops.unlink) {
          throw new FS.ErrnoError(63);
        }
        if (FS.isMountpoint(node)) {
          throw new FS.ErrnoError(10);
        }
        parent.node_ops.unlink(parent, name);
        FS.destroyNode(node);
      },
  readlink(path) {
        var lookup = FS.lookupPath(path);
        var link = lookup.node;
        if (!link) {
          throw new FS.ErrnoError(44);
        }
        if (!link.node_ops.readlink) {
          throw new FS.ErrnoError(28);
        }
        return link.node_ops.readlink(link);
      },
  stat(path, dontFollow) {
        var lookup = FS.lookupPath(path, { follow: !dontFollow });
        var node = lookup.node;
        var getattr = FS.checkOpExists(node.node_ops.getattr, 63);
        return getattr(node);
      },
  fstat(fd) {
        var stream = FS.getStreamChecked(fd);
        var node = stream.node;
        var getattr = stream.stream_ops.getattr;
        var arg = getattr ? stream : node;
        getattr ??= node.node_ops.getattr;
        FS.checkOpExists(getattr, 63)
        return getattr(arg);
      },
  lstat(path) {
        return FS.stat(path, true);
      },
  doChmod(stream, node, mode, dontFollow) {
        FS.doSetAttr(stream, node, {
          mode: (mode & 4095) | (node.mode & ~4095),
          ctime: Date.now(),
          dontFollow
        });
      },
  chmod(path, mode, dontFollow) {
        var node;
        if (typeof path == 'string') {
          var lookup = FS.lookupPath(path, { follow: !dontFollow });
          node = lookup.node;
        } else {
          node = path;
        }
        FS.doChmod(null, node, mode, dontFollow);
      },
  lchmod(path, mode) {
        FS.chmod(path, mode, true);
      },
  fchmod(fd, mode) {
        var stream = FS.getStreamChecked(fd);
        FS.doChmod(stream, stream.node, mode, false);
      },
  doChown(stream, node, dontFollow) {
        FS.doSetAttr(stream, node, {
          timestamp: Date.now(),
          dontFollow
          // we ignore the uid / gid for now
        });
      },
  chown(path, uid, gid, dontFollow) {
        var node;
        if (typeof path == 'string') {
          var lookup = FS.lookupPath(path, { follow: !dontFollow });
          node = lookup.node;
        } else {
          node = path;
        }
        FS.doChown(null, node, dontFollow);
      },
  lchown(path, uid, gid) {
        FS.chown(path, uid, gid, true);
      },
  fchown(fd, uid, gid) {
        var stream = FS.getStreamChecked(fd);
        FS.doChown(stream, stream.node, false);
      },
  doTruncate(stream, node, len) {
        if (FS.isDir(node.mode)) {
          throw new FS.ErrnoError(31);
        }
        if (!FS.isFile(node.mode)) {
          throw new FS.ErrnoError(28);
        }
        var errCode = FS.nodePermissions(node, 'w');
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        FS.doSetAttr(stream, node, {
          size: len,
          timestamp: Date.now()
        });
      },
  truncate(path, len) {
        if (len < 0) {
          throw new FS.ErrnoError(28);
        }
        var node;
        if (typeof path == 'string') {
          var lookup = FS.lookupPath(path, { follow: true });
          node = lookup.node;
        } else {
          node = path;
        }
        FS.doTruncate(null, node, len);
      },
  ftruncate(fd, len) {
        var stream = FS.getStreamChecked(fd);
        if (len < 0 || (stream.flags & 2097155) === 0) {
          throw new FS.ErrnoError(28);
        }
        FS.doTruncate(stream, stream.node, len);
      },
  utime(path, atime, mtime) {
        var lookup = FS.lookupPath(path, { follow: true });
        var node = lookup.node;
        var setattr = FS.checkOpExists(node.node_ops.setattr, 63);
        setattr(node, {
          atime: atime,
          mtime: mtime
        });
      },
  open(path, flags, mode = 0o666) {
        if (path === "") {
          throw new FS.ErrnoError(44);
        }
        flags = FS_modeStringToFlags(flags);
        if ((flags & 64)) {
          mode = (mode & 4095) | 32768;
        } else {
          mode = 0;
        }
        var node;
        var isDirPath;
        if (typeof path == 'object') {
          node = path;
        } else {
          isDirPath = path.endsWith("/");
          // noent_okay makes it so that if the final component of the path
          // doesn't exist, lookupPath returns `node: undefined`. `path` will be
          // updated to point to the target of all symlinks.
          var lookup = FS.lookupPath(path, {
            follow: !(flags & 131072),
            noent_okay: true
          });
          node = lookup.node;
          path = lookup.path;
        }
        // perhaps we need to create the node
        var created = false;
        if ((flags & 64)) {
          if (node) {
            // if O_CREAT and O_EXCL are set, error out if the node already exists
            if ((flags & 128)) {
              throw new FS.ErrnoError(20);
            }
          } else if (isDirPath) {
            throw new FS.ErrnoError(31);
          } else {
            // node doesn't exist, try to create it
            // Ignore the permission bits here to ensure we can `open` this new
            // file below. We use chmod below to apply the permissions once the
            // file is open.
            node = FS.mknod(path, mode | 0o777, 0);
            created = true;
          }
        }
        if (!node) {
          throw new FS.ErrnoError(44);
        }
        // can't truncate a device
        if (FS.isChrdev(node.mode)) {
          flags &= ~512;
        }
        // if asked only for a directory, then this must be one
        if ((flags & 65536) && !FS.isDir(node.mode)) {
          throw new FS.ErrnoError(54);
        }
        // check permissions, if this is not a file we just created now (it is ok to
        // create and write to a file with read-only permissions; it is read-only
        // for later use)
        if (!created) {
          var errCode = FS.mayOpen(node, flags);
          if (errCode) {
            throw new FS.ErrnoError(errCode);
          }
        }
        // do truncation if necessary
        if ((flags & 512) && !created) {
          FS.truncate(node, 0);
        }
        // we've already handled these, don't pass down to the underlying vfs
        flags &= ~(128 | 512 | 131072);
  
        // register the stream with the filesystem
        var stream = FS.createStream({
          node,
          path: FS.getPath(node),  // we want the absolute path to the node
          flags,
          seekable: true,
          position: 0,
          stream_ops: node.stream_ops,
          // used by the file family libc calls (fopen, fwrite, ferror, etc.)
          ungotten: [],
          error: false
        });
        // call the new stream's open function
        if (stream.stream_ops.open) {
          stream.stream_ops.open(stream);
        }
        if (created) {
          FS.chmod(node, mode & 0o777);
        }
        return stream;
      },
  close(stream) {
        if (FS.isClosed(stream)) {
          throw new FS.ErrnoError(8);
        }
        if (stream.getdents) stream.getdents = null; // free readdir state
        try {
          if (stream.stream_ops.close) {
            stream.stream_ops.close(stream);
          }
        } catch (e) {
          throw e;
        } finally {
          FS.closeStream(stream.fd);
        }
        stream.fd = null;
      },
  isClosed(stream) {
        return stream.fd === null;
      },
  llseek(stream, offset, whence) {
        if (FS.isClosed(stream)) {
          throw new FS.ErrnoError(8);
        }
        if (!stream.seekable || !stream.stream_ops.llseek) {
          throw new FS.ErrnoError(70);
        }
        if (whence != 0 && whence != 1 && whence != 2) {
          throw new FS.ErrnoError(28);
        }
        stream.position = stream.stream_ops.llseek(stream, offset, whence);
        stream.ungotten = [];
        return stream.position;
      },
  read(stream, buffer, offset, length, position) {
        assert(offset >= 0);
        if (length < 0 || position < 0) {
          throw new FS.ErrnoError(28);
        }
        if (FS.isClosed(stream)) {
          throw new FS.ErrnoError(8);
        }
        if ((stream.flags & 2097155) === 1) {
          throw new FS.ErrnoError(8);
        }
        if (FS.isDir(stream.node.mode)) {
          throw new FS.ErrnoError(31);
        }
        if (!stream.stream_ops.read) {
          throw new FS.ErrnoError(28);
        }
        var seeking = typeof position != 'undefined';
        if (!seeking) {
          position = stream.position;
        } else if (!stream.seekable) {
          throw new FS.ErrnoError(70);
        }
        var bytesRead = stream.stream_ops.read(stream, buffer, offset, length, position);
        if (!seeking) stream.position += bytesRead;
        return bytesRead;
      },
  write(stream, buffer, offset, length, position, canOwn) {
        assert(offset >= 0);
        assert(buffer.subarray, 'FS.write expects a TypedArray');
        if (length < 0 || position < 0) {
          throw new FS.ErrnoError(28);
        }
        if (FS.isClosed(stream)) {
          throw new FS.ErrnoError(8);
        }
        if ((stream.flags & 2097155) === 0) {
          throw new FS.ErrnoError(8);
        }
        if (FS.isDir(stream.node.mode)) {
          throw new FS.ErrnoError(31);
        }
        if (!stream.stream_ops.write) {
          throw new FS.ErrnoError(28);
        }
        if (stream.seekable && stream.flags & 1024) {
          // seek to the end before writing in append mode
          FS.llseek(stream, 0, 2);
        }
        var seeking = typeof position != 'undefined';
        if (!seeking) {
          position = stream.position;
        } else if (!stream.seekable) {
          throw new FS.ErrnoError(70);
        }
        var bytesWritten = stream.stream_ops.write(stream, buffer, offset, length, position, canOwn);
        if (!seeking) stream.position += bytesWritten;
        return bytesWritten;
      },
  mmap(stream, length, position, prot, flags) {
        // User requests writing to file (prot & PROT_WRITE != 0).
        // Checking if we have permissions to write to the file unless
        // MAP_PRIVATE flag is set. According to POSIX spec it is possible
        // to write to file opened in read-only mode with MAP_PRIVATE flag,
        // as all modifications will be visible only in the memory of
        // the current process.
        if ((prot & 2) !== 0
            && (flags & 2) === 0
            && (stream.flags & 2097155) !== 2) {
          throw new FS.ErrnoError(2);
        }
        if ((stream.flags & 2097155) === 1) {
          throw new FS.ErrnoError(2);
        }
        if (!stream.stream_ops.mmap) {
          throw new FS.ErrnoError(43);
        }
        if (!length) {
          throw new FS.ErrnoError(28);
        }
        return stream.stream_ops.mmap(stream, length, position, prot, flags);
      },
  msync(stream, buffer, offset, length, mmapFlags) {
        assert(offset >= 0);
        if (!stream.stream_ops.msync) {
          return 0;
        }
        return stream.stream_ops.msync(stream, buffer, offset, length, mmapFlags);
      },
  ioctl(stream, cmd, arg) {
        if (!stream.stream_ops.ioctl) {
          throw new FS.ErrnoError(59);
        }
        return stream.stream_ops.ioctl(stream, cmd, arg);
      },
  readFile(path, opts = {}) {
        opts.flags = opts.flags ?? 0;
        opts.encoding = opts.encoding ?? 'binary';
        if (opts.encoding !== 'utf8' && opts.encoding !== 'binary') {
          abort(`Invalid encoding type "${opts.encoding}"`);
        }
        var stream = FS.open(path, opts.flags);
        var stat = FS.stat(path);
        var length = stat.size;
        var buf = new Uint8Array(length);
        FS.read(stream, buf, 0, length, 0);
        if (opts.encoding === 'utf8') {
          buf = UTF8ArrayToString(buf);
        }
        FS.close(stream);
        return buf;
      },
  writeFile(path, data, opts = {}) {
        opts.flags = opts.flags ?? 577;
        var stream = FS.open(path, opts.flags, opts.mode);
        data = FS_fileDataToTypedArray(data);
        FS.write(stream, data, 0, data.byteLength, undefined, opts.canOwn);
        FS.close(stream);
      },
  cwd:() => FS.currentPath,
  chdir(path) {
        var lookup = FS.lookupPath(path, { follow: true });
        if (lookup.node === null) {
          throw new FS.ErrnoError(44);
        }
        if (!FS.isDir(lookup.node.mode)) {
          throw new FS.ErrnoError(54);
        }
        var errCode = FS.nodePermissions(lookup.node, 'x');
        if (errCode) {
          throw new FS.ErrnoError(errCode);
        }
        FS.currentPath = lookup.path;
      },
  createDefaultDirectories() {
        FS.mkdir('/tmp');
        FS.mkdir('/home');
        FS.mkdir('/home/web_user');
      },
  createDefaultDevices() {
        // create /dev
        FS.mkdir('/dev');
        // setup /dev/null
        FS.registerDevice(FS.makedev(1, 3), {
          read: () => 0,
          write: (stream, buffer, offset, length, pos) => length,
          llseek: () => 0,
        });
        FS.mkdev('/dev/null', FS.makedev(1, 3));
        // setup /dev/tty and /dev/tty1
        // stderr needs to print output using err() rather than out()
        // so we register a second tty just for it.
        TTY.register(FS.makedev(5, 0), TTY.default_tty_ops);
        TTY.register(FS.makedev(6, 0), TTY.default_tty1_ops);
        FS.mkdev('/dev/tty', FS.makedev(5, 0));
        FS.mkdev('/dev/tty1', FS.makedev(6, 0));
        // setup /dev/[u]random
        // use a buffer to avoid overhead of individual crypto calls per byte
        var randomBuffer = new Uint8Array(1024), randomLeft = 0;
        var randomByte = () => {
          if (randomLeft === 0) {
            randomFill(randomBuffer);
            randomLeft = randomBuffer.byteLength;
          }
          return randomBuffer[--randomLeft];
        };
        FS.createDevice('/dev', 'random', randomByte);
        FS.createDevice('/dev', 'urandom', randomByte);
        // we're not going to emulate the actual shm device,
        // just create the tmp dirs that reside in it commonly
        FS.mkdir('/dev/shm');
        FS.mkdir('/dev/shm/tmp');
      },
  createSpecialDirectories() {
        // create /proc/self/fd which allows /proc/self/fd/6 => readlink gives the
        // name of the stream for fd 6 (see test_unistd_ttyname)
        FS.mkdir('/proc');
        var proc_self = FS.mkdir('/proc/self');
        FS.mkdir('/proc/self/fd');
        FS.mount({
          mount() {
            var node = FS.createNode(proc_self, 'fd', 16895, 73);
            node.stream_ops = {
              llseek: MEMFS.stream_ops.llseek,
            };
            node.node_ops = {
              lookup(parent, name) {
                var fd = +name;
                var stream = FS.getStreamChecked(fd);
                var ret = {
                  parent: null,
                  mount: { mountpoint: 'fake' },
                  node_ops: { readlink: () => stream.path },
                  id: fd + 1,
                };
                ret.parent = ret; // make it look like a simple root node
                return ret;
              },
              readdir() {
                return Array.from(FS.streams.entries())
                  .filter(([k, v]) => v)
                  .map(([k, v]) => k.toString());
              }
            };
            return node;
          }
        }, {}, '/proc/self/fd');
      },
  createStandardStreams(input, output, error) {
        // TODO deprecate the old functionality of a single
        // input / output callback and that utilizes FS.createDevice
        // and instead require a unique set of stream ops
  
        // by default, we symlink the standard streams to the
        // default tty devices. however, if the standard streams
        // have been overwritten we create a unique device for
        // them instead.
        if (input) {
          FS.createDevice('/dev', 'stdin', input);
        } else {
          FS.symlink('/dev/tty', '/dev/stdin');
        }
        if (output) {
          FS.createDevice('/dev', 'stdout', null, output);
        } else {
          FS.symlink('/dev/tty', '/dev/stdout');
        }
        if (error) {
          FS.createDevice('/dev', 'stderr', null, error);
        } else {
          FS.symlink('/dev/tty1', '/dev/stderr');
        }
  
        // open default streams for the stdin, stdout and stderr devices
        var stdin = FS.open('/dev/stdin', 0);
        var stdout = FS.open('/dev/stdout', 1);
        var stderr = FS.open('/dev/stderr', 1);
        assert(stdin.fd === 0, `invalid handle for stdin (${stdin.fd})`);
        assert(stdout.fd === 1, `invalid handle for stdout (${stdout.fd})`);
        assert(stderr.fd === 2, `invalid handle for stderr (${stderr.fd})`);
      },
  staticInit() {
        FS.nameTable = new Array(4096);
  
        FS.mount(MEMFS, {}, '/');
  
        FS.createDefaultDirectories();
        FS.createDefaultDevices();
        FS.createSpecialDirectories();
  
        FS.filesystems = {
          'MEMFS': MEMFS,
          'IDBFS': IDBFS,
        };
      },
  init(input, output, error) {
        assert(!FS.initialized, 'FS.init was previously called. If you want to initialize later with custom parameters, remove any earlier calls (note that one is automatically added to the generated code)');
        FS.initialized = true;
  
        // Allow Module.stdin etc. to provide defaults, if none explicitly passed to us here
        input ??= Module['stdin'];
        output ??= Module['stdout'];
        error ??= Module['stderr'];
  
        FS.createStandardStreams(input, output, error);
      },
  quit() {
        FS.initialized = false;
        // force-flush all streams, so we get musl std streams printed out
        _fflush(0);
        // close all of our streams
        for (var stream of FS.streams) {
          if (stream) {
            FS.close(stream);
          }
        }
      },
  findObject(path, dontResolveLastLink) {
        var ret = FS.analyzePath(path, dontResolveLastLink);
        if (!ret.exists) {
          return null;
        }
        return ret.object;
      },
  analyzePath(path, dontResolveLastLink) {
        // operate from within the context of the symlink's target
        try {
          var lookup = FS.lookupPath(path, { follow: !dontResolveLastLink });
          path = lookup.path;
        } catch (e) {
        }
        var ret = {
          isRoot: false, exists: false, error: 0, name: null, path: null, object: null,
          parentExists: false, parentPath: null, parentObject: null
        };
        try {
          var lookup = FS.lookupPath(path, { parent: true });
          ret.parentExists = true;
          ret.parentPath = lookup.path;
          ret.parentObject = lookup.node;
          ret.name = PATH.basename(path);
          lookup = FS.lookupPath(path, { follow: !dontResolveLastLink });
          ret.exists = true;
          ret.path = lookup.path;
          ret.object = lookup.node;
          ret.name = lookup.node.name;
          ret.isRoot = lookup.path === '/';
        } catch (e) {
          ret.error = e.errno;
        };
        return ret;
      },
  createPath(parent, path, canRead, canWrite) {
        parent = typeof parent == 'string' ? parent : FS.getPath(parent);
        var parts = path.split('/').reverse();
        while (parts.length) {
          var part = parts.pop();
          if (!part) continue;
          var current = PATH.join2(parent, part);
          try {
            FS.mkdir(current);
          } catch (e) {
            if (e.errno != 20) throw e;
          }
          parent = current;
        }
        return current;
      },
  createFile(parent, name, properties, canRead, canWrite) {
        var path = PATH.join2(typeof parent == 'string' ? parent : FS.getPath(parent), name);
        var mode = FS_getMode(canRead, canWrite);
        return FS.create(path, mode);
      },
  createDataFile(parent, name, data, canRead, canWrite, canOwn) {
        var path = name;
        if (parent) {
          parent = typeof parent == 'string' ? parent : FS.getPath(parent);
          path = name ? PATH.join2(parent, name) : parent;
        }
        var mode = FS_getMode(canRead, canWrite);
        var node = FS.create(path, mode);
        if (data) {
          data = FS_fileDataToTypedArray(data);
          // make sure we can write to the file
          FS.chmod(node, mode | 146);
          var stream = FS.open(node, 577);
          FS.write(stream, data, 0, data.length, 0, canOwn);
          FS.close(stream);
          FS.chmod(node, mode);
        }
      },
  createDevice(parent, name, input, output) {
        var path = PATH.join2(typeof parent == 'string' ? parent : FS.getPath(parent), name);
        var mode = FS_getMode(!!input, !!output);
        FS.createDevice.major ??= 64;
        var dev = FS.makedev(FS.createDevice.major++, 0);
        // Create a fake device that a set of stream ops to emulate
        // the old behavior.
        FS.registerDevice(dev, {
          open(stream) {
            stream.seekable = false;
          },
          close(stream) {
            // flush any pending line data
            if (output?.buffer?.length) {
              output(10);
            }
          },
          read(stream, buffer, offset, length, pos /* ignored */) {
            var bytesRead = 0;
            for (var i = 0; i < length; i++) {
              var result;
              try {
                result = input();
              } catch (e) {
                throw new FS.ErrnoError(29);
              }
              if (result === undefined && bytesRead === 0) {
                throw new FS.ErrnoError(6);
              }
              if (result === null || result === undefined) break;
              bytesRead++;
              buffer[offset+i] = result;
            }
            if (bytesRead) {
              stream.node.atime = Date.now();
            }
            return bytesRead;
          },
          write(stream, buffer, offset, length, pos) {
            for (var i = 0; i < length; i++) {
              try {
                output(buffer[offset+i]);
              } catch (e) {
                throw new FS.ErrnoError(29);
              }
            }
            if (length) {
              stream.node.mtime = stream.node.ctime = Date.now();
            }
            return i;
          }
        });
        return FS.mkdev(path, mode, dev);
      },
  forceLoadFile(obj) {
        if (obj.isDevice || obj.isFolder || obj.link || obj.contents) return true;
        if (globalThis.XMLHttpRequest) {
          abort("Lazy loading should have been performed (contents set) in createLazyFile, but it was not. Lazy loading only works in web workers. Use --embed-file or --preload-file in emcc on the main thread.");
        } else { // Command-line.
          try {
            obj.contents = readBinary(obj.url);
          } catch (e) {
            throw new FS.ErrnoError(29);
          }
        }
      },
  createLazyFile(parent, name, url, canRead, canWrite) {
        // Lazy chunked Uint8Array (implements get and length from Uint8Array).
        // Actual getting is abstracted away for eventual reuse.
        class LazyUint8Array {
          lengthKnown = false;
          chunks = []; // Loaded chunks. Index is the chunk number
          get(idx) {
            if (idx > this.length-1 || idx < 0) {
              return undefined;
            }
            var chunkOffset = idx % this.chunkSize;
            var chunkNum = (idx / this.chunkSize)|0;
            return this.getter(chunkNum)[chunkOffset];
          }
          setDataGetter(getter) {
            this.getter = getter;
          }
          cacheLength() {
            // Find length
            var xhr = new XMLHttpRequest();
            xhr.open('HEAD', url, false);
            xhr.send(null);
            if (!(xhr.status >= 200 && xhr.status < 300 || xhr.status === 304)) abort("Couldn't load " + url + ". Status: " + xhr.status);
            var datalength = Number(xhr.getResponseHeader("Content-length"));
            var header;
            var hasByteServing = (header = xhr.getResponseHeader("Accept-Ranges")) && header === "bytes";
            var usesGzip = (header = xhr.getResponseHeader("Content-Encoding")) && header === "gzip";
  
            var chunkSize = 1024*1024; // Chunk size in bytes
  
            if (!hasByteServing) chunkSize = datalength;
  
            // Function to get a range from the remote URL.
            var doXHR = (from, to) => {
              if (from > to) abort(`invalid range (${from}, ${to}) or no bytes requested!`);
              if (to > datalength-1) abort(`only ${datalength} bytes available! programmer error!`);
  
              // TODO: Use mozResponseArrayBuffer, responseStream, etc. if available.
              var xhr = new XMLHttpRequest();
              xhr.open('GET', url, false);
              if (datalength !== chunkSize) xhr.setRequestHeader("Range", "bytes=" + from + "-" + to);
  
              // Some hints to the browser that we want binary data.
              xhr.responseType = 'arraybuffer';
              if (xhr.overrideMimeType) {
                xhr.overrideMimeType('text/plain; charset=x-user-defined');
              }
  
              xhr.send(null);
              if (!(xhr.status >= 200 && xhr.status < 300 || xhr.status === 304)) abort("Couldn't load " + url + ". Status: " + xhr.status);
              if (xhr.response !== undefined) {
                return new Uint8Array(/** @type{Array<number>} */(xhr.response || []));
              }
              return intArrayFromString(xhr.responseText ?? '', true);
            };
            var lazyArray = this;
            lazyArray.setDataGetter((chunkNum) => {
              var start = chunkNum * chunkSize;
              var end = (chunkNum+1) * chunkSize - 1; // including this byte
              end = Math.min(end, datalength-1); // if datalength-1 is selected, this is the last block
              if (typeof lazyArray.chunks[chunkNum] == 'undefined') {
                lazyArray.chunks[chunkNum] = doXHR(start, end);
              }
              if (typeof lazyArray.chunks[chunkNum] == 'undefined') abort('doXHR failed!');
              return lazyArray.chunks[chunkNum];
            });
  
            if (usesGzip || !datalength) {
              // if the server uses gzip or doesn't supply the length, we have to download the whole file to get the (uncompressed) length
              chunkSize = datalength = 1; // this will force getter(0)/doXHR do download the whole file
              datalength = this.getter(0).length;
              chunkSize = datalength;
              out("LazyFiles on gzip forces download of the whole file when length is accessed");
            }
  
            this._length = datalength;
            this._chunkSize = chunkSize;
            this.lengthKnown = true;
          }
          get length() {
            if (!this.lengthKnown) {
              this.cacheLength();
            }
            return this._length;
          }
          get chunkSize() {
            if (!this.lengthKnown) {
              this.cacheLength();
            }
            return this._chunkSize;
          }
        }
  
        if (globalThis.XMLHttpRequest) {
          if (!ENVIRONMENT_IS_WORKER) abort('Cannot do synchronous binary XHRs outside webworkers in modern browsers. Use --embed-file or --preload-file in emcc');
          var lazyArray = new LazyUint8Array();
          var properties = { isDevice: false, contents: lazyArray };
        } else {
          var properties = { isDevice: false, url: url };
        }
  
        var node = FS.createFile(parent, name, properties, canRead, canWrite);
        // This is a total hack, but I want to get this lazy file code out of the
        // core of MEMFS. If we want to keep this lazy file concept I feel it should
        // be its own thin LAZYFS proxying calls to MEMFS.
        if (properties.contents) {
          node.contents = properties.contents;
        } else if (properties.url) {
          node.contents = null;
          node.url = properties.url;
        }
        // Add a function that defers querying the file size until it is asked the first time.
        Object.defineProperties(node, {
          usedBytes: {
            get: function() { return this.contents.length; }
          }
        });
        // override each stream op with one that tries to force load the lazy file first
        var stream_ops = {};
        for (const [key, fn] of Object.entries(node.stream_ops)) {
          stream_ops[key] = (...args) => {
            FS.forceLoadFile(node);
            return fn(...args);
          };
        }
        function writeChunks(stream, buffer, offset, length, position) {
          var contents = stream.node.contents;
          if (position >= contents.length)
            return 0;
          var size = Math.min(contents.length - position, length);
          assert(size >= 0);
          if (contents.slice) { // normal array
            for (var i = 0; i < size; i++) {
              buffer[offset + i] = contents[position + i];
            }
          } else {
            for (var i = 0; i < size; i++) { // LazyUint8Array from sync binary XHR
              buffer[offset + i] = contents.get(position + i);
            }
          }
          return size;
        }
        // use a custom read function
        stream_ops.read = (stream, buffer, offset, length, position) => {
          FS.forceLoadFile(node);
          return writeChunks(stream, buffer, offset, length, position)
        };
        // use a custom mmap function
        stream_ops.mmap = (stream, length, position, prot, flags) => {
          FS.forceLoadFile(node);
          var ptr = mmapAlloc(length);
          if (!ptr) {
            throw new FS.ErrnoError(48);
          }
          writeChunks(stream, HEAP8, ptr, length, position);
          return { ptr, allocated: true };
        };
        node.stream_ops = stream_ops;
        return node;
      },
  };
  var SOCKFS = {
  websocketArgs:{
  },
  callbacks:{
  },
  on(event, callback) {
        SOCKFS.callbacks[event] = callback;
      },
  emit(event, param) {
        SOCKFS.callbacks[event]?.(param);
      },
  mount(mount) {
        // The incoming Module['websocket'] can be used for configuring 
        // subprotocol/url, etc
        SOCKFS.websocketArgs = Module['websocket'] || {};
        // Add the Event registration mechanism to the exported websocket configuration
        // object so we can register network callbacks from native JavaScript too.
        // For more documentation see system/include/emscripten/emscripten.h
        (Module['websocket'] ??= {})['on'] = SOCKFS.on;
  
        return FS.createNode(null, '/', 16895, 0);
      },
  createSocket(family, type, protocol) {
        if (family != 2
           ) {
          throw new FS.ErrnoError(5);
        }
        type &= ~526336; // Some applications may pass it; it makes no sense for a single process.
        // Emscripten only supports SOCK_STREAM and SOCK_DGRAM
        if (type != 1 && type != 2) {
          throw new FS.ErrnoError(28);
        }
        var streaming = type == 1;
        if (streaming && protocol && protocol != 6) {
          throw new FS.ErrnoError(66); // if SOCK_STREAM, must be tcp or 0.
        }
  
        // create our internal socket structure
        var sock = {
          family,
          type,
          protocol,
          server: null,
          error: null, // Used in getsockopt for SOL_SOCKET/SO_ERROR test
          peers: {},
          pending: [],
          recv_queue: [],
          sock_ops: SOCKFS.websocket_sock_ops
        };
  
        // create the filesystem node to store the socket structure
        var name = SOCKFS.nextname();
        var node = FS.createNode(SOCKFS.root, name, 49152, 0);
        node.sock = sock;
  
        // and the wrapping stream that enables library functions such
        // as read and write to indirectly interact with the socket
        var stream = FS.createStream({
          path: name,
          node,
          flags: 2,
          seekable: false,
          stream_ops: SOCKFS.stream_ops
        });
  
        // map the new stream to the socket structure (sockets have a 1:1
        // relationship with a stream)
        sock.stream = stream;
  
        return sock;
      },
  getSocket(fd) {
        var stream = FS.getStream(fd);
        if (!stream || !FS.isSocket(stream.node.mode)) {
          return null;
        }
        return stream.node.sock;
      },
  stream_ops:{
  poll(stream) {
          var sock = stream.node.sock;
          return sock.sock_ops.poll(sock);
        },
  ioctl(stream, request, varargs) {
          var sock = stream.node.sock;
          return sock.sock_ops.ioctl(sock, request, varargs);
        },
  read(stream, buffer, offset, length, position /* ignored */) {
          var sock = stream.node.sock;
          var msg = sock.sock_ops.recvmsg(sock, length);
          if (!msg) {
            // socket is closed
            return 0;
          }
          buffer.set(msg.buffer, offset);
          return msg.buffer.length;
        },
  write(stream, buffer, offset, length, position /* ignored */) {
          var sock = stream.node.sock;
          return sock.sock_ops.sendmsg(sock, buffer, offset, length);
        },
  close(stream) {
          var sock = stream.node.sock;
          sock.sock_ops.close(sock);
        },
  },
  nextname() {
        if (!SOCKFS.nextname.current) {
          SOCKFS.nextname.current = 0;
        }
        return `socket[${SOCKFS.nextname.current++}]`;
      },
  websocket_sock_ops:{
  createPeer(sock, addr, port) {
          var ws;
  
          if (typeof addr == 'object') {
            ws = addr;
            addr = null;
            port = null;
          }
  
          if (ws) {
            // for sockets that've already connected (e.g. we're the server)
            // we can inspect the _socket property for the address
            if (ws._socket) {
              addr = ws._socket.remoteAddress;
              port = ws._socket.remotePort;
            }
            // if we're just now initializing a connection to the remote,
            // inspect the url property
            else {
              var result = /ws[s]?:\/\/([^:]+):(\d+)/.exec(ws.url);
              if (!result) {
                throw new Error('WebSocket URL must be in the format ws(s)://address:port');
              }
              addr = result[1];
              port = parseInt(result[2], 10);
            }
          } else {
            // create the actual websocket object and connect
            try {
              // The default value is 'ws://' the replace is needed because the compiler replaces '//' comments with '#'
              // comments without checking context, so we'd end up with ws:#, the replace swaps the '#' for '//' again.
              var url = 'ws://'.replace('#', '//');
              // Make the WebSocket subprotocol (Sec-WebSocket-Protocol) default to binary if no configuration is set.
              var subProtocols = 'binary'; // The default value is 'binary'
              // The default WebSocket options
              var opts = undefined;
  
              // Fetch runtime WebSocket URL config.
              if (SOCKFS.websocketArgs['url']) {
                url = SOCKFS.websocketArgs['url'];
              }
              // Fetch runtime WebSocket subprotocol config.
              if (SOCKFS.websocketArgs['subprotocol']) {
                subProtocols = SOCKFS.websocketArgs['subprotocol'];
              } else if (SOCKFS.websocketArgs['subprotocol'] === null) {
                subProtocols = 'null'
              }
  
              if (url === 'ws://' || url === 'wss://') { // Is the supplied URL config just a prefix, if so complete it.
                var parts = addr.split('/');
                url = url + parts[0] + ":" + port + "/" + parts.slice(1).join('/');
              }
  
              if (subProtocols !== 'null') {
                // The regex trims the string (removes spaces at the beginning and end), then splits the string by
                // <any space>,<any space> into an Array. Whitespace removal is important for Websockify and ws.
                subProtocols = subProtocols.replace(/^ +| +$/g,"").split(/ *, */);
  
                opts = subProtocols;
              }
  
              // If node we use the ws library.
              var WebSocketConstructor;
              if (ENVIRONMENT_IS_NODE) {
                WebSocketConstructor = /** @type{(typeof WebSocket)} */(require('ws'));
              } else
              {
                WebSocketConstructor = WebSocket;
              }
              ws = new WebSocketConstructor(url, opts);
              ws.binaryType = 'arraybuffer';
            } catch (e) {
              throw new FS.ErrnoError(23);
            }
          }
  
          var peer = {
            addr,
            port,
            socket: ws,
            msg_send_queue: []
          };
  
          SOCKFS.websocket_sock_ops.addPeer(sock, peer);
          SOCKFS.websocket_sock_ops.handlePeerEvents(sock, peer);
  
          // if this is a bound dgram socket, send the port number first to allow
          // us to override the ephemeral port reported to us by remotePort on the
          // remote end.
          if (sock.type === 2 && typeof sock.sport != 'undefined') {
            peer.msg_send_queue.push(new Uint8Array([
                255, 255, 255, 255,
                'p'.charCodeAt(0), 'o'.charCodeAt(0), 'r'.charCodeAt(0), 't'.charCodeAt(0),
                ((sock.sport & 0xff00) >> 8) , (sock.sport & 0xff)
            ]));
          }
  
          return peer;
        },
  getPeer(sock, addr, port) {
          return sock.peers[addr + ':' + port];
        },
  addPeer(sock, peer) {
          sock.peers[peer.addr + ':' + peer.port] = peer;
        },
  removePeer(sock, peer) {
          delete sock.peers[peer.addr + ':' + peer.port];
        },
  handlePeerEvents(sock, peer) {
          var first = true;
  
          function handleOpen() {
  
            sock.connecting = false;
            SOCKFS.emit('open', sock.stream.fd);
  
            try {
              var queued = peer.msg_send_queue.shift();
              while (queued) {
                peer.socket.send(queued);
                queued = peer.msg_send_queue.shift();
              }
            } catch (e) {
              // not much we can do here in the way of proper error handling as we've already
              // lied and said this data was sent. shut it down.
              peer.socket.close();
            }
          }
  
          function handleMessage(data) {
            if (typeof data == 'string') {
              var encoder = new TextEncoder(); // should be utf-8
              data = encoder.encode(data); // make a typed array from the string
            } else {
              assert(data.byteLength !== undefined); // must receive an ArrayBuffer
              if (data.byteLength == 0) {
                // An empty ArrayBuffer will emit a pseudo disconnect event
                // as recv/recvmsg will return zero which indicates that a socket
                // has performed a shutdown although the connection has not been disconnected yet.
                return;
              }
              data = new Uint8Array(data); // make a typed array view on the array buffer
            }
  
            // if this is the port message, override the peer's port with it
            var wasfirst = first;
            first = false;
            if (wasfirst &&
                data.length === 10 &&
                data[0] === 255 && data[1] === 255 && data[2] === 255 && data[3] === 255 &&
                data[4] === 'p'.charCodeAt(0) && data[5] === 'o'.charCodeAt(0) && data[6] === 'r'.charCodeAt(0) && data[7] === 't'.charCodeAt(0)) {
              // update the peer's port and its key in the peer map
              var newport = ((data[8] << 8) | data[9]);
              SOCKFS.websocket_sock_ops.removePeer(sock, peer);
              peer.port = newport;
              SOCKFS.websocket_sock_ops.addPeer(sock, peer);
              return;
            }
  
            sock.recv_queue.push({ addr: peer.addr, port: peer.port, data: data });
            SOCKFS.emit('message', sock.stream.fd);
          }
  
          if (ENVIRONMENT_IS_NODE) {
             // EventEmitter-style events use by ws library objects in Node.js).
            peer.socket.on('open', handleOpen);
            peer.socket.on('message', (data, isBinary) => {
              if (!isBinary) {
                return;
              }
              handleMessage((new Uint8Array(data)).buffer); // copy from node Buffer -> ArrayBuffer
            });
            peer.socket.on('close', () => SOCKFS.emit('close', sock.stream.fd));
            peer.socket.on('error', (error) =>{
              // Although the ws library may pass errors that may be more descriptive than
              // ECONNREFUSED they are not necessarily the expected error code e.g.
              // ENOTFOUND on getaddrinfo seems to be node.js specific, so using ECONNREFUSED
              // is still probably the most useful thing to do.
              sock.error = 14; // Used in getsockopt for SOL_SOCKET/SO_ERROR test.
              SOCKFS.emit('error', [sock.stream.fd, sock.error, 'ECONNREFUSED: Connection refused']);
            });
            return;
          }
          peer.socket.onopen = handleOpen;
          peer.socket.onclose = () => SOCKFS.emit('close', sock.stream.fd);
          peer.socket.onmessage = (event) => handleMessage(event.data);
          peer.socket.onerror = (error) => {
            // The WebSocket spec only allows a 'simple event' to be thrown on error,
            // so we only really know as much as ECONNREFUSED.
            sock.error = 14; // Used in getsockopt for SOL_SOCKET/SO_ERROR test.
            SOCKFS.emit('error', [sock.stream.fd, sock.error, 'ECONNREFUSED: Connection refused']);
          };
        },
  poll(sock) {
          if (sock.type === 1 && sock.server) {
            // listen sockets should only say they're available for reading
            // if there are pending clients.
            return sock.pending.length ? (64 | 1) : 0;
          }
  
          var mask = 0;
          var dest = sock.type === 1 ?  // we only care about the socket state for connection-based sockets
            SOCKFS.websocket_sock_ops.getPeer(sock, sock.daddr, sock.dport) :
            null;
  
          if (sock.recv_queue.length ||
              !dest ||  // connection-less sockets are always ready to read
              (dest && dest.socket.readyState === dest.socket.CLOSING) ||
              (dest && dest.socket.readyState === dest.socket.CLOSED)) {  // let recv return 0 once closed
            mask |= (64 | 1);
          }
  
          if (!dest ||  // connection-less sockets are always ready to write
              (dest && dest.socket.readyState === dest.socket.OPEN)) {
            mask |= 4;
          }
  
          if ((dest && dest.socket.readyState === dest.socket.CLOSING) ||
              (dest && dest.socket.readyState === dest.socket.CLOSED)) {
            // When an non-blocking connect fails mark the socket as writable.
            // Its up to the calling code to then use getsockopt with SO_ERROR to
            // retrieve the error.
            // See https://man7.org/linux/man-pages/man2/connect.2.html
            if (sock.connecting) {
              mask |= 4;
            } else  {
              mask |= 16;
            }
          }
  
          return mask;
        },
  ioctl(sock, request, arg) {
          switch (request) {
            case 21531:
              var bytes = 0;
              if (sock.recv_queue.length) {
                bytes = sock.recv_queue[0].data.length;
              }
              HEAP32[((arg)>>2)] = bytes;
              return 0;
            case 21537:
              var on = HEAP32[((arg)>>2)];
              if (on) {
                sock.stream.flags |= 2048;
              } else {
                sock.stream.flags &= ~2048;
              }
              return 0;
            default:
              return 28;
          }
        },
  close(sock) {
          // if we've spawned a listen server, close it
          if (sock.server) {
            try {
              sock.server.close();
            } catch (e) {
            }
            sock.server = null;
          }
          // close any peer connections
          for (var peer of Object.values(sock.peers)) {
            try {
              peer.socket.close();
            } catch (e) {
            }
            SOCKFS.websocket_sock_ops.removePeer(sock, peer);
          }
          return 0;
        },
  bind(sock, addr, port) {
          if (typeof sock.saddr != 'undefined' || typeof sock.sport != 'undefined') {
            throw new FS.ErrnoError(28);  // already bound
          }
          sock.saddr = addr;
          sock.sport = port;
          // in order to emulate dgram sockets, we need to launch a listen server when
          // binding on a connection-less socket
          // note: this is only required on the server side
          if (sock.type === 2) {
            // close the existing server if it exists
            if (sock.server) {
              sock.server.close();
              sock.server = null;
            }
            // swallow error operation not supported error that occurs when binding in the
            // browser where this isn't supported
            try {
              sock.sock_ops.listen(sock, 0);
            } catch (e) {
              if (!(e.name === 'ErrnoError')) throw e;
              if (e.errno !== 138) throw e;
            }
          }
        },
  connect(sock, addr, port) {
          if (sock.server) {
            throw new FS.ErrnoError(138);
          }
  
          // TODO autobind
          // if (!sock.addr && sock.type == 2) {
          // }
  
          // early out if we're already connected / in the middle of connecting
          if (typeof sock.daddr != 'undefined' && typeof sock.dport != 'undefined') {
            var dest = SOCKFS.websocket_sock_ops.getPeer(sock, sock.daddr, sock.dport);
            if (dest) {
              if (dest.socket.readyState === dest.socket.CONNECTING) {
                throw new FS.ErrnoError(7);
              } else {
                throw new FS.ErrnoError(30);
              }
            }
          }
  
          // add the socket to our peer list and set our
          // destination address / port to match
          var peer = SOCKFS.websocket_sock_ops.createPeer(sock, addr, port);
          sock.daddr = peer.addr;
          sock.dport = peer.port;
  
          // because we cannot synchronously block to wait for the WebSocket
          // connection to complete, we return here pretending that the connection
          // was a success.
          sock.connecting = true;
        },
  listen(sock, backlog) {
          if (!ENVIRONMENT_IS_NODE) {
            throw new FS.ErrnoError(138);
          }
          if (sock.server) {
             throw new FS.ErrnoError(28);  // already listening
          }
          var WebSocketServer = require('ws').Server;
          var host = sock.saddr;
          sock.server = new WebSocketServer({
            host,
            port: sock.sport
            // TODO support backlog
          });
          SOCKFS.emit('listen', sock.stream.fd); // Send Event with listen fd.
  
          sock.server.on('connection', (ws) => {
            if (sock.type === 1) {
              var newsock = SOCKFS.createSocket(sock.family, sock.type, sock.protocol);
  
              // create a peer on the new socket
              var peer = SOCKFS.websocket_sock_ops.createPeer(newsock, ws);
              newsock.daddr = peer.addr;
              newsock.dport = peer.port;
  
              // push to queue for accept to pick up
              sock.pending.push(newsock);
              SOCKFS.emit('connection', newsock.stream.fd);
            } else {
              // create a peer on the listen socket so calling sendto
              // with the listen socket and an address will resolve
              // to the correct client
              SOCKFS.websocket_sock_ops.createPeer(sock, ws);
              SOCKFS.emit('connection', sock.stream.fd);
            }
          });
          sock.server.on('close', () => {
            SOCKFS.emit('close', sock.stream.fd);
            sock.server = null;
          });
          sock.server.on('error', (error) => {
            // Although the ws library may pass errors that may be more descriptive than
            // ECONNREFUSED they are not necessarily the expected error code e.g.
            // ENOTFOUND on getaddrinfo seems to be node.js specific, so using EHOSTUNREACH
            // is still probably the most useful thing to do. This error shouldn't
            // occur in a well written app as errors should get trapped in the compiled
            // app's own getaddrinfo call.
            sock.error = 23; // Used in getsockopt for SOL_SOCKET/SO_ERROR test.
            SOCKFS.emit('error', [sock.stream.fd, sock.error, 'EHOSTUNREACH: Host is unreachable']);
            // don't throw
          });
        },
  accept(listensock) {
          if (!listensock.server || !listensock.pending.length) {
            throw new FS.ErrnoError(28);
          }
          var newsock = listensock.pending.shift();
          newsock.stream.flags = listensock.stream.flags;
          return newsock;
        },
  getname(sock, peer) {
          var addr, port;
          if (peer) {
            if (sock.daddr === undefined || sock.dport === undefined) {
              throw new FS.ErrnoError(53);
            }
            addr = sock.daddr;
            port = sock.dport;
          } else {
            // TODO saddr and sport will be set for bind()'d UDP sockets, but what
            // should we be returning for TCP sockets that've been connect()'d?
            addr = sock.saddr || 0;
            port = sock.sport || 0;
          }
          return { addr, port };
        },
  sendmsg(sock, buffer, offset, length, addr, port) {
          if (sock.type === 2) {
            // connection-less sockets will honor the message address,
            // and otherwise fall back to the bound destination address
            if (addr === undefined || port === undefined) {
              addr = sock.daddr;
              port = sock.dport;
            }
            // if there was no address to fall back to, error out
            if (addr === undefined || port === undefined) {
              throw new FS.ErrnoError(17);
            }
          } else {
            // connection-based sockets will only use the bound
            addr = sock.daddr;
            port = sock.dport;
          }
  
          // find the peer for the destination address
          var dest = SOCKFS.websocket_sock_ops.getPeer(sock, addr, port);
  
          // early out if not connected with a connection-based socket
          if (sock.type === 1) {
            if (!dest || dest.socket.readyState === dest.socket.CLOSING || dest.socket.readyState === dest.socket.CLOSED) {
              throw new FS.ErrnoError(53);
            }
          }
  
          // create a copy of the incoming data to send, as the WebSocket API
          // doesn't work entirely with an ArrayBufferView, it'll just send
          // the entire underlying buffer
          if (ArrayBuffer.isView(buffer)) {
            offset += buffer.byteOffset;
            buffer = buffer.buffer;
          }
  
          var data = buffer.slice(offset, offset + length);
          // WebSockets .send() does not allow passing a SharedArrayBuffer, so
          // clone the SharedArrayBuffer as regular ArrayBuffer before
          // sending.
          if (data instanceof SharedArrayBuffer) {
            data = new Uint8Array(new Uint8Array(data)).buffer;
          }
  
          // if we don't have a cached connectionless UDP datagram connection, or
          // the TCP socket is still connecting, queue the message to be sent upon
          // connect, and lie, saying the data was sent now.
          if (!dest || dest.socket.readyState !== dest.socket.OPEN) {
            // if we're not connected, open a new connection
            if (sock.type === 2) {
              if (!dest || dest.socket.readyState === dest.socket.CLOSING || dest.socket.readyState === dest.socket.CLOSED) {
                dest = SOCKFS.websocket_sock_ops.createPeer(sock, addr, port);
              }
            }
            dest.msg_send_queue.push(data);
            return length;
          }
  
          try {
            // send the actual data
            dest.socket.send(data);
            return length;
          } catch (e) {
            throw new FS.ErrnoError(28);
          }
        },
  recvmsg(sock, length) {
          // http://pubs.opengroup.org/onlinepubs/7908799/xns/recvmsg.html
          if (sock.type === 1 && sock.server) {
            // tcp servers should not be recv()'ing on the listen socket
            throw new FS.ErrnoError(53);
          }
  
          var queued = sock.recv_queue.shift();
          if (!queued) {
            if (sock.type === 1) {
              var dest = SOCKFS.websocket_sock_ops.getPeer(sock, sock.daddr, sock.dport);
  
              if (!dest) {
                // if we have a destination address but are not connected, error out
                throw new FS.ErrnoError(53);
              }
              if (dest.socket.readyState === dest.socket.CLOSING || dest.socket.readyState === dest.socket.CLOSED) {
                // return null if the socket has closed
                return null;
              }
              // else, our socket is in a valid state but truly has nothing available
              throw new FS.ErrnoError(6);
            }
            throw new FS.ErrnoError(6);
          }
  
          // queued.data will be an ArrayBuffer if it's unadulterated, but if it's
          // requeued TCP data it'll be an ArrayBufferView
          var queuedLength = queued.data.byteLength || queued.data.length;
          var queuedOffset = queued.data.byteOffset || 0;
          var queuedBuffer = queued.data.buffer || queued.data;
          var bytesRead = Math.min(length, queuedLength);
          var res = {
            buffer: new Uint8Array(queuedBuffer, queuedOffset, bytesRead),
            addr: queued.addr,
            port: queued.port
          };
  
          // push back any unread data for TCP connections
          if (sock.type === 1 && bytesRead < queuedLength) {
            var bytesRemaining = queuedLength - bytesRead;
            queued.data = new Uint8Array(queuedBuffer, queuedOffset + bytesRead, bytesRemaining);
            sock.recv_queue.unshift(queued);
          }
  
          return res;
        },
  },
  };
  
  var getSocketFromFD = (fd) => {
      var socket = SOCKFS.getSocket(fd);
      if (!socket) throw new FS.ErrnoError(8);
      return socket;
    };
  
  var inetPton4 = (str) => {
      var b = str.split('.');
      for (var i = 0; i < 4; i++) {
        var tmp = Number(b[i]);
        if (isNaN(tmp)) return null;
        b[i] = tmp;
      }
      return (b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24)) >>> 0;
    };
  
  var inetPton6 = (str) => {
      var words;
      var w, offset, z, i;
      /* http://home.deds.nl/~aeron/regex/ */
      var valid6regx = /^((?=.*::)(?!.*::.+::)(::)?([\dA-F]{1,4}:(:|\b)|){5}|([\dA-F]{1,4}:){6})((([\dA-F]{1,4}((?!\3)::|:\b|$))|(?!\2\3)){2}|(((2[0-4]|1\d|[1-9])?\d|25[0-5])\.?\b){4})$/i
      var parts = [];
      if (!valid6regx.test(str)) {
        return null;
      }
      if (str === "::") {
        return [0, 0, 0, 0, 0, 0, 0, 0];
      }
      // Z placeholder to keep track of zeros when splitting the string on ":"
      if (str.startsWith("::")) {
        str = str.replace("::", "Z:"); // leading zeros case
      } else {
        str = str.replace("::", ":Z:");
      }
  
      if (str.indexOf(".") > 0) {
        // parse IPv4 embedded address
        str = str.replace(new RegExp('[.]', 'g'), ":");
        words = str.split(":");
        words[words.length-4] = Number(words[words.length-4]) + Number(words[words.length-3])*256;
        words[words.length-3] = Number(words[words.length-2]) + Number(words[words.length-1])*256;
        words = words.slice(0, words.length-2);
      } else {
        words = str.split(":");
      }
  
      offset = 0; z = 0;
      for (w=0; w < words.length; w++) {
        if (typeof words[w] == 'string') {
          if (words[w] === 'Z') {
            // compressed zeros - write appropriate number of zero words
            for (z = 0; z < (8 - words.length+1); z++) {
              parts[w+z] = 0;
            }
            offset = z-1;
          } else {
            // parse hex field to 16-bit value and write it in network byte-order
            parts[w+offset] = _htons(parseInt(words[w],16));
          }
        } else {
          // parsed IPv4 words
          parts[w+offset] = words[w];
        }
      }
      return [
        (parts[1] << 16) | parts[0],
        (parts[3] << 16) | parts[2],
        (parts[5] << 16) | parts[4],
        (parts[7] << 16) | parts[6]
      ];
    };
  
  var zeroMemory = (ptr, size) => HEAPU8.fill(0, ptr, ptr + size);
  
  /** @param {number=} addrlen */
  var writeSockaddr = (sa, family, addr, port, addrlen) => {
      switch (family) {
        case 2:
          addr = inetPton4(addr);
          zeroMemory(sa, 16);
          if (addrlen) {
            HEAP32[((addrlen)>>2)] = 16;
          }
          HEAP16[((sa)>>1)] = family;
          HEAP32[(((sa)+(4))>>2)] = addr;
          HEAP16[(((sa)+(2))>>1)] = _htons(port);
          break;
        case 10:
          addr = inetPton6(addr);
          zeroMemory(sa, 28);
          if (addrlen) {
            HEAP32[((addrlen)>>2)] = 28;
          }
          HEAP32[((sa)>>2)] = family;
          HEAP32[(((sa)+(8))>>2)] = addr[0];
          HEAP32[(((sa)+(12))>>2)] = addr[1];
          HEAP32[(((sa)+(16))>>2)] = addr[2];
          HEAP32[(((sa)+(20))>>2)] = addr[3];
          HEAP16[(((sa)+(2))>>1)] = _htons(port);
          break;
        default:
          return 5;
      }
      return 0;
    };
  
  
  var DNS = {
  address_map:{
  id:1,
  addrs:{
  },
  names:{
  },
  },
  lookup_name(name) {
        // If the name is already a valid ipv4 / ipv6 address, don't generate a fake one.
        var res = inetPton4(name);
        if (res !== null) {
          return name;
        }
        res = inetPton6(name);
        if (res !== null) {
          return name;
        }
  
        // See if this name is already mapped.
        var addr;
  
        if (DNS.address_map.addrs[name]) {
          addr = DNS.address_map.addrs[name];
        } else {
          var id = DNS.address_map.id++;
          assert(id < 65535, 'exceeded max address mappings of 65535');
  
          addr = '172.29.' + (id & 0xff) + '.' + (id & 0xff00);
  
          DNS.address_map.names[addr] = name;
          DNS.address_map.addrs[name] = addr;
        }
  
        return addr;
      },
  lookup_addr(addr) {
        if (DNS.address_map.names[addr]) {
          return DNS.address_map.names[addr];
        }
  
        return null;
      },
  };
  
  
  function ___syscall_accept4(fd, addr, len, flags, u1, u2) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(3, 0, 1, fd, addr, len, flags, u1, u2);
  
  try {
  
      var sock = getSocketFromFD(fd);
      var newsock = sock.sock_ops.accept(sock);
      if (addr) {
        var errno = writeSockaddr(addr, newsock.family, DNS.lookup_name(newsock.daddr), newsock.dport, len);
        assert(!errno);
      }
      return newsock.stream.fd;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  var inetNtop4 = (addr) =>
      (addr & 0xff) + '.' + ((addr >> 8) & 0xff) + '.' + ((addr >> 16) & 0xff) + '.' + ((addr >> 24) & 0xff);
  
  
  var inetNtop6 = (ints) => {
      //  ref:  http://www.ietf.org/rfc/rfc2373.txt - section 2.5.4
      //  Format for IPv4 compatible and mapped  128-bit IPv6 Addresses
      //  128-bits are split into eight 16-bit words
      //  stored in network byte order (big-endian)
      //  |                80 bits               | 16 |      32 bits        |
      //  +-----------------------------------------------------------------+
      //  |               10 bytes               |  2 |      4 bytes        |
      //  +--------------------------------------+--------------------------+
      //  +               5 words                |  1 |      2 words        |
      //  +--------------------------------------+--------------------------+
      //  |0000..............................0000|0000|    IPv4 ADDRESS     | (compatible)
      //  +--------------------------------------+----+---------------------+
      //  |0000..............................0000|FFFF|    IPv4 ADDRESS     | (mapped)
      //  +--------------------------------------+----+---------------------+
      var str = "";
      var word = 0;
      var longest = 0;
      var lastzero = 0;
      var zstart = 0;
      var len = 0;
      var i = 0;
      var parts = [
        ints[0] & 0xffff,
        (ints[0] >> 16),
        ints[1] & 0xffff,
        (ints[1] >> 16),
        ints[2] & 0xffff,
        (ints[2] >> 16),
        ints[3] & 0xffff,
        (ints[3] >> 16)
      ];
  
      // Handle IPv4-compatible, IPv4-mapped, loopback and any/unspecified addresses
  
      var hasipv4 = true;
      var v4part = "";
      // check if the 10 high-order bytes are all zeros (first 5 words)
      for (i = 0; i < 5; i++) {
        if (parts[i] !== 0) { hasipv4 = false; break; }
      }
  
      if (hasipv4) {
        // low-order 32-bits store an IPv4 address (bytes 13 to 16) (last 2 words)
        v4part = inetNtop4(parts[6] | (parts[7] << 16));
        // IPv4-mapped IPv6 address if 16-bit value (bytes 11 and 12) == 0xFFFF (6th word)
        if (parts[5] === -1) {
          str = "::ffff:";
          str += v4part;
          return str;
        }
        // IPv4-compatible IPv6 address if 16-bit value (bytes 11 and 12) == 0x0000 (6th word)
        if (parts[5] === 0) {
          str = "::";
          // special case IPv6 addresses
          if (v4part === "0.0.0.0") v4part = ""; // any/unspecified address
          if (v4part === "0.0.0.1") v4part = "1";// loopback address
          str += v4part;
          return str;
        }
      }
  
      // Handle all other IPv6 addresses
  
      // first run to find the longest contiguous zero words
      for (word = 0; word < 8; word++) {
        if (parts[word] === 0) {
          if (word - lastzero > 1) {
            len = 0;
          }
          lastzero = word;
          len++;
        }
        if (len > longest) {
          longest = len;
          zstart = word - longest + 1;
        }
      }
  
      for (word = 0; word < 8; word++) {
        if (longest > 1) {
          // compress contiguous zeros - to produce "::"
          if (parts[word] === 0 && word >= zstart && word < (zstart + longest) ) {
            if (word === zstart) {
              str += ":";
              if (zstart === 0) str += ":"; //leading zeros case
            }
            continue;
          }
        }
        // converts 16-bit words from big-endian to little-endian before converting to hex string
        str += Number(_ntohs(parts[word] & 0xffff)).toString(16);
        str += word < 7 ? ":" : "";
      }
      return str;
    };
  
  var readSockaddr = (sa, salen) => {
      // family / port offsets are common to both sockaddr_in and sockaddr_in6
      var family = HEAP16[((sa)>>1)];
      var port = _ntohs(HEAPU16[(((sa)+(2))>>1)]);
      var addr;
  
      switch (family) {
        case 2:
          if (salen !== 16) {
            return { errno: 28 };
          }
          addr = HEAP32[(((sa)+(4))>>2)];
          addr = inetNtop4(addr);
          break;
        case 10:
          if (salen !== 28) {
            return { errno: 28 };
          }
          addr = [
            HEAP32[(((sa)+(8))>>2)],
            HEAP32[(((sa)+(12))>>2)],
            HEAP32[(((sa)+(16))>>2)],
            HEAP32[(((sa)+(20))>>2)]
          ];
          addr = inetNtop6(addr);
          break;
        default:
          return { errno: 5 };
      }
  
      return { family: family, addr: addr, port: port };
    };
  
  
  var getSocketAddress = (addrp, addrlen) => {
      var info = readSockaddr(addrp, addrlen);
      if (info.errno) throw new FS.ErrnoError(info.errno);
      info.addr = DNS.lookup_addr(info.addr) || info.addr;
      return info;
    };
  
  
  function ___syscall_bind(fd, addr, len, u1, u2, u3) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(4, 0, 1, fd, addr, len, u1, u2, u3);
  
  try {
  
      var sock = getSocketFromFD(fd);
      var info = getSocketAddress(addr, len);
      sock.sock_ops.bind(sock, info.addr, info.port);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  var SYSCALLS = {
  currentUmask:18,
  calculateAt(dirfd, path, allowEmpty) {
        if (PATH.isAbs(path)) {
          return path;
        }
        // relative path
        var dir;
        if (dirfd === -100) {
          dir = FS.cwd();
        } else {
          var dirstream = SYSCALLS.getStreamFromFD(dirfd);
          dir = dirstream.path;
        }
        if (path.length == 0) {
          if (!allowEmpty) {
            throw new FS.ErrnoError(44);;
          }
          return dir;
        }
        return dir + '/' + path;
      },
  writeStat(buf, stat) {
        HEAPU32[((buf)>>2)] = stat.dev;
        HEAPU32[(((buf)+(4))>>2)] = stat.mode;
        HEAPU32[(((buf)+(8))>>2)] = stat.nlink;
        HEAPU32[(((buf)+(12))>>2)] = stat.uid;
        HEAPU32[(((buf)+(16))>>2)] = stat.gid;
        HEAPU32[(((buf)+(20))>>2)] = stat.rdev;
        HEAP64[(((buf)+(24))>>3)] = BigInt(stat.size);
        HEAP32[(((buf)+(32))>>2)] = 4096;
        HEAP32[(((buf)+(36))>>2)] = stat.blocks;
        var atime = stat.atime.getTime();
        var mtime = stat.mtime.getTime();
        var ctime = stat.ctime.getTime();
        HEAP64[(((buf)+(40))>>3)] = BigInt(Math.floor(atime / 1000));
        HEAPU32[(((buf)+(48))>>2)] = (atime % 1000) * 1000 * 1000;
        HEAP64[(((buf)+(56))>>3)] = BigInt(Math.floor(mtime / 1000));
        HEAPU32[(((buf)+(64))>>2)] = (mtime % 1000) * 1000 * 1000;
        HEAP64[(((buf)+(72))>>3)] = BigInt(Math.floor(ctime / 1000));
        HEAPU32[(((buf)+(80))>>2)] = (ctime % 1000) * 1000 * 1000;
        HEAP64[(((buf)+(88))>>3)] = BigInt(stat.ino);
        return 0;
      },
  writeStatFs(buf, stats) {
        HEAPU32[(((buf)+(4))>>2)] = stats.bsize;
        HEAPU32[(((buf)+(60))>>2)] = stats.bsize;
        HEAP64[(((buf)+(8))>>3)] = BigInt(stats.blocks);
        HEAP64[(((buf)+(16))>>3)] = BigInt(stats.bfree);
        HEAP64[(((buf)+(24))>>3)] = BigInt(stats.bavail);
        HEAP64[(((buf)+(32))>>3)] = BigInt(stats.files);
        HEAP64[(((buf)+(40))>>3)] = BigInt(stats.ffree);
        HEAPU32[(((buf)+(48))>>2)] = stats.fsid;
        HEAPU32[(((buf)+(64))>>2)] = stats.flags;  // ST_NOSUID
        HEAPU32[(((buf)+(56))>>2)] = stats.namelen;
      },
  doMsync(addr, stream, len, flags, offset) {
        if (!FS.isFile(stream.node.mode)) {
          throw new FS.ErrnoError(43);
        }
        if (flags & 2) {
          // MAP_PRIVATE calls need not to be synced back to underlying fs
          return 0;
        }
        var buffer = HEAPU8.subarray(addr, addr + len);
        FS.msync(stream, buffer, offset, len, flags);
      },
  getStreamFromFD(fd) {
        var stream = FS.getStreamChecked(fd);
        return stream;
      },
  varargs:undefined,
  getStr(ptr) {
        var ret = UTF8ToString(ptr);
        return ret;
      },
  };
  
  
  function ___syscall_chdir(path) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(5, 0, 1, path);
  
  try {
  
      path = SYSCALLS.getStr(path);
      FS.chdir(path);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_chmod(path, mode) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(6, 0, 1, path, mode);
  
  try {
  
      path = SYSCALLS.getStr(path);
      FS.chmod(path, mode);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  function ___syscall_connect(fd, addr, len, u1, u2, u3) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(7, 0, 1, fd, addr, len, u1, u2, u3);
  
  try {
  
      var sock = getSocketFromFD(fd);
      var info = getSocketAddress(addr, len);
      sock.sock_ops.connect(sock, info.addr, info.port);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_dup(fd) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(8, 0, 1, fd);
  
  try {
  
      var old = SYSCALLS.getStreamFromFD(fd);
      return FS.dupStream(old).fd;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_dup3(fd, newfd, flags) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(9, 0, 1, fd, newfd, flags);
  
  try {
  
      if (fd === newfd) return -28;
      if (flags & ~524288) return -28;
      var old = SYSCALLS.getStreamFromFD(fd);
      // Check newfd is within range of valid open file descriptors.
      if (newfd < 0 || newfd >= FS.MAX_OPEN_FDS) return -8;
      var existing = FS.getStream(newfd);
      if (existing) FS.close(existing);
      var stream = FS.dupStream(old, newfd);
      if (flags & 524288) {
        stream.flags |= 524288;
      }
      return stream.fd;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_faccessat(dirfd, path, amode, flags) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(10, 0, 1, dirfd, path, amode, flags);
  
  try {
  
      path = SYSCALLS.getStr(path);
      assert(!flags || flags == 512);
      path = SYSCALLS.calculateAt(dirfd, path);
      if (amode & ~7) {
        // need a valid mode
        return -28;
      }
      var lookup = FS.lookupPath(path, { follow: true });
      var node = lookup.node;
      if (!node) {
        return -44;
      }
      var perms = '';
      if (amode & 4) perms += 'r';
      if (amode & 2) perms += 'w';
      if (amode & 1) perms += 'x';
      if (perms /* otherwise, they've just passed F_OK */ && FS.nodePermissions(node, perms)) {
        return -2;
      }
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  function ___syscall_fchmod(fd, mode) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(11, 0, 1, fd, mode);
  
  try {
  
      FS.fchmod(fd, mode);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  function ___syscall_fchown32(fd, owner, group) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(12, 0, 1, fd, owner, group);
  
  try {
  
      FS.fchown(fd, owner, group);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_fchownat(dirfd, path, owner, group, flags) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(13, 0, 1, dirfd, path, owner, group, flags);
  
  try {
  
      path = SYSCALLS.getStr(path);
      var nofollow = flags & 256;
      flags = flags & (~256);
      assert(!flags);
      path = SYSCALLS.calculateAt(dirfd, path);
      (nofollow ? FS.lchown : FS.chown)(path, owner, group);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  var syscallGetVarargI = () => {
      assert(SYSCALLS.varargs != undefined);
      // the `+` prepended here is necessary to convince the JSCompiler that varargs is indeed a number.
      var ret = HEAP32[((+SYSCALLS.varargs)>>2)];
      SYSCALLS.varargs += 4;
      return ret;
    };
  var syscallGetVarargP = syscallGetVarargI;
  
  
  
  
  function ___syscall_fcntl64(fd, cmd, varargs) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(14, 0, 1, fd, cmd, varargs);
  
  SYSCALLS.varargs = varargs;
  try {
  
      var stream = SYSCALLS.getStreamFromFD(fd);
      switch (cmd) {
        case 0: {
          var arg = syscallGetVarargI();
          if (arg < 0) {
            return -28;
          }
          while (FS.streams[arg]) {
            arg++;
          }
          var newStream;
          newStream = FS.dupStream(stream, arg);
          return newStream.fd;
        }
        case 1:
        case 2:
          return 0;  // FD_CLOEXEC makes no sense for a single process.
        case 3:
          return stream.flags;
        case 4: {
          var arg = syscallGetVarargI();
          var mask = 289792;
          stream.flags = (stream.flags & ~mask) | (arg & mask);
          return 0;
        }
        case 12: {
          var arg = syscallGetVarargP();
          var offset = 0;
          // We're always unlocked.
          HEAP16[(((arg)+(offset))>>1)] = 2;
          return 0;
        }
        case 13:
        case 14:
          // Pretend that the locking is successful. These are process-level locks,
          // and Emscripten programs are a single process. If we supported linking a
          // filesystem between programs, we'd need to do more here.
          // See https://github.com/emscripten-core/emscripten/issues/23697
          return 0;
      }
      return -28;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_fstat64(fd, buf) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(15, 0, 1, fd, buf);
  
  try {
  
      return SYSCALLS.writeStat(buf, FS.fstat(fd));
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  var INT53_MAX = 9007199254740992;
  
  var INT53_MIN = -9007199254740992;
  var bigintToI53Checked = (num) => (num < INT53_MIN || num > INT53_MAX) ? NaN : Number(num);
  
  
  function ___syscall_ftruncate64(fd, length) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(16, 0, 1, fd, length);
  
    length = bigintToI53Checked(length);
  
  
  try {
  
      if (isNaN(length)) return -22;
      FS.ftruncate(fd, length);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  ;
  
  }
  

  
  var stringToUTF8 = (str, outPtr, maxBytesToWrite) => {
      assert(typeof maxBytesToWrite == 'number', 'stringToUTF8 requires a third parameter that specifies the length of the output buffer');
      return stringToUTF8Array(str, HEAPU8, outPtr, maxBytesToWrite);
    };
  
  
  function ___syscall_getcwd(buf, size) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(17, 0, 1, buf, size);
  
  try {
  
      if (size === 0) return -28;
      var cwd = FS.cwd();
      var cwdLengthInBytes = lengthBytesUTF8(cwd) + 1;
      if (size < cwdLengthInBytes) return -68;
      stringToUTF8(cwd, buf, size);
      return cwdLengthInBytes;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  function ___syscall_getdents64(fd, dirp, count) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(18, 0, 1, fd, dirp, count);
  
  try {
  
      var stream = SYSCALLS.getStreamFromFD(fd)
      stream.getdents ||= FS.readdir(stream.path);
  
      var struct_size = 280;
      var pos = 0;
      var off = FS.llseek(stream, 0, 1);
  
      var startIdx = Math.floor(off / struct_size);
      var endIdx = Math.min(stream.getdents.length, startIdx + Math.floor(count/struct_size))
      for (var idx = startIdx; idx < endIdx; idx++) {
        var id;
        var type;
        var name = stream.getdents[idx];
        if (name === '.') {
          id = stream.node.id;
          type = 4;
        }
        else if (name === '..') {
          var lookup = FS.lookupPath(stream.path, { parent: true });
          id = lookup.node.id;
          type = 4;
        }
        else {
          var child;
          try {
            child = FS.lookupNode(stream.node, name);
          } catch (e) {
            // If the entry is not a directory, file, or symlink, nodefs
            // lookupNode will raise EINVAL. Skip these and continue.
            if (e?.errno === 28) {
              continue;
            }
            throw e;
          }
          id = child.id;
          type = FS.isChrdev(child.mode) ? 2 : // character device.
                 FS.isDir(child.mode) ? 4 :    // directory
                 FS.isLink(child.mode) ? 10 :   // symbolic link.
                 8;                            // regular file.
        }
        assert(id);
        HEAP64[((dirp + pos)>>3)] = BigInt(id);
        HEAP64[(((dirp + pos)+(8))>>3)] = BigInt((idx + 1) * struct_size);
        HEAP16[(((dirp + pos)+(16))>>1)] = 280;
        HEAP8[(dirp + pos)+(18)] = type;
        stringToUTF8(name, dirp + pos + 19, 256);
        pos += struct_size;
      }
      FS.llseek(stream, idx * struct_size, 0);
      return pos;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  
  function ___syscall_getpeername(fd, addr, len, u1, u2, u3) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(19, 0, 1, fd, addr, len, u1, u2, u3);
  
  try {
  
      var sock = getSocketFromFD(fd);
      if (!sock.daddr) {
        return -53; // The socket is not connected.
      }
      var errno = writeSockaddr(addr, sock.family, DNS.lookup_name(sock.daddr), sock.dport, len);
      assert(!errno);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  
  function ___syscall_getsockname(fd, addr, len, u1, u2, u3) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(20, 0, 1, fd, addr, len, u1, u2, u3);
  
  try {
  
      var sock = getSocketFromFD(fd);
      // TODO: sock.saddr should never be undefined, see TODO in websocket_sock_ops.getname
      var errno = writeSockaddr(addr, sock.family, DNS.lookup_name(sock.saddr || '0.0.0.0'), sock.sport, len);
      assert(!errno);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_getsockopt(fd, level, optname, optval, optlen, unused) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(21, 0, 1, fd, level, optname, optval, optlen, unused);
  
  try {
  
      var sock = getSocketFromFD(fd);
      // Minimal getsockopt aimed at resolving https://github.com/emscripten-core/emscripten/issues/2211
      // so only supports SOL_SOCKET with SO_ERROR.
      if (level === 1) {
        if (optname === 4) {
          HEAP32[((optval)>>2)] = sock.error;
          HEAP32[((optlen)>>2)] = 4;
          sock.error = null; // Clear the error (The SO_ERROR option obtains and then clears this field).
          return 0;
        }
      }
      return -50; // The option is unknown at the level indicated.
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  function ___syscall_ioctl(fd, op, varargs) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(22, 0, 1, fd, op, varargs);
  
  SYSCALLS.varargs = varargs;
  try {
  
      var stream = SYSCALLS.getStreamFromFD(fd);
      switch (op) {
        case 21509: {
          if (!stream.tty) return -59;
          return 0;
        }
        case 21505: {
          if (!stream.tty) return -59;
          if (stream.tty.ops.ioctl_tcgets) {
            var termios = stream.tty.ops.ioctl_tcgets(stream);
            var argp = syscallGetVarargP();
            HEAP32[((argp)>>2)] = termios.c_iflag || 0;
            HEAP32[(((argp)+(4))>>2)] = termios.c_oflag || 0;
            HEAP32[(((argp)+(8))>>2)] = termios.c_cflag || 0;
            HEAP32[(((argp)+(12))>>2)] = termios.c_lflag || 0;
            for (var i = 0; i < 32; i++) {
              HEAP8[(argp + i)+(17)] = termios.c_cc[i] || 0;
            }
            return 0;
          }
          return 0;
        }
        case 21510:
        case 21511:
        case 21512: {
          if (!stream.tty) return -59;
          return 0; // no-op, not actually adjusting terminal settings
        }
        case 21506:
        case 21507:
        case 21508: {
          if (!stream.tty) return -59;
          if (stream.tty.ops.ioctl_tcsets) {
            var argp = syscallGetVarargP();
            var c_iflag = HEAP32[((argp)>>2)];
            var c_oflag = HEAP32[(((argp)+(4))>>2)];
            var c_cflag = HEAP32[(((argp)+(8))>>2)];
            var c_lflag = HEAP32[(((argp)+(12))>>2)];
            var c_cc = []
            for (var i = 0; i < 32; i++) {
              c_cc.push(HEAP8[(argp + i)+(17)]);
            }
            return stream.tty.ops.ioctl_tcsets(stream.tty, op, { c_iflag, c_oflag, c_cflag, c_lflag, c_cc });
          }
          return 0; // no-op, not actually adjusting terminal settings
        }
        case 21519: {
          if (!stream.tty) return -59;
          var argp = syscallGetVarargP();
          HEAP32[((argp)>>2)] = 0;
          return 0;
        }
        case 21520: {
          if (!stream.tty) return -59;
          return -28; // not supported
        }
        case 21537:
        case 21531: {
          var argp = syscallGetVarargP();
          return FS.ioctl(stream, op, argp);
        }
        case 21523: {
          // TODO: in theory we should write to the winsize struct that gets
          // passed in, but for now musl doesn't read anything on it
          if (!stream.tty) return -59;
          if (stream.tty.ops.ioctl_tiocgwinsz) {
            var winsize = stream.tty.ops.ioctl_tiocgwinsz(stream.tty);
            var argp = syscallGetVarargP();
            HEAP16[((argp)>>1)] = winsize[0];
            HEAP16[(((argp)+(2))>>1)] = winsize[1];
          }
          return 0;
        }
        case 21524: {
          // TODO: technically, this ioctl call should change the window size.
          // but, since emscripten doesn't have any concept of a terminal window
          // yet, we'll just silently throw it away as we do TIOCGWINSZ
          if (!stream.tty) return -59;
          return 0;
        }
        case 21515: {
          if (!stream.tty) return -59;
          return 0;
        }
        default: return -28; // not supported
      }
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_listen(fd, backlog, u1, u2, u3, u4) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(23, 0, 1, fd, backlog, u1, u2, u3, u4);
  
  try {
  
      var sock = getSocketFromFD(fd);
      sock.sock_ops.listen(sock, backlog);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_lstat64(path, buf) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(24, 0, 1, path, buf);
  
  try {
  
      path = SYSCALLS.getStr(path);
      return SYSCALLS.writeStat(buf, FS.lstat(path));
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_mkdirat(dirfd, path, mode) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(25, 0, 1, dirfd, path, mode);
  
  try {
  
      path = SYSCALLS.getStr(path);
      path = SYSCALLS.calculateAt(dirfd, path);
      mode &= ~SYSCALLS.currentUmask;
      FS.mkdir(path, mode, 0);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_newfstatat(dirfd, path, buf, flags) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(26, 0, 1, dirfd, path, buf, flags);
  
  try {
  
      path = SYSCALLS.getStr(path);
      var nofollow = flags & 256;
      var allowEmpty = flags & 4096;
      flags = flags & (~6400);
      assert(!flags, `unknown flags in __syscall_newfstatat: ${flags}`);
      path = SYSCALLS.calculateAt(dirfd, path, allowEmpty);
      return SYSCALLS.writeStat(buf, nofollow ? FS.lstat(path) : FS.stat(path));
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  function ___syscall_openat(dirfd, path, flags, varargs) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(27, 0, 1, dirfd, path, flags, varargs);
  
  SYSCALLS.varargs = varargs;
  try {
  
      path = SYSCALLS.getStr(path);
      path = SYSCALLS.calculateAt(dirfd, path);
      var mode = varargs ? syscallGetVarargI() : 0;
      if (flags & 64) {
        mode &= ~SYSCALLS.currentUmask;
      }
      return FS.open(path, flags, mode).fd;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  var PIPEFS = {
  BUCKET_BUFFER_SIZE:8192,
  mount(mount) {
        // Do not pollute the real root directory or its child nodes with pipes
        // Looks like it is OK to create another pseudo-root node not linked to the FS.root hierarchy this way
        return FS.createNode(null, '/', 16384 | 0o777, 0);
      },
  createPipe() {
        var pipe = {
          buckets: [],
          // refcnt 2 because pipe has a read end and a write end. We need to be
          // able to read from the read end after write end is closed.
          refcnt : 2,
          timestamp: new Date(),
          readableHandlers: [],
          registerReadableHandler: (callback) => {
            callback.registerCleanupFunc(() => {
              const i = pipe.readableHandlers.indexOf(callback);
              if (i !== -1) pipe.readableHandlers.splice(i, 1);
            });
            pipe.readableHandlers.push(callback);
          },
          notifyReadableHandlers: () => {
            while (pipe.readableHandlers.length > 0) {
              const cb = pipe.readableHandlers.shift();
              if (cb) cb(64 | 1);
            }
            pipe.readableHandlers = [];
          }
        };
  
        pipe.buckets.push({
          buffer: new Uint8Array(PIPEFS.BUCKET_BUFFER_SIZE),
          offset: 0,
          roffset: 0
        });
  
        var rName = PIPEFS.nextname();
        var wName = PIPEFS.nextname();
        var rNode = FS.createNode(PIPEFS.root, rName, 4096, 0);
        var wNode = FS.createNode(PIPEFS.root, wName, 4096, 0);
  
        rNode.pipe = pipe;
        wNode.pipe = pipe;
  
        var readableStream = FS.createStream({
          path: rName,
          node: rNode,
          flags: 0,
          seekable: false,
          stream_ops: PIPEFS.stream_ops
        });
        rNode.stream = readableStream;
  
        var writableStream = FS.createStream({
          path: wName,
          node: wNode,
          flags: 1,
          seekable: false,
          stream_ops: PIPEFS.stream_ops
        });
        wNode.stream = writableStream;
  
        return {
          readable_fd: readableStream.fd,
          writable_fd: writableStream.fd
        };
      },
  stream_ops:{
  getattr(stream) {
          var node = stream.node;
          var timestamp = node.pipe.timestamp;
          return {
            dev: 14,
            ino: node.id,
            mode: 0o10600,
            nlink: 1,
            uid: 0,
            gid: 0,
            rdev: 0,
            size: 0,
            atime: timestamp,
            mtime: timestamp,
            ctime: timestamp,
            blksize: 4096,
            blocks: 0,
          };
        },
  poll(stream, timeout, notifyCallback) {
          var pipe = stream.node.pipe;
  
          if ((stream.flags & 2097155) === 1) {
            return (256 | 4);
          }
          for (var bucket of pipe.buckets) {
            if (bucket.offset - bucket.roffset > 0) {
              return (64 | 1);
            }
          }
  
          if (notifyCallback) pipe.registerReadableHandler(notifyCallback);
          return 0;
        },
  dup(stream) {
          stream.node.pipe.refcnt++;
        },
  ioctl(stream, request, argp) {
          if (request == 21531) {
            var pipe = stream.node.pipe;
            var currentLength = 0;
            for (var bucket of pipe.buckets) {
              currentLength += bucket.offset - bucket.roffset;
            }
            HEAP32[((argp)>>2)] = currentLength;
            return 0;
          }
          return 28;
        },
  fsync(stream) {
          return 28;
        },
  read(stream, buffer, offset, length, position /* ignored */) {
          var pipe = stream.node.pipe;
          var currentLength = 0;
  
          for (var bucket of pipe.buckets) {
            currentLength += bucket.offset - bucket.roffset;
          }
  
          assert(buffer instanceof ArrayBuffer || buffer instanceof SharedArrayBuffer || ArrayBuffer.isView(buffer));
          var data = buffer.subarray(offset, offset + length);
  
          if (length <= 0) {
            return 0;
          }
          if (currentLength == 0) {
            // Behave as if the read end is always non-blocking
            throw new FS.ErrnoError(6);
          }
          var toRead = Math.min(currentLength, length);
  
          var totalRead = toRead;
          var toRemove = 0;
  
          for (var bucket of pipe.buckets) {
            var bucketSize = bucket.offset - bucket.roffset;
  
            if (toRead <= bucketSize) {
              var tmpSlice = bucket.buffer.subarray(bucket.roffset, bucket.offset);
              if (toRead < bucketSize) {
                tmpSlice = tmpSlice.subarray(0, toRead);
                bucket.roffset += toRead;
              } else {
                toRemove++;
              }
              data.set(tmpSlice);
              break;
            } else {
              var tmpSlice = bucket.buffer.subarray(bucket.roffset, bucket.offset);
              data.set(tmpSlice);
              data = data.subarray(tmpSlice.byteLength);
              toRead -= tmpSlice.byteLength;
              toRemove++;
            }
          }
  
          if (toRemove && toRemove == pipe.buckets.length) {
            // Do not generate excessive garbage in use cases such as
            // write several bytes, read everything, write several bytes, read everything...
            toRemove--;
            pipe.buckets[toRemove].offset = 0;
            pipe.buckets[toRemove].roffset = 0;
          }
  
          pipe.buckets.splice(0, toRemove);
  
          return totalRead;
        },
  write(stream, buffer, offset, length, position /* ignored */) {
          var pipe = stream.node.pipe;
  
          assert(buffer instanceof ArrayBuffer || buffer instanceof SharedArrayBuffer || ArrayBuffer.isView(buffer));
          var data = buffer.subarray(offset, offset + length);
  
          var dataLen = data.byteLength;
          if (dataLen <= 0) {
            return 0;
          }
  
          var currBucket = null;
  
          if (pipe.buckets.length == 0) {
            currBucket = {
              buffer: new Uint8Array(PIPEFS.BUCKET_BUFFER_SIZE),
              offset: 0,
              roffset: 0
            };
            pipe.buckets.push(currBucket);
          } else {
            currBucket = pipe.buckets[pipe.buckets.length - 1];
          }
  
          assert(currBucket.offset <= PIPEFS.BUCKET_BUFFER_SIZE);
  
          var freeBytesInCurrBuffer = PIPEFS.BUCKET_BUFFER_SIZE - currBucket.offset;
          if (freeBytesInCurrBuffer >= dataLen) {
            currBucket.buffer.set(data, currBucket.offset);
            currBucket.offset += dataLen;
            pipe.notifyReadableHandlers();
            return dataLen;
          } else if (freeBytesInCurrBuffer > 0) {
            currBucket.buffer.set(data.subarray(0, freeBytesInCurrBuffer), currBucket.offset);
            currBucket.offset += freeBytesInCurrBuffer;
            data = data.subarray(freeBytesInCurrBuffer, data.byteLength);
          }
  
          var numBuckets = (data.byteLength / PIPEFS.BUCKET_BUFFER_SIZE) | 0;
          var remElements = data.byteLength % PIPEFS.BUCKET_BUFFER_SIZE;
  
          for (var i = 0; i < numBuckets; i++) {
            var newBucket = {
              buffer: new Uint8Array(PIPEFS.BUCKET_BUFFER_SIZE),
              offset: PIPEFS.BUCKET_BUFFER_SIZE,
              roffset: 0
            };
            pipe.buckets.push(newBucket);
            newBucket.buffer.set(data.subarray(0, PIPEFS.BUCKET_BUFFER_SIZE));
            data = data.subarray(PIPEFS.BUCKET_BUFFER_SIZE, data.byteLength);
          }
  
          if (remElements > 0) {
            var newBucket = {
              buffer: new Uint8Array(PIPEFS.BUCKET_BUFFER_SIZE),
              offset: data.byteLength,
              roffset: 0
            };
            pipe.buckets.push(newBucket);
            newBucket.buffer.set(data);
          }
  
          pipe.notifyReadableHandlers();
          return dataLen;
        },
  close(stream) {
          var pipe = stream.node.pipe;
          pipe.refcnt--;
          if (pipe.refcnt === 0) {
            pipe.buckets = null;
          }
        },
  },
  nextname() {
        if (!PIPEFS.nextname.current) {
          PIPEFS.nextname.current = 0;
        }
        return 'pipe[' + (PIPEFS.nextname.current++) + ']';
      },
  };
  
  
  function ___syscall_pipe2(fdPtr, flags) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(28, 0, 1, fdPtr, flags);
  
  try {
  
      if (fdPtr == 0) {
        throw new FS.ErrnoError(21);
      }
      var validFlags = 524288 | 2048;
      if (flags & ~validFlags) {
        throw new FS.ErrnoError(138);
      }
  
      var res = PIPEFS.createPipe();
  
      if (flags & 2048) {
        FS.getStream(res.readable_fd).flags |= 2048;
        FS.getStream(res.writable_fd).flags |= 2048;
      }
  
      HEAP32[((fdPtr)>>2)] = res.readable_fd;
      HEAP32[(((fdPtr)+(4))>>2)] = res.writable_fd;
  
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  var doPoll = (fds, nfds, timeout, makeNotifyCallback) => {
      var count = 0;
      for (var i = 0; i < nfds; i++) {
        var pollfd = fds + 8 * i;
        var fd = HEAP32[((pollfd)>>2)];
        var events = HEAP16[(((pollfd)+(4))>>1)];
        var flags = 32;
        var stream = FS.getStream(fd);
        if (stream) {
          if (stream.stream_ops.poll) {
            flags = timeout
              ? stream.stream_ops.poll(stream, timeout, makeNotifyCallback(stream, pollfd))
              : stream.stream_ops.poll(stream, -1);
          } else {
            flags = 5;
          }
        }
        flags &= events | 8 | 16 | 32;
        if (flags) count++;
        HEAP16[(((pollfd)+(6))>>1)] = flags;
      }
      return count;
    };
  
  
  var doPollAsync = (fds, nfds, timeout) => {
  
      // Enable event handlers only when the poll call is proxied from a worker.
      // TODO: Could use `Promise.withResolvers` here if we know its available.
      var resolve;
      var promise = new Promise((resolve_) => { resolve = resolve_; });
      var cleanupFuncs = [];
      var notifyDone = false;
  
      function asyncPollComplete(count) {
        if (notifyDone) {
          return;
        }
        notifyDone = true;
        cleanupFuncs.forEach(cb => cb());
        resolve(count);
      }
  
      function makeNotifyCallback(stream, pollfd) {
        var cb = (flags) => {
          if (notifyDone) {
            return;
          }
          var events = HEAP16[(((pollfd)+(4))>>1)];
          flags &= events | 8 | 16 | 32;
          assert(flags)
          HEAP16[(((pollfd)+(6))>>1)] = flags;
          asyncPollComplete(1);
        }
        cb.registerCleanupFunc = (f) => {
          if (f) cleanupFuncs.push(f);
        }
        return cb;
      }
      if (timeout > 0) {
        var t = setTimeout(() => {
          asyncPollComplete(0);
        }, timeout);
        cleanupFuncs.push(() => clearTimeout(t));
      }
      // A zero timeout never registers notifications: the derivation alone
      // answers, matching the non-blocking probe.
      var count = doPoll(fds, nfds, timeout, makeNotifyCallback);
      if (count || !timeout) {
        asyncPollComplete(count);
      }
      return promise;
    };
  
  
  function ___syscall_poll(fds, nfds, timeout) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(29, 0, 2, fds, nfds, timeout);
  
  try {
  
      const isAsyncContext = PThread.currentProxiedOperationCallerThread;
      if (isAsyncContext) {
        return doPollAsync(fds, nfds, timeout);
      }
  
      var count = doPoll(fds, nfds, 0, undefined);
      if (!count && timeout != 0) warnOnce('non-zero poll() timeout not supported: ' + timeout)
      return count;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_poll_nonblocking(fds, nfds) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(30, 0, 1, fds, nfds);
  
  try {
  
      return doPoll(fds, nfds, 0, undefined);
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  
  function ___syscall_readlinkat(dirfd, path, buf, bufsize) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(31, 0, 1, dirfd, path, buf, bufsize);
  
  try {
  
      path = SYSCALLS.getStr(path);
      path = SYSCALLS.calculateAt(dirfd, path);
      if (bufsize <= 0) return -28;
      var ret = FS.readlink(path);
  
      var len = Math.min(bufsize, lengthBytesUTF8(ret));
      var endChar = HEAP8[buf+len];
      stringToUTF8(ret, buf, bufsize+1);
      // readlink is one of the rare functions that write out a C string, but does never append a null to the output buffer(!)
      // stringToUTF8() always appends a null byte, so restore the character under the null byte after the write.
      HEAP8[buf+len] = endChar;
      return len;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  
  function ___syscall_recvfrom(fd, buf, len, flags, addr, alen) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(32, 0, 1, fd, buf, len, flags, addr, alen);
  
  try {
  
      var sock = getSocketFromFD(fd);
      var msg = sock.sock_ops.recvmsg(sock, len);
      if (!msg) return 0; // socket is closed
      if (addr) {
        var errno = writeSockaddr(addr, sock.family, DNS.lookup_name(msg.addr), msg.port, alen);
        assert(!errno);
      }
      HEAPU8.set(msg.buffer, buf);
      return msg.buffer.byteLength;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  
  function ___syscall_recvmsg(fd, message, flags, u1, u2, u3) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(33, 0, 1, fd, message, flags, u1, u2, u3);
  
  try {
  
      var sock = getSocketFromFD(fd);
      var iov = HEAPU32[(((message)+(8))>>2)];
      var num = HEAP32[(((message)+(12))>>2)];
      // get the total amount of data we can read across all arrays
      var total = 0;
      for (var i = 0; i < num; i++) {
        total += HEAP32[(((iov)+((8 * i) + 4))>>2)];
      }
      // try to read total data
      var msg = sock.sock_ops.recvmsg(sock, total);
      if (!msg) return 0; // socket is closed
  
      // TODO honor flags:
      // MSG_OOB
      // Requests out-of-band data. The significance and semantics of out-of-band data are protocol-specific.
      // MSG_PEEK
      // Peeks at the incoming message.
      // MSG_WAITALL
      // Requests that the function block until the full amount of data requested can be returned. The function may return a smaller amount of data if a signal is caught, if the connection is terminated, if MSG_PEEK was specified, or if an error is pending for the socket.
  
      // write the source address out
      var name = HEAPU32[((message)>>2)];
      if (name) {
        var errno = writeSockaddr(name, sock.family, DNS.lookup_name(msg.addr), msg.port);
        assert(!errno);
      }
      // write the buffer out to the scatter-gather arrays
      var bytesRead = 0;
      var bytesRemaining = msg.buffer.byteLength;
      for (var i = 0; bytesRemaining > 0 && i < num; i++) {
        var iovbase = HEAPU32[(((iov)+((8 * i) + 0))>>2)];
        var iovlen = HEAP32[(((iov)+((8 * i) + 4))>>2)];
        if (!iovlen) {
          continue;
        }
        var length = Math.min(iovlen, bytesRemaining);
        var buf = msg.buffer.subarray(bytesRead, bytesRead + length);
        HEAPU8.set(buf, iovbase + bytesRead);
        bytesRead += length;
        bytesRemaining -= length;
      }
  
      // TODO set msghdr.msg_flags
      // MSG_EOR
      // End of record was received (if supported by the protocol).
      // MSG_OOB
      // Out-of-band data was received.
      // MSG_TRUNC
      // Normal data was truncated.
      // MSG_CTRUNC
  
      return bytesRead;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_renameat(olddirfd, oldpath, newdirfd, newpath) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(34, 0, 1, olddirfd, oldpath, newdirfd, newpath);
  
  try {
  
      oldpath = SYSCALLS.getStr(oldpath);
      newpath = SYSCALLS.getStr(newpath);
      oldpath = SYSCALLS.calculateAt(olddirfd, oldpath);
      newpath = SYSCALLS.calculateAt(newdirfd, newpath);
      FS.rename(oldpath, newpath);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_rmdir(path) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(35, 0, 1, path);
  
  try {
  
      path = SYSCALLS.getStr(path);
      FS.rmdir(path);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  function ___syscall_sendmsg(fd, message, flags, u1, u2, u3) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(36, 0, 1, fd, message, flags, u1, u2, u3);
  
  try {
  
      var sock = getSocketFromFD(fd);
      var iov = HEAPU32[(((message)+(8))>>2)];
      var num = HEAP32[(((message)+(12))>>2)];
      // read the address and port to send to
      var addr, port;
      var name = HEAPU32[((message)>>2)];
      var namelen = HEAP32[(((message)+(4))>>2)];
      if (name) {
        var info = getSocketAddress(name, namelen);
        port = info.port;
        addr = info.addr;
      }
      // concatenate scatter-gather arrays into one message buffer
      var total = 0;
      for (var i = 0; i < num; i++) {
        total += HEAP32[(((iov)+((8 * i) + 4))>>2)];
      }
      var view = new Uint8Array(total);
      var offset = 0;
      for (var i = 0; i < num; i++) {
        var iovbase = HEAPU32[(((iov)+((8 * i) + 0))>>2)];
        var iovlen = HEAP32[(((iov)+((8 * i) + 4))>>2)];
        for (var j = 0; j < iovlen; j++) {
          view[offset++] = HEAP8[(iovbase)+(j)];
        }
      }
      // write the buffer
      return sock.sock_ops.sendmsg(sock, view, 0, total, addr, port);
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  
  function ___syscall_sendto(fd, buf, len, flags, addr, alen) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(37, 0, 1, fd, buf, len, flags, addr, alen);
  
  try {
  
      var sock = getSocketFromFD(fd);
      if (!addr) {
        // send, no address provided
        return FS.write(sock.stream, HEAP8, buf, len);
      }
      var dest = getSocketAddress(addr, alen);
      // sendto an address
      return sock.sock_ops.sendmsg(sock, HEAP8, buf, len, dest.addr, dest.port);
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_setsockopt(fd, level, optname, optval, optlen, unused) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(38, 0, 1, fd, level, optname, optval, optlen, unused);
  
  try {
  
      getSocketFromFD(fd); // validate the fd (and keep this syscall's catch reachable)
      return -50; // The option is unknown at the level indicated.
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_shutdown(fd, how, u1, u2, u3, u4) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(39, 0, 1, fd, how, u1, u2, u3, u4);
  
  try {
  
      var sock = getSocketFromFD(fd);
      return -52; // unsupported feature
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_socket(domain, type, protocol, u1, u2, u3) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(40, 0, 1, domain, type, protocol, u1, u2, u3);
  
  try {
  
      var sock = SOCKFS.createSocket(domain, type, protocol);
      assert(sock.stream.fd < 64); // XXX ? select() assumes socket fd values are in 0..63
      return sock.stream.fd;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_stat64(path, buf) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(41, 0, 1, path, buf);
  
  try {
  
      path = SYSCALLS.getStr(path);
      return SYSCALLS.writeStat(buf, FS.stat(path));
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_statfs64(path, size, buf) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(42, 0, 1, path, size, buf);
  
  try {
  
      assert(size === 88);
      SYSCALLS.writeStatFs(buf, FS.statfs(SYSCALLS.getStr(path)));
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_symlinkat(target, dirfd, linkpath) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(43, 0, 1, target, dirfd, linkpath);
  
  try {
  
      target = SYSCALLS.getStr(target);
      linkpath = SYSCALLS.getStr(linkpath);
      linkpath = SYSCALLS.calculateAt(dirfd, linkpath);
      FS.symlink(target, linkpath);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_umask(mask) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(44, 0, 1, mask);
  
  try {
  
      var old = SYSCALLS.currentUmask;
      SYSCALLS.currentUmask = mask;
      return old;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  
  
  function ___syscall_unlinkat(dirfd, path, flags) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(45, 0, 1, dirfd, path, flags);
  
  try {
  
      path = SYSCALLS.getStr(path);
      path = SYSCALLS.calculateAt(dirfd, path);
      if (!flags) {
        FS.unlink(path);
      } else if (flags === 512) {
        FS.rmdir(path);
      } else {
        return -28;
      }
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  var readI53FromI64 = (ptr) => {
      return HEAPU32[((ptr)>>2)] + HEAP32[(((ptr)+(4))>>2)] * 4294967296;
    };
  
  
  
  function ___syscall_utimensat(dirfd, path, times, flags) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(46, 0, 1, dirfd, path, times, flags);
  
  try {
  
      path = SYSCALLS.getStr(path);
      assert(!flags);
      path = SYSCALLS.calculateAt(dirfd, path, true);
      var now = Date.now(), atime, mtime;
      if (!times) {
        atime = now;
        mtime = now;
      } else {
        var seconds = readI53FromI64(times);
        var nanoseconds = HEAP32[(((times)+(8))>>2)];
        if (nanoseconds == 1073741823) {
          atime = now;
        } else if (nanoseconds == 1073741822) {
          atime = null;
        } else {
          atime = (seconds*1000) + (nanoseconds/(1000*1000));
        }
        times += 16;
        seconds = readI53FromI64(times);
        nanoseconds = HEAP32[(((times)+(8))>>2)];
        if (nanoseconds == 1073741823) {
          mtime = now;
        } else if (nanoseconds == 1073741822) {
          mtime = null;
        } else {
          mtime = (seconds*1000) + (nanoseconds/(1000*1000));
        }
      }
      // null here means UTIME_OMIT was passed. If both were set to UTIME_OMIT then
      // we can skip the call completely.
      if ((mtime ?? atime) !== null) {
        FS.utime(path, atime, mtime);
      }
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return -e.errno;
  }
  
  }
  

  var __abort_js = () =>
      abort('native code called abort()');

  var __emscripten_init_main_thread_js = (tb) => {
      var can_block = !ENVIRONMENT_IS_WEB;
      // Feature detect whether the main thread can block.
      try {
        Atomics.wait(HEAP32, 0, 0, 0)
        can_block = true;
      } catch (e) {}
      // Pass the thread address to the native code where they are stored in wasm
      // globals which act as a form of TLS. Global constructors trying
      // to access this value will read the wrong value, but that is UB anyway.
      __emscripten_thread_init(
        tb,
        /*is_main=*/!ENVIRONMENT_IS_WORKER,
        /*is_runtime=*/1,
        can_block,
        /*default_stacksize=*/8388608,
        /*start_profiling=*/false,
      );
      PThread.threadInitTLS();
    };

  var handleException = (e) => {
      // Certain exception types we do not treat as errors since they are used for
      // internal control flow.
      // 1. ExitStatus, which is thrown by exit()
      // 2. "unwind", which is thrown by emscripten_unwind_to_js_event_loop() and others
      //    that wish to return to JS event loop.
      if (e instanceof ExitStatus || e == 'unwind') {
        return EXITSTATUS;
      }
      checkStackCookie();
      if (e instanceof WebAssembly.RuntimeError) {
        if (_emscripten_stack_get_current() <= 0) {
          err('Stack overflow detected.  You can try increasing -sSTACK_SIZE (currently set to 8388608)');
        }
      }
      quit_(1, e);
    };
  
  
  
  
  var maybeExit = () => {
      if (!keepRuntimeAlive()) {
        try {
          if (ENVIRONMENT_IS_PTHREAD) {
            // exit the current thread, but only if there is one active.
            // TODO(https://github.com/emscripten-core/emscripten/issues/25076):
            // Unify this check with the runtimeExited check above
            if (_pthread_self()) __emscripten_thread_exit(EXITSTATUS);
            return;
          }
          _exit(EXITSTATUS);
        } catch (e) {
          handleException(e);
        }
      }
    };
  var callUserCallback = (func) => {
      if (ABORT) {
        err('user callback triggered after runtime exited or application aborted.  Ignoring.');
        return;
      }
      try {
        return func();
      } catch (e) {
        handleException(e);
      } finally {
        maybeExit();
      }
    };
  
  
  
  
  
  var __emscripten_thread_mailbox_await = (pthread_ptr) => {
      if (!waitAsyncPolyfilled) {
        // Wait on the pthread's initial self-pointer field because it is easy and
        // safe to access from sending threads that need to notify the waiting
        // thread.
        // Note: Under wasm64 only the low 32-bit of the pthread_ptr are
        // read/compared here, but we don't actually care about the exact values
        // here as long as they match.
        var wait = Atomics.waitAsync(HEAP32, ((pthread_ptr)>>2), pthread_ptr);
        assert(wait.async);
        wait.value.then(checkMailbox);
        var waitingAsync = pthread_ptr + 112;
        Atomics.store(HEAP32, ((waitingAsync)>>2), 1);
      }
      // If `Atomics.waitAsync` is not implemented, then we will always fall back
      // to postMessage and there is no need to do anything here.
    };
  
  var checkMailbox = () => {
      // checkMailbox can be called after the pthread has shut down. See
      // Pthread.terminateRuntime().
      // In this case we return silently without re-registering using waitAsync.
      // Perhaps there is a more universal way we can detect runtime has exited.
      // TODO(https://github.com/emscripten-core/emscripten/issues/25076)
      var pthread_ptr = _pthread_self();
      if (!pthread_ptr) return;
      callUserCallback(() => {
        // If we are using Atomics.waitAsync as our notification mechanism, wait
        // for a notification before processing the mailbox to avoid missing any
        // work that could otherwise arrive after we've finished processing the
        // mailbox and before we're ready for the next notification.
        __emscripten_thread_mailbox_await(pthread_ptr);
        __emscripten_check_mailbox();
      });
    };
  
  var __emscripten_notify_mailbox_postmessage = (targetThread, currThreadId) => {
      if (targetThread == currThreadId) {
        setTimeout(checkMailbox);
      } else if (ENVIRONMENT_IS_PTHREAD) {
        postMessage({targetThread, cmd: 4});
      } else {
        var worker = PThread.pthreads[targetThread];
        if (!worker) {
          err(`Cannot send message to thread with ID ${targetThread}, unknown thread ID!`);
          return;
        }
        worker.postMessage({cmd: 4});
      }
    };

  
  
  var proxiedJSCallArgs = [];
  
  var __emscripten_receive_on_main_thread_js = (funcIndex, emAsmAddr, callingThread, bufSize, args, ctx, ctxArgs) => {
      // Sometimes we need to backproxy events to the calling thread (e.g.
      // HTML5 DOM events handlers such as
      // emscripten_set_mousemove_callback()), so keep track in a globally
      // accessible variable about the thread that initiated the proxying.
      proxiedJSCallArgs.length = 0;
      var b = ((args)>>3);
      var end = ((args + bufSize)>>3);
      while (b < end) {
        var arg;
        if (HEAP64[b++]) {
          // It's a BigInt.
          arg = HEAP64[b++];
        } else {
          // It's a Number.
          arg = HEAPF64[b++];
        }
        proxiedJSCallArgs.push(arg);
      }
      // Proxied JS library funcs use funcIndex and EM_ASM functions use emAsmAddr
      assert(!emAsmAddr);
      var func = proxiedFunctionTable[funcIndex];
      assert(!(funcIndex && emAsmAddr));
      assert(func.length == proxiedJSCallArgs.length, 'Call args mismatch in _emscripten_receive_on_main_thread_js');
      PThread.currentProxiedOperationCallerThread = callingThread;
      var rtn = func(...proxiedJSCallArgs);
      PThread.currentProxiedOperationCallerThread = 0;
      if (ctx) {
        rtn.then((rtn) => __emscripten_run_js_on_main_thread_done(ctx, ctxArgs, rtn));
        return;
      }
  
      // Proxied functions can return any type except bigint.  All other types
      // coerce to f64/double (the return type of this function in C) but not
      // bigint.
      assert(typeof rtn != "bigint");
      return rtn;
    };

  var __emscripten_runtime_keepalive_clear = () => {
      noExitRuntime = false;
      runtimeKeepaliveCounter = 0;
    };

  var __emscripten_system = (command) => {
      if (ENVIRONMENT_IS_NODE) {
        if (!command) return 1; // shell is available
  
        var cmdstr = UTF8ToString(command);
        if (!cmdstr.length) return 0; // this is what glibc seems to do (shell works test?)
  
        var cp = require('node:child_process');
        var ret = cp.spawnSync(cmdstr, [], {shell:true, stdio:'inherit'});
  
        var _W_EXITCODE = (ret, sig) => ((ret) << 8 | (sig));
  
        // this really only can happen if process is killed by signal
        if (ret.status === null) {
          // sadly node doesn't expose such function
          var signalToNumber = (sig) => {
            // implement only the most common ones, and fallback to SIGINT
            switch (sig) {
              case 'SIGHUP': return 1;
              case 'SIGQUIT': return 3;
              case 'SIGFPE': return 8;
              case 'SIGKILL': return 9;
              case 'SIGALRM': return 14;
              case 'SIGTERM': return 15;
              default: return 2;
            }
          }
          return _W_EXITCODE(0, signalToNumber(ret.signal));
        }
  
        return _W_EXITCODE(ret.status, 0);
      }
      // int system(const char *command);
      // http://pubs.opengroup.org/onlinepubs/000095399/functions/system.html
      // Can't call external programs.
      if (!command) return 0; // no shell available
      return -52;
    };

  var __emscripten_thread_cleanup = (thread) => {
      // Called when a thread needs to be cleaned up so it can be reused.
      // A thread is considered reusable when it either returns from its
      // entry point, calls pthread_exit, or acts upon a cancellation.
      // Detached threads are responsible for calling this themselves,
      // otherwise pthread_join is responsible for calling this.
      if (!ENVIRONMENT_IS_PTHREAD) cleanupThread(thread);
      else postMessage({ cmd: 6, thread });
    };


  var __emscripten_thread_set_strongref = (thread) => {
      // Called when a thread needs to be strongly referenced.
      // Currently only used for:
      // - keeping the "main" thread alive in PROXY_TO_PTHREAD mode;
      // - crashed threads that need to propagate the uncaught exception
      //   back to the main thread.
      if (ENVIRONMENT_IS_NODE) {
        var worker = PThread.pthreads[thread];
        worker.ref();
        // Also, record that we called strongref, in case this function is called
        // bafore the 'loaded' callback from the thread (where we would normally
        // `unref` it.
        worker.strongref = 1;
      }
    };

  var __emscripten_throw_longjmp = () => {
      throw new EmscriptenSjLj;
    };

  function __gmtime_js(time, tmPtr) {
    time = bigintToI53Checked(time);
  
  
      var date = new Date(time * 1000);
      if (isNaN(date.getTime())) {
        return 1;
      }
      HEAP32[((tmPtr)>>2)] = date.getUTCSeconds();
      HEAP32[(((tmPtr)+(4))>>2)] = date.getUTCMinutes();
      HEAP32[(((tmPtr)+(8))>>2)] = date.getUTCHours();
      HEAP32[(((tmPtr)+(12))>>2)] = date.getUTCDate();
      HEAP32[(((tmPtr)+(16))>>2)] = date.getUTCMonth();
      HEAP32[(((tmPtr)+(20))>>2)] = date.getUTCFullYear()-1900;
      HEAP32[(((tmPtr)+(24))>>2)] = date.getUTCDay();
      var start = Date.UTC(date.getUTCFullYear(), 0, 1, 0, 0, 0, 0);
      var yday = ((date.getTime() - start) / (1000 * 60 * 60 * 24))|0;
      HEAP32[(((tmPtr)+(28))>>2)] = yday;
      return 0;
    ;
  }

  var isLeapYear = (year) => year%4 === 0 && (year%100 !== 0 || year%400 === 0);
  
  var MONTH_DAYS_LEAP_CUMULATIVE = [0,31,60,91,121,152,182,213,244,274,305,335];
  
  var MONTH_DAYS_REGULAR_CUMULATIVE = [0,31,59,90,120,151,181,212,243,273,304,334];
  var ydayFromDate = (date) => {
      var leap = isLeapYear(date.getFullYear());
      var monthDaysCumulative = (leap ? MONTH_DAYS_LEAP_CUMULATIVE : MONTH_DAYS_REGULAR_CUMULATIVE);
      var yday = monthDaysCumulative[date.getMonth()] + date.getDate() - 1; // -1 since it's days since Jan 1
  
      return yday;
    };
  
  function __localtime_js(time, tmPtr) {
    time = bigintToI53Checked(time);
  
  
      var date = new Date(time*1000);
      if (isNaN(date.getTime())) {
        return 1;
      }
      HEAP32[((tmPtr)>>2)] = date.getSeconds();
      HEAP32[(((tmPtr)+(4))>>2)] = date.getMinutes();
      HEAP32[(((tmPtr)+(8))>>2)] = date.getHours();
      HEAP32[(((tmPtr)+(12))>>2)] = date.getDate();
      HEAP32[(((tmPtr)+(16))>>2)] = date.getMonth();
      HEAP32[(((tmPtr)+(20))>>2)] = date.getFullYear()-1900;
      HEAP32[(((tmPtr)+(24))>>2)] = date.getDay();
  
      var yday = ydayFromDate(date)|0;
      HEAP32[(((tmPtr)+(28))>>2)] = yday;
      HEAP32[(((tmPtr)+(36))>>2)] = -(date.getTimezoneOffset() * 60);
  
      // Attention: DST is in December in South, and some regions don't have DST at all.
      var start = new Date(date.getFullYear(), 0, 1);
      var summerOffset = new Date(date.getFullYear(), 6, 1).getTimezoneOffset();
      var winterOffset = start.getTimezoneOffset();
      var dst = (summerOffset != winterOffset && date.getTimezoneOffset() == Math.min(winterOffset, summerOffset))|0;
      HEAP32[(((tmPtr)+(32))>>2)] = dst;
      return 0;
    ;
  }

  
  var __mktime_js = function(tmPtr) {
  
  var ret = (() => { 
      var date = new Date(HEAP32[(((tmPtr)+(20))>>2)] + 1900,
                          HEAP32[(((tmPtr)+(16))>>2)],
                          HEAP32[(((tmPtr)+(12))>>2)],
                          HEAP32[(((tmPtr)+(8))>>2)],
                          HEAP32[(((tmPtr)+(4))>>2)],
                          HEAP32[((tmPtr)>>2)],
                          0);
      if (isNaN(date.getTime())) {
        return -1;
      }
  
      // There's an ambiguous hour when the time goes back; the tm_isdst field is
      // used to disambiguate it.  Date() basically guesses, so we fix it up if it
      // guessed wrong, or fill in tm_isdst with the guess if it's -1.
      var dst = HEAP32[(((tmPtr)+(32))>>2)];
      var guessedOffset = date.getTimezoneOffset();
      var start = new Date(date.getFullYear(), 0, 1);
      var summerOffset = new Date(date.getFullYear(), 6, 1).getTimezoneOffset();
      var winterOffset = start.getTimezoneOffset();
      var dstOffset = Math.min(winterOffset, summerOffset); // DST is in December in South
      if (dst < 0) {
        // Attention: some regions don't have DST at all.
        dst = Number(summerOffset != winterOffset && dstOffset == guessedOffset);
      } else if ((dst > 0) != (dstOffset == guessedOffset)) {
        var nonDstOffset = Math.max(winterOffset, summerOffset);
        var trueOffset = dst > 0 ? dstOffset : nonDstOffset;
        // Don't try setMinutes(date.getMinutes() + ...) -- it's messed up.
        date.setTime(date.getTime() + (trueOffset - guessedOffset)*60000);
        if (isNaN(date.getTime())) {
          return -1;
        }
      }
  
      HEAP32[(((tmPtr)+(32))>>2)] = dst;
      HEAP32[(((tmPtr)+(24))>>2)] = date.getDay();
      var yday = ydayFromDate(date)|0;
      HEAP32[(((tmPtr)+(28))>>2)] = yday;
      // To match expected behavior, update fields from date
      HEAP32[((tmPtr)>>2)] = date.getSeconds();
      HEAP32[(((tmPtr)+(4))>>2)] = date.getMinutes();
      HEAP32[(((tmPtr)+(8))>>2)] = date.getHours();
      HEAP32[(((tmPtr)+(12))>>2)] = date.getDate();
      HEAP32[(((tmPtr)+(16))>>2)] = date.getMonth();
      HEAP32[(((tmPtr)+(20))>>2)] = date.getYear();
  
      // Return time in seconds
      return date.getTime() / 1000;
     })();
  return BigInt(ret);
  };

  var timers = {
  };
  
  var clearTimers = () => {
      for (var t of Object.values(timers)) {
        clearTimeout(t.id);
      }
    };
  
  
  
  var _emscripten_get_now = () => performance.timeOrigin + performance.now();
  
  
  function __setitimer_js(which, timeout_ms) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(47, 0, 1, which, timeout_ms);
  
      // First, clear any existing timer.
      if (timers[which]) {
        clearTimeout(timers[which].id);
        delete timers[which];
      }
  
      // A timeout of zero simply cancels the current timeout so we have nothing
      // more to do.
      if (!timeout_ms) return 0;
  
      var id = setTimeout(() => {
        assert(which in timers);
        delete timers[which];
        callUserCallback(() => __emscripten_timeout(which, _emscripten_get_now()));
      }, timeout_ms);
      timers[which] = { id, timeout_ms };
      return 0;
    
  }
  

  
  var __tzset_js = (timezone, daylight, std_name, dst_name) => {
      // TODO: Use (malleable) environment variables instead of system settings.
      var currentYear = new Date().getFullYear();
      var winter = new Date(currentYear, 0, 1);
      var summer = new Date(currentYear, 6, 1);
      var winterOffset = winter.getTimezoneOffset();
      var summerOffset = summer.getTimezoneOffset();
  
      // Local standard timezone offset. Local standard time is not adjusted for
      // daylight savings.  This code uses the fact that getTimezoneOffset returns
      // a greater value during Standard Time versus Daylight Saving Time (DST).
      // Thus it determines the expected output during Standard Time, and it
      // compares whether the output of the given date the same (Standard) or less
      // (DST).
      var stdTimezoneOffset = Math.max(winterOffset, summerOffset);
  
      // timezone is specified as seconds west of UTC ("The external variable
      // `timezone` shall be set to the difference, in seconds, between
      // Coordinated Universal Time (UTC) and local standard time."), the same
      // as returned by stdTimezoneOffset.
      // See http://pubs.opengroup.org/onlinepubs/009695399/functions/tzset.html
      HEAPU32[((timezone)>>2)] = stdTimezoneOffset * 60;
  
      HEAP32[((daylight)>>2)] = Number(winterOffset != summerOffset);
  
      var extractZone = (timezoneOffset) => {
        // Why inverse sign?
        // Read here https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date/getTimezoneOffset
        var sign = timezoneOffset >= 0 ? "-" : "+";
  
        var absOffset = Math.abs(timezoneOffset)
        var hours = String(Math.floor(absOffset / 60)).padStart(2, "0");
        var minutes = String(absOffset % 60).padStart(2, "0");
  
        return `UTC${sign}${hours}${minutes}`;
      }
  
      var winterName = extractZone(winterOffset);
      var summerName = extractZone(summerOffset);
      assert(winterName);
      assert(summerName);
      assert(lengthBytesUTF8(winterName) <= 16, `timezone name truncated to fit in TZNAME_MAX (${winterName})`);
      assert(lengthBytesUTF8(summerName) <= 16, `timezone name truncated to fit in TZNAME_MAX (${summerName})`);
      if (summerOffset < winterOffset) {
        // Northern hemisphere
        stringToUTF8(winterName, std_name, 17);
        stringToUTF8(summerName, dst_name, 17);
      } else {
        stringToUTF8(winterName, dst_name, 17);
        stringToUTF8(summerName, std_name, 17);
      }
    };

  
  var _emscripten_date_now = () => Date.now();
  
  var nowIsMonotonic = 1;
  
  var checkWasiClock = (clock_id) => clock_id >= 0 && clock_id <= 3;
  
  function _clock_time_get(clk_id, ignored_precision, ptime) {
    ignored_precision = bigintToI53Checked(ignored_precision);
  
  
      if (!checkWasiClock(clk_id)) {
        return 28;
      }
      var now;
      // all wasi clocks but realtime are monotonic
      if (clk_id === 0) {
        now = _emscripten_date_now();
      } else if (nowIsMonotonic) {
        now = _emscripten_get_now();
      } else {
        return 52;
      }
      // "now" is in ms, and wasi times are in ns.
      var nsec = Math.round(now * 1000 * 1000);
      HEAP64[((ptime)>>3)] = BigInt(nsec);
      return 0;
    ;
  }

  
  var _emscripten_check_blocking_allowed = () => {
      if (ENVIRONMENT_IS_NODE) return;
  
      if (ENVIRONMENT_IS_WORKER) return; // Blocking in a worker/pthread is fine.
  
      warnOnce('Blocking on the main thread is very dangerous, see https://emscripten.org/docs/porting/pthreads.html#blocking-on-the-main-browser-thread');
  
    };


  var _emscripten_err = (str) => err(UTF8ToString(str));

  var runtimeKeepalivePush = () => {
      runtimeKeepaliveCounter += 1;
    };
  var _emscripten_exit_with_live_runtime = () => {
      runtimeKeepalivePush();
      throw 'unwind';
    };

  var getHeapMax = () =>
      HEAPU8.length;
  var _emscripten_get_heap_max = () => getHeapMax();


  var _emscripten_num_logical_cores = () =>
      ENVIRONMENT_IS_NODE ? require('node:os').cpus().length :
      navigator['hardwareConcurrency'];

  var abortOnCannotGrowMemory = (requestedSize) => {
      abort(`Cannot enlarge memory arrays to size ${requestedSize} bytes (OOM). Either (1) compile with -sINITIAL_MEMORY=X with X higher than the current value ${HEAP8.length}, (2) compile with -sALLOW_MEMORY_GROWTH which allows increasing the size at runtime, or (3) if you want malloc to return NULL (0) instead of this abort, compile with -sABORTING_MALLOC=0`);
    };
  var _emscripten_resize_heap = (requestedSize) => {
      var oldSize = HEAPU8.length;
      // With CAN_ADDRESS_2GB or MEMORY64, pointers are already unsigned.
      requestedSize >>>= 0;
      abortOnCannotGrowMemory(requestedSize);
    };

  var _emscripten_unwind_to_js_event_loop = () => {
      throw 'unwind';
    };

  var ENV = {
  };
  
  var getExecutableName = () => thisProgram;
  var getEnvStrings = () => {
      if (!getEnvStrings.strings) {
        // Default values.
        var lang = (globalThis.navigator?.language ?? 'C').replace('-', '_') + '.UTF-8';
        var env = {
          'USER': 'web_user',
          'LOGNAME': 'web_user',
          'PATH': '/',
          'PWD': '/',
          'HOME': '/home/web_user',
          'LANG': lang,
          '_': getExecutableName()
        };
        // Apply the user-provided values, if any.
        for (var x in ENV) {
          // x is a key in ENV; if ENV[x] is undefined, that means it was
          // explicitly set to be so. We allow user code to do that to
          // force variables with default values to remain unset.
          if (ENV[x] === undefined) delete env[x];
          else env[x] = ENV[x];
        }
        var strings = [];
        for (var x in env) {
          strings.push(`${x}=${env[x]}`);
        }
        getEnvStrings.strings = strings;
      }
      return getEnvStrings.strings;
    };
  
  
  
  function _environ_get(__environ, environ_buf) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(48, 0, 1, __environ, environ_buf);
  
      var bufSize = 0;
      var envp = 0;
      for (var string of getEnvStrings()) {
        var ptr = environ_buf + bufSize;
        HEAPU32[(((__environ)+(envp))>>2)] = ptr;
        bufSize += stringToUTF8(string, ptr, Infinity) + 1;
        envp += 4;
      }
      return 0;
    
  }
  

  
  
  
  function _environ_sizes_get(penviron_count, penviron_buf_size) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(49, 0, 1, penviron_count, penviron_buf_size);
  
      var strings = getEnvStrings();
      HEAPU32[((penviron_count)>>2)] = strings.length;
      var bufSize = 0;
      for (var string of strings) {
        bufSize += lengthBytesUTF8(string) + 1;
      }
      HEAPU32[((penviron_buf_size)>>2)] = bufSize;
      return 0;
    
  }
  


  
  
  function _fd_close(fd) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(50, 0, 1, fd);
  
  try {
  
      var stream = SYSCALLS.getStreamFromFD(fd);
      FS.close(stream);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  
  }
  

  
  
  function _fd_fdstat_get(fd, pbuf) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(51, 0, 1, fd, pbuf);
  
  try {
  
      var rightsBase = 0;
      var rightsInheriting = 0;
      var flags = 0;
      {
        var stream = SYSCALLS.getStreamFromFD(fd);
        // All character devices are terminals (other things a Linux system would
        // assume is a character device, like the mouse, we have special APIs for).
        var type = stream.tty ? 2 :
                   FS.isDir(stream.mode) ? 3 :
                   FS.isLink(stream.mode) ? 7 :
                   4;
      }
      HEAP8[pbuf] = type;
      HEAP16[(((pbuf)+(2))>>1)] = flags;
      HEAP64[(((pbuf)+(8))>>3)] = BigInt(rightsBase);
      HEAP64[(((pbuf)+(16))>>3)] = BigInt(rightsInheriting);
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  
  }
  

  /** @param {number=} offset */
  var doReadv = (stream, iov, iovcnt, offset) => {
      var ret = 0;
      for (var i = 0; i < iovcnt; i++) {
        var ptr = HEAPU32[((iov)>>2)];
        var len = HEAPU32[(((iov)+(4))>>2)];
        iov += 8;
        var curr = FS.read(stream, HEAP8, ptr, len, offset);
        if (curr < 0) return -1;
        ret += curr;
        if (curr < len) break; // nothing more to read
        if (typeof offset != 'undefined') {
          offset += curr;
        }
      }
      return ret;
    };
  
  
  
  
  function _fd_pread(fd, iov, iovcnt, offset, pnum) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(52, 0, 1, fd, iov, iovcnt, offset, pnum);
  
    offset = bigintToI53Checked(offset);
  
  
  try {
  
      if (isNaN(offset)) return 22;
      var stream = SYSCALLS.getStreamFromFD(fd)
      var num = doReadv(stream, iov, iovcnt, offset);
      HEAPU32[((pnum)>>2)] = num;
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  ;
  
  }
  

  /** @param {number=} offset */
  var doWritev = (stream, iov, iovcnt, offset) => {
      var ret = 0;
      for (var i = 0; i < iovcnt; i++) {
        var ptr = HEAPU32[((iov)>>2)];
        var len = HEAPU32[(((iov)+(4))>>2)];
        iov += 8;
        var curr = FS.write(stream, HEAP8, ptr, len, offset);
        if (curr < 0) return -1;
        ret += curr;
        if (curr < len) {
          // No more space to write.
          break;
        }
        if (typeof offset != 'undefined') {
          offset += curr;
        }
      }
      return ret;
    };
  
  
  
  
  function _fd_pwrite(fd, iov, iovcnt, offset, pnum) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(53, 0, 1, fd, iov, iovcnt, offset, pnum);
  
    offset = bigintToI53Checked(offset);
  
  
  try {
  
      if (isNaN(offset)) return 22;
      var stream = SYSCALLS.getStreamFromFD(fd)
      var num = doWritev(stream, iov, iovcnt, offset);
      HEAPU32[((pnum)>>2)] = num;
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  ;
  
  }
  

  
  
  
  function _fd_read(fd, iov, iovcnt, pnum) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(54, 0, 1, fd, iov, iovcnt, pnum);
  
  try {
  
      var stream = SYSCALLS.getStreamFromFD(fd);
      var num = doReadv(stream, iov, iovcnt);
      HEAPU32[((pnum)>>2)] = num;
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  
  }
  

  
  
  
  function _fd_seek(fd, offset, whence, newOffset) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(55, 0, 1, fd, offset, whence, newOffset);
  
    offset = bigintToI53Checked(offset);
  
  
  try {
  
      if (isNaN(offset)) return 22;
      var stream = SYSCALLS.getStreamFromFD(fd);
      FS.llseek(stream, offset, whence);
      HEAP64[((newOffset)>>3)] = BigInt(stream.position);
      if (stream.getdents && offset === 0 && whence === 0) stream.getdents = null; // reset readdir state
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  ;
  
  }
  

  
  var _fd_sync = 
  function(fd) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(56, 0, 2, fd);
  
  try {
  
      var stream = SYSCALLS.getStreamFromFD(fd);
      var rtn = stream.stream_ops?.fsync?.(stream);
      return new Promise((resolve) => {
        var mount = stream.node.mount;
        if (mount?.type.syncfs) {
          mount.type.syncfs(mount, false, (err) => resolve(err ? 29 : 0));
        } else {
          resolve(rtn);
        }
      });
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  
  }
  ;

  
  
  
  function _fd_write(fd, iov, iovcnt, pnum) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(57, 0, 1, fd, iov, iovcnt, pnum);
  
  try {
  
      var stream = SYSCALLS.getStreamFromFD(fd);
      var num = doWritev(stream, iov, iovcnt);
      HEAPU32[((pnum)>>2)] = num;
      return 0;
    } catch (e) {
    if (typeof FS == 'undefined' || !(e.name === 'ErrnoError')) throw e;
    return e.errno;
  }
  
  }
  

  
  
  
  
  
  
  
  
  
  
  function _getaddrinfo(node, service, hint, out) {
  if (ENVIRONMENT_IS_PTHREAD)
    return proxyToMainThread(58, 0, 1, node, service, hint, out);
  
      // Note getaddrinfo currently only returns a single addrinfo with ai_next defaulting to NULL. When NULL
      // hints are specified or ai_family set to AF_UNSPEC or ai_socktype or ai_protocol set to 0 then we
      // really should provide a linked list of suitable addrinfo values.
      var addrs = [];
      var canon = null;
      var addr = 0;
      var port = 0;
      var flags = 0;
      var family = 0;
      var type = 0;
      var proto = 0;
      var ai, last;
  
      function allocaddrinfo(family, type, proto, canon, addr, port) {
        var sa, salen, ai;
        var errno;
  
        salen = family === 10 ?
          28 :
          16;
        addr = family === 10 ?
          inetNtop6(addr) :
          inetNtop4(addr);
        sa = _malloc(salen);
        errno = writeSockaddr(sa, family, addr, port);
        assert(!errno);
  
        ai = _malloc(32);
        HEAP32[(((ai)+(4))>>2)] = family;
        HEAP32[(((ai)+(8))>>2)] = type;
        HEAP32[(((ai)+(12))>>2)] = proto;
        HEAPU32[(((ai)+(24))>>2)] = canon;
        HEAPU32[(((ai)+(20))>>2)] = sa;
        if (family === 10) {
          HEAP32[(((ai)+(16))>>2)] = 28;
        } else {
          HEAP32[(((ai)+(16))>>2)] = 16;
        }
        HEAP32[(((ai)+(28))>>2)] = 0;
  
        return ai;
      }
  
      if (hint) {
        flags = HEAP32[((hint)>>2)];
        family = HEAP32[(((hint)+(4))>>2)];
        type = HEAP32[(((hint)+(8))>>2)];
        proto = HEAP32[(((hint)+(12))>>2)];
      }
      if (type && !proto) {
        proto = type === 2 ? 17 : 6;
      }
      if (!type && proto) {
        type = proto === 17 ? 2 : 1;
      }
  
      // If type or proto are set to zero in hints we should really be returning multiple addrinfo values, but for
      // now default to a TCP STREAM socket so we can at least return a sensible addrinfo given NULL hints.
      if (proto === 0) {
        proto = 6;
      }
      if (type === 0) {
        type = 1;
      }
  
      if (!node && !service) {
        return -2;
      }
      if (flags & ~(1|2|4|
          1024|8|16|32)) {
        return -1;
      }
      if (hint !== 0 && (HEAP32[((hint)>>2)] & 2) && !node) {
        return -1;
      }
      if (flags & 32) {
        // TODO
        return -2;
      }
      if (type !== 0 && type !== 1 && type !== 2) {
        return -7;
      }
      if (family !== 0 && family !== 2 && family !== 10) {
        return -6;
      }
  
      if (service) {
        service = UTF8ToString(service);
        port = parseInt(service, 10);
  
        if (isNaN(port)) {
          if (flags & 1024) {
            return -2;
          }
          // TODO support resolving well-known service names from:
          // http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.txt
          return -8;
        }
      }
  
      if (!node) {
        if (family === 0) {
          family = 2;
        }
        if ((flags & 1) === 0) {
          if (family === 2) {
            addr = _htonl(2130706433);
          } else {
            addr = [0, 0, 0, _htonl(1)];
          }
        }
        ai = allocaddrinfo(family, type, proto, null, addr, port);
        HEAPU32[((out)>>2)] = ai;
        return 0;
      }
  
      //
      // try as a numeric address
      //
      node = UTF8ToString(node);
      addr = inetPton4(node);
      if (addr !== null) {
        // incoming node is a valid ipv4 address
        if (family === 0 || family === 2) {
          family = 2;
        }
        else if (family === 10 && (flags & 8)) {
          addr = [0, 0, _htonl(0xffff), addr];
          family = 10;
        } else {
          return -2;
        }
      } else {
        addr = inetPton6(node);
        if (addr !== null) {
          // incoming node is a valid ipv6 address
          if (family === 0 || family === 10) {
            family = 10;
          } else {
            return -2;
          }
        }
      }
      if (addr != null) {
        ai = allocaddrinfo(family, type, proto, node, addr, port);
        HEAPU32[((out)>>2)] = ai;
        return 0;
      }
      if (flags & 4) {
        return -2;
      }
  
      //
      // try as a hostname
      //
      // resolve the hostname to a temporary fake address
      node = DNS.lookup_name(node);
      addr = inetPton4(node);
      if (family === 0) {
        family = 2;
      } else if (family === 10) {
        addr = [0, 0, _htonl(0xffff), addr];
      }
      ai = allocaddrinfo(family, type, proto, null, addr, port);
      HEAPU32[((out)>>2)] = ai;
      return 0;
    
  }
  

  
  
  var _getnameinfo = (sa, salen, node, nodelen, serv, servlen, flags) => {
      var info = readSockaddr(sa, salen);
      if (info.errno) {
        return -6;
      }
      var port = info.port;
      var addr = info.addr;
  
      var overflowed = false;
  
      if (node && nodelen) {
        var lookup;
        if ((flags & 1) || !(lookup = DNS.lookup_addr(addr))) {
          if (flags & 8) {
            return -2;
          }
        } else {
          addr = lookup;
        }
        var numBytesWrittenExclNull = stringToUTF8(addr, node, nodelen);
  
        if (numBytesWrittenExclNull+1 >= nodelen) {
          overflowed = true;
        }
      }
  
      if (serv && servlen) {
        port = '' + port;
        var numBytesWrittenExclNull = stringToUTF8(port, serv, servlen);
  
        if (numBytesWrittenExclNull+1 >= servlen) {
          overflowed = true;
        }
      }
  
      if (overflowed) {
        // Note: even when we overflow, getnameinfo() is specced to write out the truncated results.
        return -12;
      }
  
      return 0;
    };

  var Protocols = {
  list:[],
  map:{
  },
  };
  
  var stringToAscii = (str, buffer) => {
      for (var i = 0; i < str.length; ++i) {
        assert(str.charCodeAt(i) === (str.charCodeAt(i) & 0xff));
        HEAP8[buffer++] = str.charCodeAt(i);
      }
      // Null-terminate the string
      HEAP8[buffer] = 0;
    };
  
  var _setprotoent = (stayopen) => {
      // void setprotoent(int stayopen);
  
      // Allocate and populate a protoent structure given a name, protocol number and array of aliases
      function allocprotoent(name, proto, aliases) {
        // write name into buffer
        var nameBuf = _malloc(name.length + 1);
        stringToAscii(name, nameBuf);
  
        // write aliases into buffer
        var j = 0;
        var length = aliases.length;
        var aliasListBuf = _malloc((length + 1) * 4); // Use length + 1 so we have space for the terminating NULL ptr.
  
        for (var i = 0; i < length; i++, j += 4) {
          var alias = aliases[i];
          var aliasBuf = _malloc(alias.length + 1);
          stringToAscii(alias, aliasBuf);
          HEAPU32[(((aliasListBuf)+(j))>>2)] = aliasBuf;
        }
        HEAPU32[(((aliasListBuf)+(j))>>2)] = 0; // Terminating NULL pointer.
  
        // generate protoent
        var pe = _malloc(12);
        HEAPU32[((pe)>>2)] = nameBuf;
        HEAPU32[(((pe)+(4))>>2)] = aliasListBuf;
        HEAP32[(((pe)+(8))>>2)] = proto;
        return pe;
      };
  
      // Populate the protocol 'database'. The entries are limited to tcp and udp, though it is fairly trivial
      // to add extra entries from /etc/protocols if desired - though not sure if that'd actually be useful.
      var list = Protocols.list;
      var map  = Protocols.map;
      if (list.length === 0) {
          var entry = allocprotoent('tcp', 6, ['TCP']);
          list.push(entry);
          map['tcp'] = map['6'] = entry;
          entry = allocprotoent('udp', 17, ['UDP']);
          list.push(entry);
          map['udp'] = map['17'] = entry;
      }
  
      _setprotoent.index = 0;
    };
  
  
  var _getprotobyname = (name) => {
      // struct protoent *getprotobyname(const char *);
      name = UTF8ToString(name);
      _setprotoent(true);
      var result = Protocols.map[name];
      return result;
    };

  
  var _getprotobynumber = (number) => {
      // struct protoent *getprotobynumber(int proto);
      _setprotoent(true);
      var result = Protocols.map[number];
      return result;
    };













  var getCFunc = (ident) => {
      var func = Module['_' + ident]; // closure exported function
      assert(func, `Cannot call unknown function ${ident}, make sure it is exported`);
      return func;
    };
  
  var writeArrayToMemory = (array, buffer) => {
      assert(array.length >= 0, 'writeArrayToMemory array must have a length (should be an array or typed array)')
      HEAP8.set(array, buffer);
    };
  
  
  
  var stringToUTF8OnStack = (str) => {
      var size = lengthBytesUTF8(str) + 1;
      var ret = stackAlloc(size);
      stringToUTF8(str, ret, size);
      return ret;
    };
  
  
  
  
  
    /**
   * @param {string|null=} returnType
   * @param {Array=} argTypes
   * @param {Array=} args
   * @param {Object=} opts
   */
  var ccall = (ident, returnType, argTypes, args, opts) => {
      // For fast lookup of conversion functions
      var toC = {
        'string': (str) => {
          var ret = 0;
          if (str !== null && str !== undefined && str !== 0) { // null string
            ret = stringToUTF8OnStack(str);
          }
          return ret;
        },
        'array': (arr) => {
          var ret = stackAlloc(arr.length);
          writeArrayToMemory(arr, ret);
          return ret;
        }
      };
  
      function convertReturnValue(ret) {
        if (returnType === 'string') {
          return UTF8ToString(ret);
        }
        if (returnType === 'boolean') return Boolean(ret);
        return ret;
      }
  
      var func = getCFunc(ident);
      var cArgs = [];
      var stack = 0;
      assert(returnType !== 'array', 'return type should not be "array"');
      if (args) {
        for (var i = 0; i < args.length; i++) {
          var converter = toC[argTypes[i]];
          if (converter) {
            if (stack === 0) stack = stackSave();
            cArgs[i] = converter(args[i]);
          } else {
            cArgs[i] = args[i];
          }
        }
      }
      var ret = func(...cArgs);
      function onDone(ret) {
        if (stack !== 0) stackRestore(stack);
        return convertReturnValue(ret);
      }
  
      ret = onDone(ret);
      return ret;
    };

  
    /**
   * @param {string=} returnType
   * @param {Array=} argTypes
   * @param {Object=} opts
   */
  var cwrap = (ident, returnType, argTypes, opts) => {
      return (...args) => ccall(ident, returnType, argTypes, args, opts);
    };

  var FS_createPath = (...args) => FS.createPath(...args);



  var FS_unlink = (...args) => FS.unlink(...args);

  var FS_createLazyFile = (...args) => FS.createLazyFile(...args);

  var FS_createDevice = (...args) => FS.createDevice(...args);


PThread.init();;

  FS.createPreloadedFile = FS_createPreloadedFile;
  FS.preloadFile = FS_preloadFile;
  FS.staticInit();;
// End JS library code

// include: postlibrary.js
// This file is included after the automatically-generated JS library code
// but before the wasm module is created.

{
  // With WASM_ESM_INTEGRATION this has to happen at the top level and not
  // delayed until processModuleArgs.
  initMemory();

  // Begin ATMODULES hooks
  if (Module['noExitRuntime']) noExitRuntime = Module['noExitRuntime'];

if (Module['print']) out = Module['print'];
if (Module['printErr']) err = Module['printErr'];
  // End ATMODULES hooks

  checkIncomingModuleAPI();

  if (Module['arguments']) programArgs = Module['arguments'];
  if (Module['thisProgram']) thisProgram = Module['thisProgram'];

  // Assertions on removed incoming Module JS APIs.
  assert(typeof Module['memoryInitializerPrefixURL'] == 'undefined', 'Module.memoryInitializerPrefixURL option was removed, use Module.locateFile instead');
  assert(typeof Module['pthreadMainPrefixURL'] == 'undefined', 'Module.pthreadMainPrefixURL option was removed, use Module.locateFile instead');
  assert(typeof Module['cdInitializerPrefixURL'] == 'undefined', 'Module.cdInitializerPrefixURL option was removed, use Module.locateFile instead');
  assert(typeof Module['filePackagePrefixURL'] == 'undefined', 'Module.filePackagePrefixURL option was removed, use Module.locateFile instead');
  assert(typeof Module['read'] == 'undefined', 'Module.read option was removed');
  assert(typeof Module['readAsync'] == 'undefined', 'Module.readAsync option was removed (modify readAsync in JS)');
  assert(typeof Module['readBinary'] == 'undefined', 'Module.readBinary option was removed (modify readBinary in JS)');
  assert(typeof Module['setWindowTitle'] == 'undefined', 'Module.setWindowTitle option was removed (modify emscripten_set_window_title in JS)');
  assert(typeof Module['TOTAL_MEMORY'] == 'undefined', 'Module.TOTAL_MEMORY has been renamed Module.INITIAL_MEMORY');
  assert(typeof Module['ENVIRONMENT'] == 'undefined', 'Module.ENVIRONMENT has been deprecated. To force the environment, use the ENVIRONMENT compile-time option (for example, -sENVIRONMENT=web or -sENVIRONMENT=node)');
  assert(typeof Module['STACK_SIZE'] == 'undefined', 'STACK_SIZE can no longer be set at runtime.  Use -sSTACK_SIZE at link time')

  var preInit = Module['preInit'];
  if (preInit) {
    if (typeof preInit == 'function') Module['preInit'] = preInit = [preInit];
    // Written as a loop so that preInit functions that themselves add more
    // preInit functions.  Is this actually needed?
    while (preInit.length > 0) {
      preInit.shift()();
    }
  }
  consumedModuleProp('preInit');
}

// Begin runtime exports
  Module['ENV'] = ENV;
  Module['addRunDependency'] = addRunDependency;
  Module['removeRunDependency'] = removeRunDependency;
  Module['ccall'] = ccall;
  Module['cwrap'] = cwrap;
  Module['setValue'] = setValue;
  Module['getValue'] = getValue;
  Module['UTF8ToString'] = UTF8ToString;
  Module['stringToUTF8'] = stringToUTF8;
  Module['lengthBytesUTF8'] = lengthBytesUTF8;
  Module['FS_preloadFile'] = FS_preloadFile;
  Module['FS_unlink'] = FS_unlink;
  Module['FS_createPath'] = FS_createPath;
  Module['FS_createDevice'] = FS_createDevice;
  Module['FS'] = FS;
  Module['FS_createDataFile'] = FS_createDataFile;
  Module['FS_createLazyFile'] = FS_createLazyFile;
  Module['IDBFS'] = IDBFS;
  var missingLibrarySymbols = [
  'writeI53ToI64',
  'writeI53ToI64Clamped',
  'writeI53ToI64Signaling',
  'writeI53ToU64Clamped',
  'writeI53ToU64Signaling',
  'readI53FromU64',
  'convertI32PairToI53',
  'convertI32PairToI53Checked',
  'convertU32PairToI53',
  'getTempRet0',
  'createNamedFunction',
  'growMemory',
  'withStackSave',
  'readEmAsmArgs',
  'jstoi_q',
  'autoResumeAudioContext',
  'getDynCaller',
  'dynCall',
  'runtimeKeepalivePop',
  'asmjsMangle',
  'alignMemory',
  'HandleAllocator',
  'addOnInit',
  'addOnPostCtor',
  'addOnPreMain',
  'addOnExit',
  'STACK_SIZE',
  'STACK_ALIGN',
  'POINTER_SIZE',
  'ASSERTIONS',
  'convertJsFunctionToWasm',
  'getEmptyTableSlot',
  'updateTableMap',
  'getFunctionAddress',
  'addFunction',
  'removeFunction',
  'intArrayToString',
  'AsciiToString',
  'UTF16ToString',
  'stringToUTF16',
  'lengthBytesUTF16',
  'UTF32ToString',
  'stringToUTF32',
  'lengthBytesUTF32',
  'stringToNewUTF8',
  'registerKeyEventCallback',
  'maybeCStringToJsString',
  'findEventTarget',
  'getBoundingClientRect',
  'fillMouseEventData',
  'registerMouseEventCallback',
  'registerWheelEventCallback',
  'registerUiEventCallback',
  'registerFocusEventCallback',
  'fillDeviceOrientationEventData',
  'registerDeviceOrientationEventCallback',
  'fillDeviceMotionEventData',
  'registerDeviceMotionEventCallback',
  'screenOrientation',
  'fillOrientationChangeEventData',
  'registerOrientationChangeEventCallback',
  'fillFullscreenChangeEventData',
  'registerFullscreenChangeEventCallback',
  'JSEvents_requestFullscreen',
  'JSEvents_resizeCanvasForFullscreen',
  'registerRestoreOldStyle',
  'hideEverythingExceptGivenElement',
  'restoreHiddenElements',
  'setLetterbox',
  'softFullscreenResizeWebGLRenderTarget',
  'doRequestFullscreen',
  'fillPointerlockChangeEventData',
  'registerPointerlockChangeEventCallback',
  'registerPointerlockErrorEventCallback',
  'requestPointerLock',
  'fillVisibilityChangeEventData',
  'registerVisibilityChangeEventCallback',
  'registerTouchEventCallback',
  'fillGamepadEventData',
  'registerGamepadEventCallback',
  'registerBeforeUnloadEventCallback',
  'fillBatteryEventData',
  'registerBatteryEventCallback',
  'setCanvasElementSizeCallingThread',
  'setCanvasElementSizeMainThread',
  'setCanvasElementSize',
  'getCanvasSizeCallingThread',
  'getCanvasSizeMainThread',
  'getCanvasElementSize',
  'jsStackTrace',
  'getCallstack',
  'convertPCtoSourceLocation',
  'wasiRightsToMuslOFlags',
  'wasiOFlagsToMuslOFlags',
  'safeSetTimeout',
  'setImmediateWrapped',
  'safeRequestAnimationFrame',
  'clearImmediateWrapped',
  'registerPostMainLoop',
  'registerPreMainLoop',
  'getPromise',
  'makePromise',
  'addPromise',
  'idsToPromises',
  'makePromiseCallback',
  'getExceptionMessageCommon',
  'incrementUncaughtExceptionCount',
  'decrementUncaughtExceptionCount',
  'incrementExceptionRefcount',
  'decrementExceptionRefcount',
  'getExceptionMessage',
  'Browser_asyncPrepareDataCounter',
  'arraySum',
  'addDays',
  'FS_mkdirTree',
  '_setNetworkCallback',
  'heapObjectForWebGLType',
  'toTypedArrayIndex',
  'webgl_enable_ANGLE_instanced_arrays',
  'webgl_enable_OES_vertex_array_object',
  'webgl_enable_WEBGL_draw_buffers',
  'webgl_enable_WEBGL_multi_draw',
  'webgl_enable_EXT_polygon_offset_clamp',
  'webgl_enable_EXT_clip_control',
  'webgl_enable_WEBGL_polygon_mode',
  'emscriptenWebGLGet',
  'computeUnpackAlignedImageSize',
  'colorChannelsInGlTextureFormat',
  'emscriptenWebGLGetTexPixelData',
  'emscriptenWebGLGetUniform',
  'webglGetProgramUniformLocation',
  'webglGetUniformLocation',
  'webglPrepareUniformLocationsBeforeFirstUse',
  'webglGetLeftBracePos',
  'emscriptenWebGLGetVertexAttrib',
  '__glGetActiveAttribOrUniform',
  'writeGLArray',
  'emscripten_webgl_destroy_context_before_on_calling_thread',
  'registerWebGlEventCallback',
  'runAndAbortIfError',
  'ALLOC_NORMAL',
  'ALLOC_STACK',
  'allocate',
  'writeStringToMemory',
  'writeAsciiToMemory',
  'allocateUTF8',
  'allocateUTF8OnStack',
  'demangle',
  'stackTrace',
  'getNativeTypeSize',
];
missingLibrarySymbols.forEach(missingLibrarySymbol)

  var unexportedSymbols = [
  'run',
  'out',
  'err',
  'callMain',
  'abort',
  'wasmExports',
  'writeStackCookie',
  'checkStackCookie',
  'readI53FromI64',
  'INT53_MAX',
  'INT53_MIN',
  'bigintToI53Checked',
  'HEAP8',
  'HEAP16',
  'HEAPU16',
  'HEAP32',
  'HEAPU32',
  'HEAPF32',
  'HEAPF64',
  'HEAP64',
  'HEAPU64',
  'stackSave',
  'stackRestore',
  'stackAlloc',
  'setTempRet0',
  'ptrToString',
  'zeroMemory',
  'exitJS',
  'getHeapMax',
  'abortOnCannotGrowMemory',
  'setStackLimits',
  'ERRNO_CODES',
  'strError',
  'inetPton4',
  'inetNtop4',
  'inetPton6',
  'inetNtop6',
  'readSockaddr',
  'writeSockaddr',
  'DNS',
  'Protocols',
  'Sockets',
  'timers',
  'warnOnce',
  'readEmAsmArgsArray',
  'getExecutableName',
  'handleException',
  'keepRuntimeAlive',
  'runtimeKeepalivePush',
  'callUserCallback',
  'maybeExit',
  'asyncLoad',
  'mmapAlloc',
  'wasmTable',
  'wasmMemory',
  'getUniqueRunDependency',
  'noExitRuntime',
  'addOnPreRun',
  'addOnPostRun',
  'freeTableIndexes',
  'functionsInTableMap',
  'PATH',
  'PATH_FS',
  'UTF8Decoder',
  'UTF8ArrayToString',
  'stringToUTF8Array',
  'intArrayFromString',
  'stringToAscii',
  'UTF16Decoder',
  'stringToUTF8OnStack',
  'writeArrayToMemory',
  'JSEvents',
  'specialHTMLTargets',
  'findCanvasEventTarget',
  'currentFullscreenStrategy',
  'restoreOldWindowedStyle',
  'UNWIND_CACHE',
  'ExitStatus',
  'getEnvStrings',
  'checkWasiClock',
  'doReadv',
  'doWritev',
  'initRandomFill',
  'randomFill',
  'emSetImmediate',
  'emClearImmediate_deps',
  'emClearImmediate',
  'promiseMap',
  'uncaughtExceptionCount',
  'exceptionLast',
  'exceptionCaught',
  'ExceptionInfo',
  'findMatchingCatch',
  'Browser',
  'requestFullscreen',
  'requestFullScreen',
  'setCanvasSize',
  'getUserMedia',
  'createContext',
  'getPreloadedImageData__data',
  'wget',
  'MONTH_DAYS_REGULAR',
  'MONTH_DAYS_LEAP',
  'MONTH_DAYS_REGULAR_CUMULATIVE',
  'MONTH_DAYS_LEAP_CUMULATIVE',
  'isLeapYear',
  'ydayFromDate',
  'SYSCALLS',
  'getSocketFromFD',
  'getSocketAddress',
  'preloadPlugins',
  'FS_createPreloadedFile',
  'FS_modeStringToFlags',
  'FS_getMode',
  'FS_fileDataToTypedArray',
  'FS_stdin_getChar_buffer',
  'FS_stdin_getChar',
  'FS_readFile',
  'FS_root',
  'FS_mounts',
  'FS_devices',
  'FS_streams',
  'FS_nextInode',
  'FS_nameTable',
  'FS_currentPath',
  'FS_initialized',
  'FS_ignorePermissions',
  'FS_filesystems',
  'FS_syncFSRequests',
  'FS_lookupPath',
  'FS_getPath',
  'FS_hashName',
  'FS_hashAddNode',
  'FS_hashRemoveNode',
  'FS_lookupNode',
  'FS_createNode',
  'FS_destroyNode',
  'FS_isRoot',
  'FS_isMountpoint',
  'FS_isFile',
  'FS_isDir',
  'FS_isLink',
  'FS_isChrdev',
  'FS_isBlkdev',
  'FS_isFIFO',
  'FS_isSocket',
  'FS_flagsToPermissionString',
  'FS_nodePermissions',
  'FS_mayLookup',
  'FS_mayCreate',
  'FS_mayDelete',
  'FS_mayOpen',
  'FS_checkOpExists',
  'FS_nextfd',
  'FS_getStreamChecked',
  'FS_getStream',
  'FS_createStream',
  'FS_closeStream',
  'FS_dupStream',
  'FS_doSetAttr',
  'FS_chrdev_stream_ops',
  'FS_major',
  'FS_minor',
  'FS_makedev',
  'FS_registerDevice',
  'FS_getDevice',
  'FS_getMounts',
  'FS_syncfs',
  'FS_mount',
  'FS_unmount',
  'FS_lookup',
  'FS_mknod',
  'FS_statfs',
  'FS_statfsStream',
  'FS_statfsNode',
  'FS_create',
  'FS_mkdir',
  'FS_mkdev',
  'FS_symlink',
  'FS_rename',
  'FS_rmdir',
  'FS_readdir',
  'FS_readlink',
  'FS_stat',
  'FS_fstat',
  'FS_lstat',
  'FS_doChmod',
  'FS_chmod',
  'FS_lchmod',
  'FS_fchmod',
  'FS_doChown',
  'FS_chown',
  'FS_lchown',
  'FS_fchown',
  'FS_doTruncate',
  'FS_truncate',
  'FS_ftruncate',
  'FS_utime',
  'FS_open',
  'FS_close',
  'FS_isClosed',
  'FS_llseek',
  'FS_read',
  'FS_write',
  'FS_mmap',
  'FS_msync',
  'FS_ioctl',
  'FS_writeFile',
  'FS_cwd',
  'FS_chdir',
  'FS_createDefaultDirectories',
  'FS_createDefaultDevices',
  'FS_createSpecialDirectories',
  'FS_createStandardStreams',
  'FS_staticInit',
  'FS_init',
  'FS_quit',
  'FS_findObject',
  'FS_analyzePath',
  'FS_createFile',
  'FS_forceLoadFile',
  'MEMFS',
  'TTY',
  'PIPEFS',
  'SOCKFS',
  'tempFixedLengthArray',
  'miniTempWebGLFloatBuffers',
  'miniTempWebGLIntBuffers',
  'GL',
  'AL',
  'GLUT',
  'EGL',
  'GLEW',
  'IDBStore',
  'SDL',
  'SDL_gfx',
  'waitAsyncPolyfilled',
  'print',
  'printErr',
  'jstoi_s',
  'PThread',
  'terminateWorker',
  'cleanupThread',
  'registerTLSInit',
  'spawnThread',
  'exitOnMainThread',
  'proxyToMainThread',
  'proxiedJSCallArgs',
  'invokeEntryPoint',
  'checkMailbox',
];
unexportedSymbols.forEach(unexportedRuntimeSymbol);

  // End runtime exports
  // Begin JS library exports
  // End JS library exports

// end include: postlibrary.js


// proxiedFunctionTable specifies the list of functions that can be called
// either synchronously or asynchronously from other threads in postMessage()d
// or internally queued events. This way a pthread in a Worker can synchronously
// access e.g. the DOM on the main thread.
var proxiedFunctionTable = [
  _proc_exit,
  exitOnMainThread,
  pthreadCreateProxied,
  ___syscall_accept4,
  ___syscall_bind,
  ___syscall_chdir,
  ___syscall_chmod,
  ___syscall_connect,
  ___syscall_dup,
  ___syscall_dup3,
  ___syscall_faccessat,
  ___syscall_fchmod,
  ___syscall_fchown32,
  ___syscall_fchownat,
  ___syscall_fcntl64,
  ___syscall_fstat64,
  ___syscall_ftruncate64,
  ___syscall_getcwd,
  ___syscall_getdents64,
  ___syscall_getpeername,
  ___syscall_getsockname,
  ___syscall_getsockopt,
  ___syscall_ioctl,
  ___syscall_listen,
  ___syscall_lstat64,
  ___syscall_mkdirat,
  ___syscall_newfstatat,
  ___syscall_openat,
  ___syscall_pipe2,
  ___syscall_poll,
  ___syscall_poll_nonblocking,
  ___syscall_readlinkat,
  ___syscall_recvfrom,
  ___syscall_recvmsg,
  ___syscall_renameat,
  ___syscall_rmdir,
  ___syscall_sendmsg,
  ___syscall_sendto,
  ___syscall_setsockopt,
  ___syscall_shutdown,
  ___syscall_socket,
  ___syscall_stat64,
  ___syscall_statfs64,
  ___syscall_symlinkat,
  ___syscall_umask,
  ___syscall_unlinkat,
  ___syscall_utimensat,
  __setitimer_js,
  _environ_get,
  _environ_sizes_get,
  _fd_close,
  _fd_fdstat_get,
  _fd_pread,
  _fd_pwrite,
  _fd_read,
  _fd_seek,
  _fd_sync,
  _fd_write,
  _getaddrinfo
];

function checkIncomingModuleAPI() {
  ignoredModuleProp('fetchSettings');
  ignoredModuleProp('logReadFiles');
  ignoredModuleProp('loadSplitModule');
  ignoredModuleProp('onMalloc');
  ignoredModuleProp('onRealloc');
  ignoredModuleProp('onFree');
  ignoredModuleProp('onSbrkGrow');
  ignoredModuleProp('onCOSCacheHit');
  ignoredModuleProp('onCOSCacheMiss');
  ignoredModuleProp('onCOSStore');
  ignoredModuleProp('GL_MAX_TEXTURE_IMAGE_UNITS');
  ignoredModuleProp('SDL_canPlayWithWebAudio');
  ignoredModuleProp('SDL_numSimultaneouslyQueuedBuffers');
  ignoredModuleProp('freePreloadedMediaOnUse');
  ignoredModuleProp('preinitializedWebGLContext');
  ignoredModuleProp('keyboardListeningElement');
  ignoredModuleProp('doNotCaptureKeyboard');
  ignoredModuleProp('extraStackTrace');
  ignoredModuleProp('preloadPlugins');
  ignoredModuleProp('preMainLoop');
  ignoredModuleProp('postMainLoop');
  ignoredModuleProp('forcedAspectRatio');
  ignoredModuleProp('mainScriptUrlOrBlob');
  ignoredModuleProp('onFullScreen');
  ignoredModuleProp('INITIAL_MEMORY');
  ignoredModuleProp('wasmMemory');
  ignoredModuleProp('wasmBinary');
}

// Imports from the Wasm binary.
var _free = Module['_free'] = makeInvalidEarlyAccess('_free');
var _malloc = Module['_malloc'] = makeInvalidEarlyAccess('_malloc');
var _fflush = makeInvalidEarlyAccess('_fflush');
var _strerror = makeInvalidEarlyAccess('_strerror');
var _nvim_main = Module['_nvim_main'] = makeInvalidEarlyAccess('_nvim_main');
var _ntohs = makeInvalidEarlyAccess('_ntohs');
var _uv_browser_get_shared_state_ptr = Module['_uv_browser_get_shared_state_ptr'] = makeInvalidEarlyAccess('_uv_browser_get_shared_state_ptr');
var _uv_browser_set_readable = Module['_uv_browser_set_readable'] = makeInvalidEarlyAccess('_uv_browser_set_readable');
var _uv_browser_set_writable = Module['_uv_browser_set_writable'] = makeInvalidEarlyAccess('_uv_browser_set_writable');
var _htons = makeInvalidEarlyAccess('_htons');
var _pthread_self = makeInvalidEarlyAccess('_pthread_self');
var _htonl = makeInvalidEarlyAccess('_htonl');
var __emscripten_tls_init = makeInvalidEarlyAccess('__emscripten_tls_init');
var __emscripten_thread_init = makeInvalidEarlyAccess('__emscripten_thread_init');
var ___set_thread_state = makeInvalidEarlyAccess('___set_thread_state');
var __emscripten_thread_crashed = makeInvalidEarlyAccess('__emscripten_thread_crashed');
var _emscripten_stack_get_end = makeInvalidEarlyAccess('_emscripten_stack_get_end');
var _emscripten_stack_get_base = makeInvalidEarlyAccess('_emscripten_stack_get_base');
var __emscripten_run_js_on_main_thread_done = makeInvalidEarlyAccess('__emscripten_run_js_on_main_thread_done');
var __emscripten_run_js_on_main_thread = makeInvalidEarlyAccess('__emscripten_run_js_on_main_thread');
var __emscripten_thread_free_data = makeInvalidEarlyAccess('__emscripten_thread_free_data');
var __emscripten_thread_exit = makeInvalidEarlyAccess('__emscripten_thread_exit');
var __emscripten_timeout = makeInvalidEarlyAccess('__emscripten_timeout');
var __emscripten_check_mailbox = makeInvalidEarlyAccess('__emscripten_check_mailbox');
var _setThrew = makeInvalidEarlyAccess('_setThrew');
var __emscripten_tempret_set = makeInvalidEarlyAccess('__emscripten_tempret_set');
var _emscripten_stack_init = makeInvalidEarlyAccess('_emscripten_stack_init');
var _emscripten_stack_set_limits = makeInvalidEarlyAccess('_emscripten_stack_set_limits');
var _emscripten_stack_get_free = makeInvalidEarlyAccess('_emscripten_stack_get_free');
var __emscripten_stack_restore = makeInvalidEarlyAccess('__emscripten_stack_restore');
var __emscripten_stack_alloc = makeInvalidEarlyAccess('__emscripten_stack_alloc');
var _emscripten_stack_get_current = makeInvalidEarlyAccess('_emscripten_stack_get_current');
var ___cxa_increment_exception_refcount = makeInvalidEarlyAccess('___cxa_increment_exception_refcount');
var ___cxa_decrement_exception_refcount = makeInvalidEarlyAccess('___cxa_decrement_exception_refcount');
var ___get_exception_message = makeInvalidEarlyAccess('___get_exception_message');
var ___cxa_can_catch = makeInvalidEarlyAccess('___cxa_can_catch');
var ___cxa_get_exception_ptr = makeInvalidEarlyAccess('___cxa_get_exception_ptr');
var ___set_stack_limits = Module['___set_stack_limits'] = makeInvalidEarlyAccess('___set_stack_limits');
var __indirect_function_table = makeInvalidEarlyAccess('__indirect_function_table');
var wasmTable = makeInvalidEarlyAccess('wasmTable');

function assignWasmExports(wasmExports) {
  assert(typeof wasmExports['free'] != 'undefined', 'missing Wasm export: free');
  assert(typeof wasmExports['malloc'] != 'undefined', 'missing Wasm export: malloc');
  assert(typeof wasmExports['fflush'] != 'undefined', 'missing Wasm export: fflush');
  assert(typeof wasmExports['strerror'] != 'undefined', 'missing Wasm export: strerror');
  assert(typeof wasmExports['nvim_main'] != 'undefined', 'missing Wasm export: nvim_main');
  assert(typeof wasmExports['ntohs'] != 'undefined', 'missing Wasm export: ntohs');
  assert(typeof wasmExports['uv_browser_get_shared_state_ptr'] != 'undefined', 'missing Wasm export: uv_browser_get_shared_state_ptr');
  assert(typeof wasmExports['uv_browser_set_readable'] != 'undefined', 'missing Wasm export: uv_browser_set_readable');
  assert(typeof wasmExports['uv_browser_set_writable'] != 'undefined', 'missing Wasm export: uv_browser_set_writable');
  assert(typeof wasmExports['htons'] != 'undefined', 'missing Wasm export: htons');
  assert(typeof wasmExports['pthread_self'] != 'undefined', 'missing Wasm export: pthread_self');
  assert(typeof wasmExports['htonl'] != 'undefined', 'missing Wasm export: htonl');
  assert(typeof wasmExports['_emscripten_tls_init'] != 'undefined', 'missing Wasm export: _emscripten_tls_init');
  assert(typeof wasmExports['_emscripten_thread_init'] != 'undefined', 'missing Wasm export: _emscripten_thread_init');
  assert(typeof wasmExports['__set_thread_state'] != 'undefined', 'missing Wasm export: __set_thread_state');
  assert(typeof wasmExports['_emscripten_thread_crashed'] != 'undefined', 'missing Wasm export: _emscripten_thread_crashed');
  assert(typeof wasmExports['emscripten_stack_get_end'] != 'undefined', 'missing Wasm export: emscripten_stack_get_end');
  assert(typeof wasmExports['emscripten_stack_get_base'] != 'undefined', 'missing Wasm export: emscripten_stack_get_base');
  assert(typeof wasmExports['_emscripten_run_js_on_main_thread_done'] != 'undefined', 'missing Wasm export: _emscripten_run_js_on_main_thread_done');
  assert(typeof wasmExports['_emscripten_run_js_on_main_thread'] != 'undefined', 'missing Wasm export: _emscripten_run_js_on_main_thread');
  assert(typeof wasmExports['_emscripten_thread_free_data'] != 'undefined', 'missing Wasm export: _emscripten_thread_free_data');
  assert(typeof wasmExports['_emscripten_thread_exit'] != 'undefined', 'missing Wasm export: _emscripten_thread_exit');
  assert(typeof wasmExports['_emscripten_timeout'] != 'undefined', 'missing Wasm export: _emscripten_timeout');
  assert(typeof wasmExports['_emscripten_check_mailbox'] != 'undefined', 'missing Wasm export: _emscripten_check_mailbox');
  assert(typeof wasmExports['setThrew'] != 'undefined', 'missing Wasm export: setThrew');
  assert(typeof wasmExports['_emscripten_tempret_set'] != 'undefined', 'missing Wasm export: _emscripten_tempret_set');
  assert(typeof wasmExports['emscripten_stack_init'] != 'undefined', 'missing Wasm export: emscripten_stack_init');
  assert(typeof wasmExports['emscripten_stack_set_limits'] != 'undefined', 'missing Wasm export: emscripten_stack_set_limits');
  assert(typeof wasmExports['emscripten_stack_get_free'] != 'undefined', 'missing Wasm export: emscripten_stack_get_free');
  assert(typeof wasmExports['_emscripten_stack_restore'] != 'undefined', 'missing Wasm export: _emscripten_stack_restore');
  assert(typeof wasmExports['_emscripten_stack_alloc'] != 'undefined', 'missing Wasm export: _emscripten_stack_alloc');
  assert(typeof wasmExports['emscripten_stack_get_current'] != 'undefined', 'missing Wasm export: emscripten_stack_get_current');
  assert(typeof wasmExports['__cxa_increment_exception_refcount'] != 'undefined', 'missing Wasm export: __cxa_increment_exception_refcount');
  assert(typeof wasmExports['__cxa_decrement_exception_refcount'] != 'undefined', 'missing Wasm export: __cxa_decrement_exception_refcount');
  assert(typeof wasmExports['__get_exception_message'] != 'undefined', 'missing Wasm export: __get_exception_message');
  assert(typeof wasmExports['__cxa_can_catch'] != 'undefined', 'missing Wasm export: __cxa_can_catch');
  assert(typeof wasmExports['__cxa_get_exception_ptr'] != 'undefined', 'missing Wasm export: __cxa_get_exception_ptr');
  assert(typeof wasmExports['__set_stack_limits'] != 'undefined', 'missing Wasm export: __set_stack_limits');
  assert(typeof wasmExports['__indirect_function_table'] != 'undefined', 'missing Wasm export: __indirect_function_table');
  _free = Module['_free'] = createExportWrapper('free', wasmExports['free'], 1);
  _malloc = Module['_malloc'] = createExportWrapper('malloc', wasmExports['malloc'], 1);
  _fflush = createExportWrapper('fflush', wasmExports['fflush'], 1);
  _strerror = createExportWrapper('strerror', wasmExports['strerror'], 1);
  _nvim_main = Module['_nvim_main'] = createExportWrapper('nvim_main', wasmExports['nvim_main'], 2);
  _ntohs = createExportWrapper('ntohs', wasmExports['ntohs'], 1);
  _uv_browser_get_shared_state_ptr = Module['_uv_browser_get_shared_state_ptr'] = createExportWrapper('uv_browser_get_shared_state_ptr', wasmExports['uv_browser_get_shared_state_ptr'], 0);
  _uv_browser_set_readable = Module['_uv_browser_set_readable'] = createExportWrapper('uv_browser_set_readable', wasmExports['uv_browser_set_readable'], 2);
  _uv_browser_set_writable = Module['_uv_browser_set_writable'] = createExportWrapper('uv_browser_set_writable', wasmExports['uv_browser_set_writable'], 2);
  _htons = createExportWrapper('htons', wasmExports['htons'], 1);
  _pthread_self = wasmExports['pthread_self'];
  _htonl = createExportWrapper('htonl', wasmExports['htonl'], 1);
  __emscripten_tls_init = createExportWrapper('_emscripten_tls_init', wasmExports['_emscripten_tls_init'], 0);
  __emscripten_thread_init = createExportWrapper('_emscripten_thread_init', wasmExports['_emscripten_thread_init'], 6);
  ___set_thread_state = createExportWrapper('__set_thread_state', wasmExports['__set_thread_state'], 4);
  __emscripten_thread_crashed = createExportWrapper('_emscripten_thread_crashed', wasmExports['_emscripten_thread_crashed'], 0);
  _emscripten_stack_get_end = wasmExports['emscripten_stack_get_end'];
  _emscripten_stack_get_base = wasmExports['emscripten_stack_get_base'];
  __emscripten_run_js_on_main_thread_done = createExportWrapper('_emscripten_run_js_on_main_thread_done', wasmExports['_emscripten_run_js_on_main_thread_done'], 3);
  __emscripten_run_js_on_main_thread = createExportWrapper('_emscripten_run_js_on_main_thread', wasmExports['_emscripten_run_js_on_main_thread'], 5);
  __emscripten_thread_free_data = createExportWrapper('_emscripten_thread_free_data', wasmExports['_emscripten_thread_free_data'], 1);
  __emscripten_thread_exit = createExportWrapper('_emscripten_thread_exit', wasmExports['_emscripten_thread_exit'], 1);
  __emscripten_timeout = createExportWrapper('_emscripten_timeout', wasmExports['_emscripten_timeout'], 2);
  __emscripten_check_mailbox = createExportWrapper('_emscripten_check_mailbox', wasmExports['_emscripten_check_mailbox'], 0);
  _setThrew = createExportWrapper('setThrew', wasmExports['setThrew'], 2);
  __emscripten_tempret_set = createExportWrapper('_emscripten_tempret_set', wasmExports['_emscripten_tempret_set'], 1);
  _emscripten_stack_init = wasmExports['emscripten_stack_init'];
  _emscripten_stack_set_limits = wasmExports['emscripten_stack_set_limits'];
  _emscripten_stack_get_free = wasmExports['emscripten_stack_get_free'];
  __emscripten_stack_restore = wasmExports['_emscripten_stack_restore'];
  __emscripten_stack_alloc = wasmExports['_emscripten_stack_alloc'];
  _emscripten_stack_get_current = wasmExports['emscripten_stack_get_current'];
  ___cxa_increment_exception_refcount = createExportWrapper('__cxa_increment_exception_refcount', wasmExports['__cxa_increment_exception_refcount'], 1);
  ___cxa_decrement_exception_refcount = createExportWrapper('__cxa_decrement_exception_refcount', wasmExports['__cxa_decrement_exception_refcount'], 1);
  ___get_exception_message = createExportWrapper('__get_exception_message', wasmExports['__get_exception_message'], 3);
  ___cxa_can_catch = createExportWrapper('__cxa_can_catch', wasmExports['__cxa_can_catch'], 3);
  ___cxa_get_exception_ptr = createExportWrapper('__cxa_get_exception_ptr', wasmExports['__cxa_get_exception_ptr'], 1);
  ___set_stack_limits = Module['___set_stack_limits'] = createExportWrapper('__set_stack_limits', wasmExports['__set_stack_limits'], 2);
  __indirect_function_table = wasmTable = wasmExports['__indirect_function_table'];
}

  var wasmImports;
  function assignWasmImports() {
    wasmImports = {
    /** @export */
    __assert_fail: ___assert_fail,
    /** @export */
    __call_sighandler: ___call_sighandler,
    /** @export */
    __cxa_begin_catch: ___cxa_begin_catch,
    /** @export */
    __cxa_find_matching_catch_2: ___cxa_find_matching_catch_2,
    /** @export */
    __cxa_find_matching_catch_3: ___cxa_find_matching_catch_3,
    /** @export */
    __handle_stack_overflow: ___handle_stack_overflow,
    /** @export */
    __pthread_create_js: ___pthread_create_js,
    /** @export */
    __resumeException: ___resumeException,
    /** @export */
    __syscall_accept4: ___syscall_accept4,
    /** @export */
    __syscall_bind: ___syscall_bind,
    /** @export */
    __syscall_chdir: ___syscall_chdir,
    /** @export */
    __syscall_chmod: ___syscall_chmod,
    /** @export */
    __syscall_connect: ___syscall_connect,
    /** @export */
    __syscall_dup: ___syscall_dup,
    /** @export */
    __syscall_dup3: ___syscall_dup3,
    /** @export */
    __syscall_faccessat: ___syscall_faccessat,
    /** @export */
    __syscall_fchmod: ___syscall_fchmod,
    /** @export */
    __syscall_fchown32: ___syscall_fchown32,
    /** @export */
    __syscall_fchownat: ___syscall_fchownat,
    /** @export */
    __syscall_fcntl64: ___syscall_fcntl64,
    /** @export */
    __syscall_fstat64: ___syscall_fstat64,
    /** @export */
    __syscall_ftruncate64: ___syscall_ftruncate64,
    /** @export */
    __syscall_getcwd: ___syscall_getcwd,
    /** @export */
    __syscall_getdents64: ___syscall_getdents64,
    /** @export */
    __syscall_getpeername: ___syscall_getpeername,
    /** @export */
    __syscall_getsockname: ___syscall_getsockname,
    /** @export */
    __syscall_getsockopt: ___syscall_getsockopt,
    /** @export */
    __syscall_ioctl: ___syscall_ioctl,
    /** @export */
    __syscall_listen: ___syscall_listen,
    /** @export */
    __syscall_lstat64: ___syscall_lstat64,
    /** @export */
    __syscall_mkdirat: ___syscall_mkdirat,
    /** @export */
    __syscall_newfstatat: ___syscall_newfstatat,
    /** @export */
    __syscall_openat: ___syscall_openat,
    /** @export */
    __syscall_pipe2: ___syscall_pipe2,
    /** @export */
    __syscall_poll: ___syscall_poll,
    /** @export */
    __syscall_poll_nonblocking: ___syscall_poll_nonblocking,
    /** @export */
    __syscall_readlinkat: ___syscall_readlinkat,
    /** @export */
    __syscall_recvfrom: ___syscall_recvfrom,
    /** @export */
    __syscall_recvmsg: ___syscall_recvmsg,
    /** @export */
    __syscall_renameat: ___syscall_renameat,
    /** @export */
    __syscall_rmdir: ___syscall_rmdir,
    /** @export */
    __syscall_sendmsg: ___syscall_sendmsg,
    /** @export */
    __syscall_sendto: ___syscall_sendto,
    /** @export */
    __syscall_setsockopt: ___syscall_setsockopt,
    /** @export */
    __syscall_shutdown: ___syscall_shutdown,
    /** @export */
    __syscall_socket: ___syscall_socket,
    /** @export */
    __syscall_stat64: ___syscall_stat64,
    /** @export */
    __syscall_statfs64: ___syscall_statfs64,
    /** @export */
    __syscall_symlinkat: ___syscall_symlinkat,
    /** @export */
    __syscall_umask: ___syscall_umask,
    /** @export */
    __syscall_unlinkat: ___syscall_unlinkat,
    /** @export */
    __syscall_utimensat: ___syscall_utimensat,
    /** @export */
    _abort_js: __abort_js,
    /** @export */
    _emscripten_init_main_thread_js: __emscripten_init_main_thread_js,
    /** @export */
    _emscripten_notify_mailbox_postmessage: __emscripten_notify_mailbox_postmessage,
    /** @export */
    _emscripten_receive_on_main_thread_js: __emscripten_receive_on_main_thread_js,
    /** @export */
    _emscripten_runtime_keepalive_clear: __emscripten_runtime_keepalive_clear,
    /** @export */
    _emscripten_system: __emscripten_system,
    /** @export */
    _emscripten_thread_cleanup: __emscripten_thread_cleanup,
    /** @export */
    _emscripten_thread_mailbox_await: __emscripten_thread_mailbox_await,
    /** @export */
    _emscripten_thread_set_strongref: __emscripten_thread_set_strongref,
    /** @export */
    _emscripten_throw_longjmp: __emscripten_throw_longjmp,
    /** @export */
    _gmtime_js: __gmtime_js,
    /** @export */
    _localtime_js: __localtime_js,
    /** @export */
    _mktime_js: __mktime_js,
    /** @export */
    _setitimer_js: __setitimer_js,
    /** @export */
    _tzset_js: __tzset_js,
    /** @export */
    clock_time_get: _clock_time_get,
    /** @export */
    emscripten_check_blocking_allowed: _emscripten_check_blocking_allowed,
    /** @export */
    emscripten_date_now: _emscripten_date_now,
    /** @export */
    emscripten_err: _emscripten_err,
    /** @export */
    emscripten_exit_with_live_runtime: _emscripten_exit_with_live_runtime,
    /** @export */
    emscripten_get_heap_max: _emscripten_get_heap_max,
    /** @export */
    emscripten_get_now: _emscripten_get_now,
    /** @export */
    emscripten_num_logical_cores: _emscripten_num_logical_cores,
    /** @export */
    emscripten_resize_heap: _emscripten_resize_heap,
    /** @export */
    emscripten_unwind_to_js_event_loop: _emscripten_unwind_to_js_event_loop,
    /** @export */
    environ_get: _environ_get,
    /** @export */
    environ_sizes_get: _environ_sizes_get,
    /** @export */
    exit: _exit,
    /** @export */
    fd_close: _fd_close,
    /** @export */
    fd_fdstat_get: _fd_fdstat_get,
    /** @export */
    fd_pread: _fd_pread,
    /** @export */
    fd_pwrite: _fd_pwrite,
    /** @export */
    fd_read: _fd_read,
    /** @export */
    fd_seek: _fd_seek,
    /** @export */
    fd_sync: _fd_sync,
    /** @export */
    fd_write: _fd_write,
    /** @export */
    getaddrinfo: _getaddrinfo,
    /** @export */
    getnameinfo: _getnameinfo,
    /** @export */
    getprotobyname: _getprotobyname,
    /** @export */
    getprotobynumber: _getprotobynumber,
    /** @export */
    invoke_ii,
    /** @export */
    invoke_iii,
    /** @export */
    invoke_vii,
    /** @export */
    invoke_viii,
    /** @export */
    invoke_viiii,
    /** @export */
    memory: wasmMemory,
    /** @export */
    proc_exit: _proc_exit
  };
  }

function invoke_vii(index,a1,a2) {
  var sp = stackSave();
  try {
    getWasmTableEntry(index)(a1,a2);
  } catch(e) {
    stackRestore(sp);
    if (!(e instanceof EmscriptenEH)) throw e;
    _setThrew(1, 0);
  }
}

function invoke_ii(index,a1) {
  var sp = stackSave();
  try {
    return getWasmTableEntry(index)(a1);
  } catch(e) {
    stackRestore(sp);
    if (!(e instanceof EmscriptenEH)) throw e;
    _setThrew(1, 0);
  }
}

function invoke_iii(index,a1,a2) {
  var sp = stackSave();
  try {
    return getWasmTableEntry(index)(a1,a2);
  } catch(e) {
    stackRestore(sp);
    if (!(e instanceof EmscriptenEH)) throw e;
    _setThrew(1, 0);
  }
}

function invoke_viiii(index,a1,a2,a3,a4) {
  var sp = stackSave();
  try {
    getWasmTableEntry(index)(a1,a2,a3,a4);
  } catch(e) {
    stackRestore(sp);
    if (!(e instanceof EmscriptenEH)) throw e;
    _setThrew(1, 0);
  }
}

function invoke_viii(index,a1,a2,a3) {
  var sp = stackSave();
  try {
    getWasmTableEntry(index)(a1,a2,a3);
  } catch(e) {
    stackRestore(sp);
    if (!(e instanceof EmscriptenEH)) throw e;
    _setThrew(1, 0);
  }
}


// include: postamble.js
// === Auto-generated postamble setup entry stuff ===

var calledRun;

function stackCheckInit() {
  // This is normally called automatically during __wasm_call_ctors but need to
  // get these values before even running any of the ctors so we call it redundantly
  // here.
  // See $establishStackSpace for the equivalent code that runs on a thread
  assert(!ENVIRONMENT_IS_PTHREAD);
  _emscripten_stack_init();
  // TODO(sbc): Move writeStackCookie to native to to avoid this.
  writeStackCookie();
}

async function run() {
  assert(!calledRun);
  calledRun = true;

  if ((ENVIRONMENT_IS_PTHREAD)) {
    initRuntime();
    return;
  }

  stackCheckInit();

  preRun();

  if (runDependencies) {
    await resolveRunDependencies();
  }

  var setStatus = Module['setStatus'];
  if (setStatus) {
    setStatus('Running...');
    // Yield to the event loop to allow the browser to paint "Running..."
    await new Promise((resolve) => setTimeout(resolve, 1));
    // Then we want to clear the status text, but only after the rest of this function runs.
    setTimeout(setStatus, 1, '');
  }

  if (ABORT) return;

  initRuntime();

  Module['onRuntimeInitialized']?.();
  consumedModuleProp('onRuntimeInitialized');

  assert(!Module['_main'], 'compiled without a main, but one is present. if you added it from JS, use Module["onRuntimeInitialized"]');

  postRun();
}

function checkUnflushedContent() {
  // Compiler settings do not allow exiting the runtime, so flushing
  // the streams is not possible. but in ASSERTIONS mode we check
  // if there was something to flush, and if so tell the user they
  // should request that the runtime be exitable.
  // Normally we would not even include flush() at all, but in ASSERTIONS
  // builds we do so just for this check, and here we see if there is any
  // content to flush, that is, we check if there would have been
  // something a non-ASSERTIONS build would have not seen.
  // How we flush the streams depends on whether we are in SYSCALLS_REQUIRE_FILESYSTEM=0
  // mode (which has its own special function for this; otherwise, all
  // the code is inside libc)
  var oldOut = out;
  var oldErr = err;
  var has = false;
  out = err = (x) => {
    has = true;
  }
  try { // it doesn't matter if it fails
    _fflush(0);
    // also flush in the JS FS layer
    for (var name of ['stdout', 'stderr']) {
      var info = FS.analyzePath('/dev/' + name);
      if (!info) return;
      var stream = info.object;
      var rdev = stream.rdev;
      var tty = TTY.ttys[rdev];
      if (tty?.output?.length) {
        has = true;
      }
    }
  } catch(e) {}
  out = oldOut;
  err = oldErr;
  if (has) {
    warnOnce('stdio streams had content in them that was not flushed. you should set EXIT_RUNTIME to 1 (see the Emscripten FAQ), or make sure to emit a newline when you printf etc.');
  }
}

var wasmExports;

if ((!(ENVIRONMENT_IS_PTHREAD))) {
// Call createWasm on startup if we are the main thread.
// Worker threads call this once they receive the module via postMessage

// In modularize mode the generated code is within a factory function so we
// can use await here (since it's not top-level-await).
wasmExports = await createWasm();
await run();

}

// end include: postamble.js

// include: postamble_modularize.js
// In MODULARIZE mode we wrap the generated code in a factory function
// and return either the Module itself, or a promise of the module.

// Assertion for attempting to access module properties on the incoming
// moduleArg.  In the past we used this object as the prototype of the module
// and assigned properties to it, but now we return a distinct object.  This
// keeps the instance private until it is ready (i.e the promise has been
// resolved).
for (const prop of Object.keys(Module)) {
  if (!(prop in moduleArg)) {
    Object.defineProperty(moduleArg, prop, {
      configurable: true,
      get() {
        abort(`Access to module property ('${prop}') is no longer possible via the module constructor argument; Instead, use the result of the module constructor.`)
      }
    });
  }
}
// end include: postamble_modularize.js



    return Module;
  };
})();

// Export using a UMD style export, or ES6 exports if selected
if (typeof exports === 'object' && typeof module === 'object') {
  module.exports = createNvim;
  // This default export looks redundant, but it allows TS to import this
  // commonjs style module.
  module.exports.default = createNvim;
} else if (typeof define === 'function' && define['amd'])
  define([], () => createNvim);

// Create code for detecting if we are running in a pthread.
// Normally this detection is done when the module is itself run but
// when running in MODULARIZE mode we need use this to know if we should
// run the module constructor on startup (true only for pthreads).
var isPthread = globalThis.name?.startsWith('em-pthread');
// In order to support both web and node we also need to detect node here.
var isNode = globalThis.process?.versions?.node && globalThis.process?.type != 'renderer';
if (isNode) isPthread = require('node:worker_threads').workerData === 'em-pthread'

isPthread && createNvim();

