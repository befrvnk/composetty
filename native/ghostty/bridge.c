// Composetty's narrow libghostty-vt bridge. The packed snapshot approach is inspired by
// Chuchu's Android Compose renderer: https://github.com/jossephus/chuchu
#include "bridge.h"

#include <ghostty/vt.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

struct ComposettyTerminal {
  GhosttyTerminal terminal;
  GhosttyRenderState render;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  GhosttyKeyEncoder encoder;
  GhosttyKeyEvent key_event;
  uint8_t *snapshot;
  size_t snapshot_capacity;
  uint8_t *selection;
  size_t selection_capacity;
  uint8_t *pty_writes;
  size_t pty_write_length;
  size_t pty_write_capacity;
};

static void append_pty_write(ComposettyTerminal *terminal, const uint8_t *data, size_t len) {
  if (len == 0) return;
  size_t needed = terminal->pty_write_length + len;
  if (needed > terminal->pty_write_capacity) {
    size_t capacity = terminal->pty_write_capacity == 0 ? 256 : terminal->pty_write_capacity;
    while (capacity < needed) capacity *= 2;
    uint8_t *next = realloc(terminal->pty_writes, capacity);
    if (next == NULL) return;
    terminal->pty_writes = next;
    terminal->pty_write_capacity = capacity;
  }
  memcpy(terminal->pty_writes + terminal->pty_write_length, data, len);
  terminal->pty_write_length += len;
}

static void write_pty(
    GhosttyTerminal ghostty_terminal, void *userdata, const uint8_t *data, size_t len) {
  (void)ghostty_terminal;
  append_pty_write((ComposettyTerminal *)userdata, data, len);
}

COMPOSETTY_API ComposettyTerminal *composetty_terminal_create(
    uint16_t cols, uint16_t rows, size_t max_scrollback) {
  ComposettyTerminal *result = calloc(1, sizeof(ComposettyTerminal));
  if (result == NULL) return NULL;
  if (ghostty_terminal_new(NULL, &result->terminal, cols, rows) != GHOSTTY_SUCCESS) goto fail;
  if (ghostty_render_state_new(NULL, &result->render) != GHOSTTY_SUCCESS) goto fail;
  if (ghostty_render_state_row_iterator_new(NULL, &result->rows) != GHOSTTY_SUCCESS) goto fail;
  if (ghostty_render_state_row_cells_new(NULL, &result->cells) != GHOSTTY_SUCCESS) goto fail;
  if (ghostty_key_encoder_new(NULL, &result->encoder) != GHOSTTY_SUCCESS) goto fail;
  if (ghostty_key_event_new(NULL, &result->key_event) != GHOSTTY_SUCCESS) goto fail;

  ghostty_terminal_set(result->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, result);
  ghostty_terminal_set(result->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, write_pty);
  ghostty_terminal_set(
      result->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &max_scrollback);
  return result;

fail:
  if (result->key_event) ghostty_key_event_free(result->key_event);
  if (result->encoder) ghostty_key_encoder_free(result->encoder);
  if (result->cells) ghostty_render_state_row_cells_free(result->cells);
  if (result->rows) ghostty_render_state_row_iterator_free(result->rows);
  if (result->render) ghostty_render_state_free(result->render);
  if (result->terminal) ghostty_terminal_free(result->terminal);
  free(result);
  return NULL;
}

COMPOSETTY_API void composetty_terminal_destroy(ComposettyTerminal *terminal) {
  if (terminal == NULL) return;
  ghostty_key_event_free(terminal->key_event);
  ghostty_key_encoder_free(terminal->encoder);
  ghostty_render_state_row_cells_free(terminal->cells);
  ghostty_render_state_row_iterator_free(terminal->rows);
  ghostty_render_state_free(terminal->render);
  ghostty_terminal_free(terminal->terminal);
  free(terminal->snapshot);
  free(terminal->selection);
  free(terminal->pty_writes);
  free(terminal);
}

