-- Browser subset of tolgraven/nvim-setup; no package managers or host commands.
vim.g.mapleader = ' '
vim.g.maplocalleader = ','
vim.opt.termguicolors = true
vim.opt.background = 'dark'
vim.opt.tabstop = 2
vim.opt.shiftwidth = 2
vim.opt.expandtab = true
vim.opt.ignorecase = true
vim.opt.smartcase = true
vim.opt.hidden = true
vim.opt.number = true
vim.opt.numberwidth = 3
vim.opt.cursorline = true
vim.opt.wrap = false
vim.opt.guicursor = 'n-c:block-Cursor,v:block-vCursor,i-ci-ve:ver25,r-cr:hor20,o:hor50'
vim.opt.splitbelow = true
vim.opt.splitright = true
vim.opt.timeoutlen = 370
vim.opt.swapfile = false
vim.g.bruvbox_number_column = 'bg0_h'
vim.g.bruvbox_sign_column = 'bg0_h2'
vim.g.bruvbox_color_column = 'bg0_h2'
vim.g.bruvbox_fold_column = 'bg0_h2'
vim.g.bruvbox_vert_split = 'bg3'
vim.g.bruvbox_cursorline_bg = 'bg0_m'
vim.g.bruvbox_italic = 1
vim.g.bruvbox_invert_selection = 1
vim.g.bruvbox_underline_cursorline = 0
local root = vim.fn.fnamemodify(debug.getinfo(1, 'S').source:sub(2), ':h')
vim.opt.runtimepath:prepend(root .. '/bruvbox')
vim.opt.runtimepath:prepend(root .. '/metabuffer')
local plugins = {'nerdtree', 'vim-devicons', 'vim-airline', 'vim-clojure-static', 'vim-sexp', 'rainbow_parentheses.vim', 'vim-cursorword', 'vim-numbertoggle', 'vim-sayonara', 'vim-repeat', 'vim-commentary', 'vim-textobj-user', 'vim-textobj-indent', 'vim-easy-align', 'vim-diminactive'}
for _, name in ipairs(plugins) do vim.opt.runtimepath:append(root .. '/' .. name) end
vim.opt.runtimepath:append(root .. '/after')
-- Undo files persist within this virtual session, never in the host config.
vim.fn.mkdir(root .. '/undo', 'p')
vim.opt.undodir = root .. '/undo'
vim.cmd('source ' .. root .. '/browser.vim')
vim.cmd('filetype plugin indent on')
vim.cmd('runtime! plugin/*.vim')
vim.cmd('syntax enable')
-- Keep rainbow everywhere except NERDTree, after its native syntax loading.
vim.api.nvim_create_autocmd({'Syntax', 'ColorScheme'}, {
  group = vim.api.nvim_create_augroup('browser_profile_rainbow', {clear = true}),
  callback = function()
    local ft = vim.bo.filetype
    if ft ~= 'nerdtree' then
      vim.fn['rainbow_parentheses#activate'](1)
    end
  end,
})
vim.cmd('colorscheme bruvbox')
-- Give the remote UI a cursor highlight distinct from MatchParen's reverse attr.
vim.api.nvim_set_hl(0, 'Cursor', {reverse = true})
-- cljlib expects the global module name; use Meta's pinned embedded Fennel.
package.preload.fennel = function() return require('metabuffer.nfnl.fennel') end
require('metabuffer').setup({
  ui = { animation = { enabled = false, backend = 'native', time_scale = 0.1 } },
})
vim.keymap.set('n', '#', '<Cmd>Meta<CR>')
vim.keymap.set('n', '<Leader>*', '<Cmd>MetaCursorWord<CR>')
vim.keymap.set('n', '<LocalLeader>me', '<Cmd>MetaReload<CR>')
