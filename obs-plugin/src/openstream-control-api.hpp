#pragma once

#include <obs.h>

// UI-safe bridge used by the frontend dock. The source reference is used only
// during the call; queued work copies its network target and JSON body.
bool openstream_is_camera_source(obs_source_t *source);
bool openstream_post_camera_command(obs_source_t *source, const char *path,
                                    const char *json_body);
bool openstream_start_camera_source(obs_source_t *source);
bool openstream_stop_camera_source(obs_source_t *source);
const char *openstream_source_status(obs_source_t *source);

void openstream_register_dock();
void openstream_unregister_dock();
void openstream_show_dock();
