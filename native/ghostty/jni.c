#include "bridge.h"

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#define COMPOSETTY_JNI_CLASS "dev/befrvnk/composetty/AndroidGhosttyBindings"
#define COMPOSETTY_MAX_KEY_BYTES 256
#define COMPOSETTY_MAX_PTY_BYTES 4096
#define COMPOSETTY_JNI_ERROR -1

static ComposettyTerminal *terminal_from_handle(jlong handle) {
  return (ComposettyTerminal *)(intptr_t)handle;
}

static jbyteArray empty_byte_array(JNIEnv *env) {
  return (*env)->NewByteArray(env, 0);
}

static jbyteArray copy_bytes(JNIEnv *env, const uint8_t *bytes, size_t length) {
  if (bytes == NULL || length == 0) return empty_byte_array(env);
  if (length > INT32_MAX) return empty_byte_array(env);
  jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
  if (result == NULL) return NULL;
  (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)bytes);
  return result;
}

static jlong native_create(
    JNIEnv *env, jobject self, jint columns, jint rows, jlong max_scrollback) {
  (void)env;
  (void)self;
  if (columns <= 0 || columns > UINT16_MAX || rows <= 0 || rows > UINT16_MAX ||
      max_scrollback < 0)
    return 0;
  ComposettyTerminal *terminal = composetty_terminal_create(
      (uint16_t)columns, (uint16_t)rows, (size_t)max_scrollback);
  return (jlong)(intptr_t)terminal;
}

static void native_destroy(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  composetty_terminal_destroy(terminal_from_handle(handle));
}

