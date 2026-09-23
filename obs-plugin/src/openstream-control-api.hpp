#pragma once

#include <obs.h>
#include <stddef.h>

// UI-safe bridge used by the frontend dock. The source reference is used only
// during the call; queued work copies its network target and JSON body.
bool openstream_is_camera_source(obs_source_t *source);
bool openstream_post_camera_command(obs_source_t *source, const char *path,
                                    const char *json_body);
bool openstream_start_camera_source(obs_source_t *source);
bool openstream_stop_camera_source(obs_source_t *source);
const char *openstream_source_status(obs_source_t *source);
size_t openstream_camera_phone_count(obs_source_t *source);
const char *openstream_camera_phone_id(obs_source_t *source, size_t index);
const char *openstream_camera_phone_name(obs_source_t *source, size_t index);
bool openstream_select_camera_phone(obs_source_t *source, const char *phone_id);
bool openstream_set_camera_label(obs_source_t *source, const char *label);

void openstream_register_dock();
void openstream_unregister_dock();
