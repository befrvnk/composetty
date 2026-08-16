#ifndef COMPOSETTY_GHOSTTY_BRIDGE_H
#define COMPOSETTY_GHOSTTY_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define COMPOSETTY_API __declspec(dllexport)
#else
#define COMPOSETTY_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ComposettyTerminal ComposettyTerminal;

COMPOSETTY_API ComposettyTerminal *composetty_terminal_create(
    uint16_t cols, uint16_t rows, size_t max_scrollback);
COMPOSETTY_API void composetty_terminal_destroy(ComposettyTerminal *terminal);
COMPOSETTY_API int composetty_terminal_write(
    ComposettyTerminal *terminal, const uint8_t *data, size_t len);
COMPOSETTY_API int composetty_terminal_resize(
    ComposettyTerminal *terminal,
    uint16_t cols,
    uint16_t rows,
    uint32_t cell_width,
    uint32_t cell_height);
COMPOSETTY_API void composetty_terminal_scroll(ComposettyTerminal *terminal, intptr_t delta);
COMPOSETTY_API int composetty_terminal_set_colors(
    ComposettyTerminal *terminal,
    uint8_t fr,
    uint8_t fg,
    uint8_t fb,
    uint8_t br,
    uint8_t bg,
    uint8_t bb,
    uint8_t cr,
    uint8_t cg,
    uint8_t cb);
COMPOSETTY_API const uint8_t *composetty_terminal_snapshot(
    ComposettyTerminal *terminal, size_t *out_size);
COMPOSETTY_API size_t composetty_terminal_encode_key(
    ComposettyTerminal *terminal,
    int key,
    int action,
    uint16_t mods,
    uint32_t unshifted_codepoint,
    const char *utf8,
    size_t utf8_len,
    uint8_t *out,
    size_t out_capacity);
COMPOSETTY_API size_t composetty_terminal_encode_paste(
    ComposettyTerminal *terminal,
    const uint8_t *data,
    size_t data_len,
    uint8_t *out,
    size_t out_capacity);
COMPOSETTY_API int composetty_terminal_select(
    ComposettyTerminal *terminal,
    uint16_t start_column,
    uint16_t start_row,
    uint16_t end_column,
    uint16_t end_row);
COMPOSETTY_API void composetty_terminal_clear_selection(ComposettyTerminal *terminal);
COMPOSETTY_API const uint8_t *composetty_terminal_selection(
    ComposettyTerminal *terminal, size_t *out_size);
COMPOSETTY_API size_t composetty_terminal_drain_pty_writes(
    ComposettyTerminal *terminal, uint8_t *out, size_t out_capacity);

#ifdef __cplusplus
}
#endif

#endif
