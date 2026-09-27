#ifndef OLED_DISPLAY_H
#define OLED_DISPLAY_H

#include <stdint.h>
#include "esp_err.h"

#define OLED_WIDTH   128
#define OLED_HEIGHT   64
#define OLED_I2C_ADDR 0x3C

esp_err_t oled_display_init(void);

esp_err_t oled_display_clear(void);

esp_err_t oled_display_text(
    uint8_t x,
    uint8_t page,
    const char *text
);

esp_err_t oled_display_line(uint8_t page);

typedef enum {
    OLED_ICON_NONE = 0,
    OLED_ICON_LEFT,
    OLED_ICON_RIGHT,
    OLED_ICON_DESTINATION
} oled_icon_t;

/* Single display-task API: compose a frame and transmit only changed pages. */
esp_err_t oled_display_guidance(
    oled_icon_t icon,
    const char *title,
    const char *detail
);

esp_err_t oled_display_pairing(
    const char *title,
    const char *code,
    const char *prompt
);

#endif