COMPOSETTY_API int composetty_terminal_write(
    ComposettyTerminal *terminal, const uint8_t *data, size_t len) {
  if (terminal == NULL || (data == NULL && len > 0)) return GHOSTTY_INVALID_VALUE;
  ghostty_terminal_vt_write(terminal->terminal, data, len);
  return GHOSTTY_SUCCESS;
}

COMPOSETTY_API int composetty_terminal_resize(
    ComposettyTerminal *terminal,
    uint16_t cols,
    uint16_t rows,
    uint32_t cell_width,
    uint32_t cell_height) {
  if (terminal == NULL) return GHOSTTY_INVALID_VALUE;
  return ghostty_terminal_resize(terminal->terminal, cols, rows, cell_width, cell_height);
}

COMPOSETTY_API void composetty_terminal_scroll(ComposettyTerminal *terminal, intptr_t delta) {
  if (terminal == NULL) return;
  GhosttyTerminalScrollViewport scroll = {0};
  scroll.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
  scroll.value.delta = delta;
  ghostty_terminal_scroll_viewport(terminal->terminal, scroll);
}

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
    uint8_t cb) {
  if (terminal == NULL) return GHOSTTY_INVALID_VALUE;
  GhosttyColorRgb foreground = {fr, fg, fb};
  GhosttyColorRgb background = {br, bg, bb};
  GhosttyColorRgb cursor = {cr, cg, cb};
  if (ghostty_terminal_set(
          terminal->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &foreground) !=
      GHOSTTY_SUCCESS)
    return GHOSTTY_INVALID_VALUE;
  if (ghostty_terminal_set(
          terminal->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &background) !=
      GHOSTTY_SUCCESS)
    return GHOSTTY_INVALID_VALUE;
  return ghostty_terminal_set(
      terminal->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor);
}

static void write_u32_le(uint8_t *target, size_t offset, uint32_t value) {
  target[offset] = (uint8_t)value;
  target[offset + 1] = (uint8_t)(value >> 8);
  target[offset + 2] = (uint8_t)(value >> 16);
  target[offset + 3] = (uint8_t)(value >> 24);
}