static jint native_write(JNIEnv *env, jobject self, jlong handle, jbyteArray bytes) {
  (void)self;
  if (bytes == NULL) return COMPOSETTY_JNI_ERROR;
  jsize length = (*env)->GetArrayLength(env, bytes);
  jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (data == NULL && length > 0) return COMPOSETTY_JNI_ERROR;
  int result = composetty_terminal_write(
      terminal_from_handle(handle), (const uint8_t *)data, (size_t)length);
  if (data != NULL) (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
  return result;
}

static jint native_resize(
    JNIEnv *env,
    jobject self,
    jlong handle,
    jint columns,
    jint rows,
    jint cell_width,
    jint cell_height) {
  (void)env;
  (void)self;
  if (columns <= 0 || columns > UINT16_MAX || rows <= 0 || rows > UINT16_MAX ||
      cell_width <= 0 || cell_height <= 0)
    return COMPOSETTY_JNI_ERROR;
  return composetty_terminal_resize(
      terminal_from_handle(handle),
      (uint16_t)columns,
      (uint16_t)rows,
      (uint32_t)cell_width,
      (uint32_t)cell_height);
}

static void native_scroll(JNIEnv *env, jobject self, jlong handle, jlong rows) {
  (void)env;
  (void)self;
  composetty_terminal_scroll(terminal_from_handle(handle), (intptr_t)rows);
}

static jint native_set_colors(
    JNIEnv *env,
    jobject self,
    jlong handle,
    jint foreground_red,
    jint foreground_green,
    jint foreground_blue,
    jint background_red,
    jint background_green,
    jint background_blue,
    jint cursor_red,
    jint cursor_green,
    jint cursor_blue) {
  (void)env;
  (void)self;
  return composetty_terminal_set_colors(
      terminal_from_handle(handle),
      (uint8_t)foreground_red,
      (uint8_t)foreground_green,
      (uint8_t)foreground_blue,
      (uint8_t)background_red,
      (uint8_t)background_green,
      (uint8_t)background_blue,
      (uint8_t)cursor_red,
      (uint8_t)cursor_green,
      (uint8_t)cursor_blue);
}

static jbyteArray native_snapshot(JNIEnv *env, jobject self, jlong handle) {
  (void)self;
  size_t length = 0;
  const uint8_t *bytes = composetty_terminal_snapshot(terminal_from_handle(handle), &length);
  return copy_bytes(env, bytes, length);
}

static jbyteArray native_encode_key(
    JNIEnv *env,
    jobject self,
    jlong handle,
    jint key,
    jint action,
    jint modifiers,
    jint unshifted_codepoint,
    jbyteArray utf8) {
  (void)self;
  if (utf8 == NULL) return empty_byte_array(env);
  jsize utf8_length = (*env)->GetArrayLength(env, utf8);
  jbyte *utf8_bytes = (*env)->GetByteArrayElements(env, utf8, NULL);
  if (utf8_bytes == NULL && utf8_length > 0) return empty_byte_array(env);
  uint8_t output[COMPOSETTY_MAX_KEY_BYTES];
  size_t written = composetty_terminal_encode_key(
      terminal_from_handle(handle),
      key,
      action,
      (uint16_t)modifiers,
      (uint32_t)unshifted_codepoint,
      (const char *)utf8_bytes,
      (size_t)utf8_length,
      output,
      sizeof(output));
  if (utf8_bytes != NULL)
    (*env)->ReleaseByteArrayElements(env, utf8, utf8_bytes, JNI_ABORT);
  return copy_bytes(env, output, written);
}

static jbyteArray native_encode_paste(
    JNIEnv *env, jobject self, jlong handle, jbyteArray bytes) {
  (void)self;
  if (bytes == NULL) return empty_byte_array(env);
  jsize length = (*env)->GetArrayLength(env, bytes);
  if (length <= 0 || length > INT32_MAX - 12) return empty_byte_array(env);
  jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (data == NULL) return empty_byte_array(env);
  size_t output_capacity = (size_t)length + 12;
  uint8_t *output = malloc(output_capacity);
  if (output == NULL) {
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return empty_byte_array(env);
  }
  size_t written = composetty_terminal_encode_paste(
      terminal_from_handle(handle),
      (const uint8_t *)data,
      (size_t)length,
      output,
      output_capacity);
  (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
  jbyteArray result = copy_bytes(env, output, written);
  free(output);
  return result;
}

static jint native_select(
    JNIEnv *env,
    jobject self,
    jlong handle,
    jint start_column,
    jint start_row,
    jint end_column,
    jint end_row) {
  (void)env;
  (void)self;
  if (start_column < 0 || start_column > UINT16_MAX || start_row < 0 ||
      start_row > UINT16_MAX || end_column < 0 || end_column > UINT16_MAX ||
      end_row < 0 || end_row > UINT16_MAX)
    return COMPOSETTY_JNI_ERROR;
  return composetty_terminal_select(
      terminal_from_handle(handle),
      (uint16_t)start_column,
      (uint16_t)start_row,
      (uint16_t)end_column,
      (uint16_t)end_row);
}

static void native_clear_selection(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  composetty_terminal_clear_selection(terminal_from_handle(handle));
}

static jbyteArray native_selection(JNIEnv *env, jobject self, jlong handle) {
  (void)self;
  size_t length = 0;
  const uint8_t *bytes = composetty_terminal_selection(terminal_from_handle(handle), &length);
  return copy_bytes(env, bytes, length);
}

static jbyteArray native_drain_pty_writes(JNIEnv *env, jobject self, jlong handle) {
  (void)self;
  uint8_t output[COMPOSETTY_MAX_PTY_BYTES];
  size_t written = composetty_terminal_drain_pty_writes(
      terminal_from_handle(handle), output, sizeof(output));
  return copy_bytes(env, output, written);
}

static const JNINativeMethod methods[] = {
    {"create", "(IIJ)J", (void *)native_create},
    {"destroy", "(J)V", (void *)native_destroy},
    {"write", "(J[B)I", (void *)native_write},
    {"resize", "(JIIII)I", (void *)native_resize},
    {"scroll", "(JJ)V", (void *)native_scroll},
    {"setColors", "(JIIIIIIIII)I", (void *)native_set_colors},
    {"snapshot", "(J)[B", (void *)native_snapshot},
    {"encodeKey", "(JIIII[B)[B", (void *)native_encode_key},
    {"encodePaste", "(J[B)[B", (void *)native_encode_paste},
    {"select", "(JIIII)I", (void *)native_select},
    {"clearSelection", "(J)V", (void *)native_clear_selection},
    {"selection", "(J)[B", (void *)native_selection},
    {"drainPtyWrites", "(J)[B", (void *)native_drain_pty_writes},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass bindings = (*env)->FindClass(env, COMPOSETTY_JNI_CLASS);
  if (bindings == NULL) return JNI_ERR;
  jint result = (*env)->RegisterNatives(
      env, bindings, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
  (*env)->DeleteLocalRef(env, bindings);
  return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
