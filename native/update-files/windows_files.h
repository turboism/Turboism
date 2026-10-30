/* Original read-only W2 core; licensed under the repository AGPL-3.0 license. */
#ifndef TURBOISM_WINDOWS_FILES_H
#define TURBOISM_WINDOWS_FILES_H
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <stdint.h>
typedef struct wf_root wf_root;
typedef struct wf_file wf_file;
typedef struct { DWORD win32; LONG ntstatus; const char *operation; } wf_error;
int wf_root_open(const WCHAR *path, size_t length, wf_root **out, wf_error *error);
void wf_root_close(wf_root *root);
int wf_root_identity(wf_root *root, unsigned char out[24], wf_error *error);
int wf_file_open(wf_root *root, const WCHAR *path, size_t length, uint64_t limit,
                 wf_file **out, wf_error *error);
int wf_file_read(wf_file *file, unsigned char *bytes, DWORD capacity, DWORD *read, wf_error *error);
int wf_file_finish(wf_file *file, wf_error *error);
void wf_file_close(wf_file *file);
uint64_t wf_file_size(wf_file *file);
#endif