COMPOSETTY_API const uint8_t *composetty_terminal_snapshot(
    ComposettyTerminal *terminal, size_t *out_size) {
  if (terminal == NULL || out_size == NULL) return NULL;
  if (ghostty_render_state_update(terminal->render, terminal->terminal) != GHOSTTY_SUCCESS)
    return NULL;

  uint16_t cols = 0, rows = 0, cursor_x = 0, cursor_y = 0;
  bool cursor_visible = false, cursor_in_viewport = false, cursor_wide = false;
  GhosttyRenderStateCursorVisualStyle cursor_style =
      GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;
  ghostty_render_state_get(terminal->render, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(terminal->render, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  ghostty_render_state_get(
      terminal->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE, &cursor_visible);
  ghostty_render_state_get(
      terminal->render,
      GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE,
      &cursor_in_viewport);
  if (cursor_in_viewport) {
    ghostty_render_state_get(
        terminal->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &cursor_x);
    ghostty_render_state_get(
        terminal->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &cursor_y);
    ghostty_render_state_get(
        terminal->render,
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL,
        &cursor_wide);
  }
  ghostty_render_state_get(
      terminal->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &cursor_style);

  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  if (ghostty_render_state_colors_get(terminal->render, &colors) != GHOSTTY_SUCCESS)
    return NULL;

  const size_t header_ints = 14;
  const size_t header_size = header_ints * sizeof(int32_t);
  const size_t cell_size = 16;
  const size_t cell_count = (size_t)cols * rows;
  if (cell_count > (SIZE_MAX - header_size) / cell_size) return NULL;
  const size_t grapheme_start = header_size + cell_count * cell_size;

  size_t grapheme_size = 0;
  if (ghostty_render_state_get(
          terminal->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &terminal->rows) !=
      GHOSTTY_SUCCESS)
    return NULL;
  while (ghostty_render_state_row_iterator_next(terminal->rows)) {
    if (ghostty_render_state_row_get(
            terminal->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &terminal->cells) !=
        GHOSTTY_SUCCESS)
      return NULL;
    while (ghostty_render_state_row_cells_next(terminal->cells)) {
      GhosttyBuffer grapheme = {0};
      GhosttyResult result = ghostty_render_state_row_cells_get(
          terminal->cells,
          GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
          &grapheme);
      if (result != GHOSTTY_SUCCESS && result != GHOSTTY_OUT_OF_SPACE) return NULL;
      if (grapheme.len > SIZE_MAX - grapheme_size) return NULL;
      grapheme_size += grapheme.len;
    }
  }
  if (grapheme_size > SIZE_MAX - grapheme_start) return NULL;
  const size_t required = grapheme_start + grapheme_size;
  if (required > INT32_MAX) return NULL;
  if (required > terminal->snapshot_capacity) {
    uint8_t *next = realloc(terminal->snapshot, required);
    if (next == NULL) return NULL;
    terminal->snapshot = next;
    terminal->snapshot_capacity = required;
  }
  memset(terminal->snapshot, 0, required);
  write_u32_le(terminal->snapshot, 0, cols);
  write_u32_le(terminal->snapshot, 4, rows);
  write_u32_le(terminal->snapshot, 8, cursor_x);
  write_u32_le(terminal->snapshot, 12, cursor_y);
  write_u32_le(terminal->snapshot, 16, cursor_visible && cursor_in_viewport);
  write_u32_le(terminal->snapshot, 20, cursor_style);
  write_u32_le(terminal->snapshot, 24, colors.background.r);
  write_u32_le(terminal->snapshot, 28, colors.background.g);
  write_u32_le(terminal->snapshot, 32, colors.background.b);
  write_u32_le(terminal->snapshot, 36, colors.foreground.r);
  write_u32_le(terminal->snapshot, 40, colors.foreground.g);
  write_u32_le(terminal->snapshot, 44, colors.foreground.b);
  write_u32_le(terminal->snapshot, 48, 2);
  write_u32_le(terminal->snapshot, 52, cursor_wide);

  if (ghostty_render_state_get(
          terminal->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &terminal->rows) !=
      GHOSTTY_SUCCESS)
    return NULL;
  size_t index = 0;
  size_t grapheme_offset = 0;
  while (ghostty_render_state_row_iterator_next(terminal->rows) &&
         index < (size_t)cols * rows) {
    if (ghostty_render_state_row_get(
            terminal->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &terminal->cells) !=
        GHOSTTY_SUCCESS)
      return NULL;
    GhosttyRenderStateRowSelection row_selection =
        GHOSTTY_INIT_SIZED(GhosttyRenderStateRowSelection);
    bool has_selection =
        ghostty_render_state_row_get(
            terminal->rows,
            GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION,
            &row_selection) == GHOSTTY_SUCCESS;
    uint16_t column = 0;
    while (ghostty_render_state_row_cells_next(terminal->cells) && index < cell_count) {
      const size_t base = header_size + index * cell_size;
      GhosttyBuffer grapheme = {
          .ptr = terminal->snapshot + grapheme_start + grapheme_offset,
          .cap = grapheme_size - grapheme_offset,
          .len = 0,
      };
      if (ghostty_render_state_row_cells_get(
              terminal->cells,
              GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
              &grapheme) != GHOSTTY_SUCCESS)
        return NULL;
      if (grapheme.len > grapheme_size - grapheme_offset) return NULL;

      GhosttyColorRgb foreground = colors.foreground;
      GhosttyColorRgb background = colors.background;
      GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
      GhosttyCell raw_cell = 0;
      GhosttyCellWide width = GHOSTTY_CELL_WIDE_NARROW;
      if (ghostty_render_state_row_cells_get(
              terminal->cells,
              GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
              &foreground) != GHOSTTY_SUCCESS)
        foreground = colors.foreground;
      if (ghostty_render_state_row_cells_get(
              terminal->cells,
              GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
              &background) != GHOSTTY_SUCCESS)
        background = colors.background;
      ghostty_render_state_row_cells_get(
          terminal->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
      if (ghostty_render_state_row_cells_get(
              terminal->cells,
              GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,
              &raw_cell) == GHOSTTY_SUCCESS)
        ghostty_cell_get(raw_cell, GHOSTTY_CELL_DATA_WIDE, &width);
      if (style.inverse) {
        GhosttyColorRgb swap = foreground;
        foreground = background;
        background = swap;
      }
      uint8_t flags = 0;
      if (style.bold) flags |= 1;
      if (style.italic) flags |= 2;
      if (style.underline != 0) flags |= 4;
      if (style.faint) flags |= 8;
      if (style.strikethrough) flags |= 16;
      if (has_selection && column >= row_selection.start_x && column <= row_selection.end_x)
        flags |= 32;
      write_u32_le(terminal->snapshot, base, (uint32_t)grapheme_offset);
      write_u32_le(terminal->snapshot, base + 4, (uint32_t)grapheme.len);
      terminal->snapshot[base + 8] = foreground.r;
      terminal->snapshot[base + 9] = foreground.g;
      terminal->snapshot[base + 10] = foreground.b;
      terminal->snapshot[base + 11] = background.r;
      terminal->snapshot[base + 12] = background.g;
      terminal->snapshot[base + 13] = background.b;
      terminal->snapshot[base + 14] = flags;
      terminal->snapshot[base + 15] = (uint8_t)width;
      grapheme_offset += grapheme.len;
      column++;
      index++;
    }
  }
  if (index != cell_count || grapheme_offset != grapheme_size) return NULL;

  *out_size = required;
  return terminal->snapshot;
}

COMPOSETTY_API size_t composetty_terminal_encode_key(
    ComposettyTerminal *terminal,
    int key,
    int action,
    uint16_t mods,
    uint32_t unshifted_codepoint,
    const char *utf8,
    size_t utf8_len,
    uint8_t *out,
    size_t out_capacity) {
  if (terminal == NULL || out == NULL) return 0;
  ghostty_key_encoder_setopt_from_terminal(terminal->encoder, terminal->terminal);
  GhosttyOptionAsAlt option_as_alt = GHOSTTY_OPTION_AS_ALT_TRUE;
  ghostty_key_encoder_setopt(
      terminal->encoder, GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT, &option_as_alt);
  ghostty_key_event_set_key(terminal->key_event, (GhosttyKey)key);
  ghostty_key_event_set_action(terminal->key_event, (GhosttyKeyAction)action);
  ghostty_key_event_set_mods(terminal->key_event, mods);
  ghostty_key_event_set_consumed_mods(terminal->key_event, 0);
  ghostty_key_event_set_unshifted_codepoint(terminal->key_event, unshifted_codepoint);
  ghostty_key_event_set_utf8(terminal->key_event, utf8, utf8_len);
  size_t written = 0;
  if (ghostty_key_encoder_encode(
          terminal->encoder,
          terminal->key_event,
          (char *)out,
          out_capacity,
          &written) != GHOSTTY_SUCCESS)
    return 0;
  return written;
}

COMPOSETTY_API size_t composetty_terminal_encode_paste(
    ComposettyTerminal *terminal,
    const uint8_t *data,
    size_t data_len,
    uint8_t *out,
    size_t out_capacity) {
  if (terminal == NULL || out == NULL || (data == NULL && data_len > 0)) return 0;
  if (data_len > SIZE_MAX - 12 || out_capacity < data_len + 12) return 0;

  GhosttyTerminalModeConfig mode = {
      .mode = GHOSTTY_MODE_BRACKETED_PASTE,
      .value = false,
  };
  if (ghostty_terminal_get(
          terminal->terminal, GHOSTTY_TERMINAL_DATA_MODE, &mode) != GHOSTTY_SUCCESS)
    return 0;

  char *mutable_data = NULL;
  if (data_len > 0) {
    mutable_data = malloc(data_len);
    if (mutable_data == NULL) return 0;
    memcpy(mutable_data, data, data_len);
  }
  size_t written = 0;
  GhosttyResult result = ghostty_paste_encode(
      mutable_data,
      data_len,
      mode.value,
      (char *)out,
      out_capacity,
      &written);
  free(mutable_data);
  return result == GHOSTTY_SUCCESS ? written : 0;
}

static GhosttyResult viewport_grid_ref(
    ComposettyTerminal *terminal, uint16_t column, uint16_t row, GhosttyGridRef *out) {
  GhosttyPoint point = {
      .tag = GHOSTTY_POINT_TAG_VIEWPORT,
      .value = { .coordinate = { .x = column, .y = row } },
  };
  *out = (GhosttyGridRef)GHOSTTY_INIT_SIZED(GhosttyGridRef);
  return ghostty_terminal_grid_ref(terminal->terminal, point, out);
}

COMPOSETTY_API int composetty_terminal_select(
    ComposettyTerminal *terminal,
    uint16_t start_column,
    uint16_t start_row,
    uint16_t end_column,
    uint16_t end_row) {
  if (terminal == NULL) return GHOSTTY_INVALID_VALUE;
  GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (viewport_grid_ref(terminal, start_column, start_row, &selection.start) !=
      GHOSTTY_SUCCESS)
    return GHOSTTY_INVALID_VALUE;
  if (viewport_grid_ref(terminal, end_column, end_row, &selection.end) !=
      GHOSTTY_SUCCESS)
    return GHOSTTY_INVALID_VALUE;
  return ghostty_terminal_set(
      terminal->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection);
}

COMPOSETTY_API void composetty_terminal_clear_selection(ComposettyTerminal *terminal) {
  if (terminal == NULL) return;
  ghostty_terminal_set(terminal->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
}

COMPOSETTY_API const uint8_t *composetty_terminal_selection(
    ComposettyTerminal *terminal, size_t *out_size) {
  if (terminal == NULL || out_size == NULL) return NULL;
  *out_size = 0;
  GhosttyTerminalSelectionFormatOptions options =
      GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
  options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  options.unwrap = true;
  options.trim = true;

  size_t required = 0;
  GhosttyResult result = ghostty_terminal_selection_format_buf(
      terminal->terminal, options, NULL, 0, &required);
  if (result == GHOSTTY_NO_VALUE) return NULL;
  if (result != GHOSTTY_OUT_OF_SPACE && result != GHOSTTY_SUCCESS) return NULL;
  if (required == 0) return terminal->selection;
  if (required > terminal->selection_capacity) {
    uint8_t *next = realloc(terminal->selection, required);
    if (next == NULL) return NULL;
    terminal->selection = next;
    terminal->selection_capacity = required;
  }
  size_t written = 0;
  if (ghostty_terminal_selection_format_buf(
          terminal->terminal,
          options,
          terminal->selection,
          terminal->selection_capacity,
          &written) != GHOSTTY_SUCCESS)
    return NULL;
  *out_size = written;
  return terminal->selection;
}

COMPOSETTY_API size_t composetty_terminal_drain_pty_writes(
    ComposettyTerminal *terminal, uint8_t *out, size_t out_capacity) {
  if (terminal == NULL || out == NULL || out_capacity == 0) return 0;
  size_t count =
      terminal->pty_write_length < out_capacity ? terminal->pty_write_length
                                                : out_capacity;
  memcpy(out, terminal->pty_writes, count);
  terminal->pty_write_length -= count;
  if (terminal->pty_write_length > 0)
    memmove(
        terminal->pty_writes,
        terminal->pty_writes + count,
        terminal->pty_write_length);
  return count;
}
