" Browser-safe subset of the local init.vim; plugin sources are pinned alongside it.
let g:sexp_enable_insert_mode_mappings = 0
let g:clojure_align_multiline_strings = 1
let g:clojure_align_subforms = 1
let g:airline_theme = 'bruvbox'
let g:airline_powerline_fonts = 1
let g:airline_left_sep = ''
let g:airline_left_alt_sep = ''
let g:airline_right_sep = ''
let g:airline_right_alt_sep = ''
let g:airline_skip_empty_sections = 1
let g:airline_inactive_collapse = 0
let g:airline_highlighting_cache = 1
let g:airline_extensions = ['tabline']
let g:airline#extensions#tabline#enabled = 1
let g:airline#extensions#tabline#fnamemod = ':t'
let g:NERDTreeShowHidden = 1
let g:NERDTreeWinSize = 28
let g:NERDTreeMinimalUI = 1
let g:NERDTreeHighlightCursorline = 1
let g:NERDTreeRespectWildIgnore = 1
let g:NERDTreeChDirMode = 2
let g:NERDTreeShowBookmarks = 1
let g:NERDTreeCascadeOpenSingleChildDir = 1
let g:NERDTreeCreatePrefix = 'silent keepjumps'
let g:webdevicons_conceal_nerdtree_brackets = 1
let g:WebDevIconsUnicodeDecorateFolderNodes = 1
let g:WebDevIconsUnicodeGlyphDoubleWidth = 1
let g:WebDevIconsNerdTreeAfterGlyphPadding = ' '
let g:rainbow#pairs = [['(', ')'], ['[', ']'], ['{', '}']]
let g:rainbow#auto = 1
let g:rainbow#max_level = 24
let g:rainbow#blacklist = ['vim', 'NERD_Tree_*', '#hex']
autocmd FileType lisp,clojure,fennel RainbowParentheses
nnoremap <Leader>n <Cmd>NERDTreeToggle<CR>
nnoremap <Leader>x <Cmd>Sayonara!<CR>
nnoremap <C-h> <C-w>h
nnoremap <C-j> <C-w>j
nnoremap <C-k> <C-w>k
nnoremap <C-l> <C-w>l
let g:sexp_insert_after_wrap =1
let g:sexp_mappings = {
    \ 'sexp_move_to_prev_bracket':        '(',   'sexp_move_to_next_bracket':        ')',
    \ 'sexp_move_to_prev_element_head': ',mk',   'sexp_move_to_next_element_head': ',mj',
    \ 'sexp_move_to_prev_element_tail': ',mh',   'sexp_move_to_next_element_tail': ',ml',
    \ 'sexp_move_to_prev_top_element':  ',mp',   'sexp_move_to_next_top_element':  ',mn',
    \ 'sexp_flow_to_prev_open':         ',fd',   'sexp_flow_to_next_open':         ',fs',
    \ 'sexp_flow_to_prev_close':        ',fa',   'sexp_flow_to_next_close':        ',ff',
    \ 'sexp_flow_to_prev_leaf_head':    ',fk',   'sexp_flow_to_next_leaf_head':    ',fj',
    \ 'sexp_flow_to_prev_leaf_tail':    ',fh',   'sexp_flow_to_next_leaf_tail':    ',fl',
    \ 'sexp_select_prev_element':       ',sk',   'sexp_select_next_element':       ',sj',
    \ 'sexp_indent':                     '++',
    \ 'sexp_indent_top':                 '+0',
    \ 'sexp_round_head_wrap_list':       ',8',   'sexp_round_tail_wrap_list':       ',(',
    \ 'sexp_square_head_wrap_list':      ',7',   'sexp_square_tail_wrap_list':      ',/',
    \ 'sexp_curly_head_wrap_list':       ',9',   'sexp_curly_tail_wrap_list':       ',)',
    \ 'sexp_round_head_wrap_element':   ',,8',   'sexp_round_tail_wrap_element':   ',,(',
    \ 'sexp_square_head_wrap_element':  ',,7',   'sexp_square_tail_wrap_element':  ',,/',
    \ 'sexp_curly_head_wrap_element':   ',,9',   'sexp_curly_tail_wrap_element':   ',,)',
    \ 'sexp_insert_at_list_head':        ',i',   'sexp_insert_at_list_tail':        ',a',
    \ 'sexp_splice_list':                ',2',
    \ 'sexp_convolute':                  ',+',
    \ 'sexp_raise_list':                 ',o',
    \ 'sexp_raise_element':              ',O',
    \ 'sexp_swap_list_backward':         ',k',   'sexp_swap_list_forward':          ',j',
    \ 'sexp_swap_element_backward':      ',h',   'sexp_swap_element_forward':       ',l',
    \ 'sexp_emit_head_element':         ',,j',   'sexp_emit_tail_element':         ',,k',
    \ 'sexp_capture_prev_element':      ',,h',   'sexp_capture_next_element':      ',,l',
    \ }
function! SexpInit() abort
    nmap <buffer> ,o     <Plug>(sexp_raise_list)++
endfunction

augroup sexp_mappings | autocmd!
  autocmd FileType clojure call SexpInit()
augroup END
